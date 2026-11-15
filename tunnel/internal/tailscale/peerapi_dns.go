package tailscale

import (
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"time"
)

const maxDNSQuerySize = 65535

var exitDNSUpstreams = []string{"1.1.1.1:53", "8.8.8.8:53"}

func (s *PeerAPIServer) handleDNSQuery(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	dp := s.dp
	s.mu.Unlock()

	if dp == nil || !dp.advertiseExit.Load() {
		http.Error(w, "exit node disabled", http.StatusForbidden)
		return
	}

	var reqWire []byte
	var err error
	switch r.Method {
	case http.MethodPost:
		reqWire, err = io.ReadAll(io.LimitReader(r.Body, maxDNSQuerySize+1))
		if err != nil || len(reqWire) == 0 || len(reqWire) > maxDNSQuerySize {
			http.Error(w, "invalid dns query body", http.StatusBadRequest)
			return
		}
	case http.MethodGet:
		encoded := r.URL.Query().Get("dns")
		if encoded == "" {
			http.Error(w, "missing dns query parameter", http.StatusBadRequest)
			return
		}
		reqWire, err = base64.RawURLEncoding.DecodeString(encoded)
		if err != nil || len(reqWire) == 0 || len(reqWire) > maxDNSQuerySize {
			http.Error(w, "invalid base64 dns parameter", http.StatusBadRequest)
			return
		}
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var respWire []byte
	if handler := dp.getExitDNSHandler(); handler != nil {
		remoteIP, _, errSplit := net.SplitHostPort(r.RemoteAddr)
		if errSplit != nil {
			remoteIP = r.RemoteAddr
		}
		respWire, err = handler(r.Context(), remoteIP, reqWire)
	} else {
		respWire, err = forwardExitDNS(r.Context(), dp, reqWire)
	}
	if err != nil {
		http.Error(w, "upstream dns failed", http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/dns-message")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(respWire)
}

func forwardExitDNS(ctx context.Context, dp *DataPlane, reqWire []byte) ([]byte, error) {
	dialer := dp.protectedDialer(4 * time.Second)
	var lastErr error
	for _, upstream := range exitDNSUpstreams {
		resp, err := exchangeUDP(ctx, dialer, upstream, reqWire)
		if err == nil {
			return resp, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func exchangeUDP(ctx context.Context, dialer *net.Dialer, upstream string, reqWire []byte) ([]byte, error) {
	conn, err := dialer.DialContext(ctx, "udp", upstream)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err := conn.Write(reqWire); err != nil {
		return nil, err
	}

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}
