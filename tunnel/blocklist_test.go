package tunnel

import (
	"os"
	"testing"
)

func TestMmapFd(t *testing.T) {
	// Create a temporary file with known content
	content := []byte("example.com\n# comment\ncloudflare-dns.com\n1.1.1.1\n")
	tmpFile, err := os.CreateTemp("", "blocklist_test_*.txt")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())
	defer tmpFile.Close()

	if _, err := tmpFile.Write(content); err != nil {
		t.Fatalf("Failed to write to temp file: %v", err)
	}

	// Test mmapFd with exact length
	slice, cleanup, err := mmapFd(int(tmpFile.Fd()), 0, int64(len(content)))
	if err != nil {
		t.Fatalf("mmapFd failed: %v", err)
	}
	defer cleanup()

	if string(slice) != string(content) {
		t.Errorf("mmapFd content mismatch: got %q, want %q", string(slice), string(content))
	}
}

func TestSetDoHBlocklistFromFd(t *testing.T) {
	content := []byte("# DoH domains\ncloudflare-dns.com\ndns.google\n")
	tmpFile, err := os.CreateTemp("", "doh_test_*.txt")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())
	defer tmpFile.Close()

	if _, err := tmpFile.Write(content); err != nil {
		t.Fatalf("Failed to write to temp file: %v", err)
	}

	engine := NewEngine()
	err = engine.SetDoHBlocklistFromFd(int(tmpFile.Fd()), 0, int64(len(content)))
	if err != nil {
		t.Fatalf("SetDoHBlocklistFromFd failed: %v", err)
	}

	if !engine.isDoHDomain("cloudflare-dns.com") {
		t.Errorf("expected cloudflare-dns.com to be blocked")
	}
	if !engine.isDoHDomain("dns.google") {
		t.Errorf("expected dns.google to be blocked")
	}
	if engine.isDoHDomain("example.com") {
		t.Errorf("did not expect example.com to be blocked")
	}
}

func TestSetCosmeticCSSFromFile(t *testing.T) {
	cssContent := "##.ad-banner { display: none !important; }"
	tmpFile, err := os.CreateTemp("", "cosmetic_*.css")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())
	defer tmpFile.Close()

	if _, err := tmpFile.WriteString(cssContent); err != nil {
		t.Fatalf("Failed to write to temp file: %v", err)
	}

	engine := NewEngine()
	err = engine.SetCosmeticCSSFromFile(tmpFile.Name())
	if err != nil {
		t.Fatalf("SetCosmeticCSSFromFile failed: %v", err)
	}
}
