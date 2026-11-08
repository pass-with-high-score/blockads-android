package tailscale

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	// DefaultPeerAPIPort is the standard TCP port for Tailscale PeerAPI.
	DefaultPeerAPIPort uint16 = 41641
)

// TaildropReceiver is implemented by upper layers (e.g. Kotlin via gomobile)
// to receive incoming files transferred via Taildrop.
type TaildropReceiver interface {
	OnFileReceived(fileName string, tempPath string, size int64) bool
}

// PeerAPIServer handles node-to-node HTTP requests over the Tailscale netstack,
// primarily Taildrop incoming file transfers (PUT /v0/put/*).
type PeerAPIServer struct {
	mu       sync.Mutex
	port     uint16
	tempDir  string
	receiver TaildropReceiver
	listener net.Listener
	server   *http.Server
	running  bool
}

// NewPeerAPIServer creates a new PeerAPI server instance.
func NewPeerAPIServer(port uint16, tempDir string, receiver TaildropReceiver) *PeerAPIServer {
	if port == 0 {
		port = DefaultPeerAPIPort
	}
	s := &PeerAPIServer{
		port:     port,
		tempDir:  tempDir,
		receiver: receiver,
	}
	return s
}

// Port returns the configured port for PeerAPI.
func (s *PeerAPIServer) Port() uint16 {
	return s.port
}

// SetReceiver updates the Taildrop file receiver callback.
func (s *PeerAPIServer) SetReceiver(rcv TaildropReceiver) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.receiver = rcv
}

// SetTempDir updates the staging directory for temporary incoming files.
func (s *PeerAPIServer) SetTempDir(dir string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tempDir = dir
}

// Start binds to the netstack TCP port and starts serving HTTP requests.
func (s *PeerAPIServer) Start(dp *DataPlane) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return nil
	}
	if dp == nil {
		return fmt.Errorf("peerapi: dataplane is nil")
	}

	ln, err := dp.ListenTCP(nil, int(s.port))
	if err != nil {
		return fmt.Errorf("peerapi listen TCP port %d: %w", s.port, err)
	}
	s.listener = ln
	s.running = true

	s.server = &http.Server{
		Handler:      s,
		ReadTimeout:  5 * time.Minute,
		WriteTimeout: 5 * time.Minute,
	}

	go func() {
		logf("[Tailscale/PeerAPI] Server listening on netstack port %d", s.port)
		if err := s.server.Serve(ln); err != nil && err != http.ErrServerClosed {
			logf("[Tailscale/PeerAPI] Serve error: %v", err)
		}
	}()

	return nil
}

// Stop shuts down the HTTP server and closes the underlying listener.
func (s *PeerAPIServer) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}
	s.running = false

	if s.server != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = s.server.Shutdown(ctx)
		cancel()
		s.server = nil
	}
	if s.listener != nil {
		_ = s.listener.Close()
		s.listener = nil
	}
	logf("[Tailscale/PeerAPI] Server stopped")
}

// ServeHTTP routes incoming requests directly without ServeMux path cleaning.
func (s *PeerAPIServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPut && (strings.HasPrefix(r.URL.Path, "/v0/put/") || strings.HasPrefix(r.URL.EscapedPath(), "/v0/put/")) {
		s.handleTaildropPut(w, r)
		return
	}
	s.handleGeneric(w, r)
}

func (s *PeerAPIServer) handleGeneric(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, "{}\n")
}

func (s *PeerAPIServer) handleTaildropPut(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	rawPath := r.URL.EscapedPath()
	rawPrefix := "/v0/put/"
	if !strings.HasPrefix(rawPath, rawPrefix) {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}

	encodedName := strings.TrimPrefix(rawPath, rawPrefix)
	if encodedName == "" {
		http.Error(w, "missing filename", http.StatusBadRequest)
		return
	}

	fileName, err := url.PathUnescape(encodedName)
	if err != nil || fileName == "" {
		fileName = encodedName
	}
	fileName = filepath.Base(fileName)
	if fileName == "" || fileName == "." || fileName == "/" {
		fileName = fmt.Sprintf("taildrop_%d", time.Now().Unix())
	}

	s.mu.Lock()
	targetDir := s.tempDir
	receiver := s.receiver
	s.mu.Unlock()

	if targetDir == "" {
		targetDir = os.TempDir()
	}
	_ = os.MkdirAll(targetDir, 0700)

	tmpFile, err := os.CreateTemp(targetDir, fmt.Sprintf("td_*_%s", fileName))
	if err != nil {
		logf("[Tailscale/PeerAPI] failed to create temp file: %v", err)
		http.Error(w, "failed to create staging file", http.StatusInternalServerError)
		return
	}
	tmpPath := tmpFile.Name()

	n, copyErr := io.Copy(tmpFile, r.Body)
	closeErr := tmpFile.Close()

	if copyErr != nil || closeErr != nil {
		_ = os.Remove(tmpPath)
		logf("[Tailscale/PeerAPI] failed to save file %s: %v", fileName, copyErr)
		http.Error(w, "failed to write file", http.StatusInternalServerError)
		return
	}

	logf("[Tailscale/PeerAPI] Received Taildrop file: %s (%d bytes) saved to %s", fileName, n, tmpPath)

	handled := false
	if receiver != nil {
		handled = receiver.OnFileReceived(fileName, tmpPath, n)
	}

	if !handled {
		logf("[Tailscale/PeerAPI] No receiver handled file %s, leaving at %s", fileName, tmpPath)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, "{}\n")
}
