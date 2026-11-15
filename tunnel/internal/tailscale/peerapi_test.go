package tailscale

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

type mockTaildropReceiver struct {
	called   bool
	fileName string
	tempPath string
	size     int64
}

func (m *mockTaildropReceiver) OnFileReceived(fileName string, tempPath string, size int64) bool {
	m.called = true
	m.fileName = fileName
	m.tempPath = tempPath
	m.size = size
	return true
}

func TestPeerAPITaildropPut(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "taildrop_test_*")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	defer os.RemoveAll(tempDir)

	mockRecv := &mockTaildropReceiver{}
	server := NewPeerAPIServer(41641, tempDir, mockRecv)

	// Test 1: Successful file upload
	testPayload := []byte("Hello Taildrop from Antigravity!")
	req := httptest.NewRequest(http.MethodPut, "/v0/put/test_doc%20file.txt", bytes.NewReader(testPayload))
	rec := httptest.NewRecorder()

	server.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d: %s", rec.Code, rec.Body.String())
	}

	if !mockRecv.called {
		t.Fatalf("expected receiver to be called")
	}
	if mockRecv.fileName != "test_doc file.txt" {
		t.Errorf("expected fileName 'test_doc file.txt', got %q", mockRecv.fileName)
	}
	if mockRecv.size != int64(len(testPayload)) {
		t.Errorf("expected size %d, got %d", len(testPayload), mockRecv.size)
	}

	// Verify temp file content on disk
	content, err := os.ReadFile(mockRecv.tempPath)
	if err != nil {
		t.Fatalf("ReadFile %s: %v", mockRecv.tempPath, err)
	}
	if !bytes.Equal(content, testPayload) {
		t.Errorf("content mismatch: got %q, want %q", content, testPayload)
	}

	// Test 2: Path traversal sanitization
	mockRecv.called = false
	req2 := httptest.NewRequest(http.MethodPut, "/v0/put/../../etc/passwd", bytes.NewReader([]byte("fake")))
	rec2 := httptest.NewRecorder()
	server.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rec2.Code)
	}
	if mockRecv.fileName != "passwd" {
		t.Errorf("expected sanitized fileName 'passwd', got %q", mockRecv.fileName)
	}
	if filepath.Dir(mockRecv.tempPath) != tempDir {
		t.Errorf("tempPath %s escaped tempDir %s", mockRecv.tempPath, tempDir)
	}

	// Test 3: Generic GET returns 200 OK
	req3 := httptest.NewRequest(http.MethodGet, "/v0/goroutines", nil)
	rec3 := httptest.NewRecorder()
	server.ServeHTTP(rec3, req3)

	if rec3.Code != http.StatusOK {
		t.Errorf("expected 200 OK on GET, got %d", rec3.Code)
	}
}
