package tunnel

import (
	"testing"
)

func TestDoHBlocklist(t *testing.T) {
	e := NewEngine()
	blocklist := `
# Public DoH providers
cloudflare-dns.com
dns.google
dns.quad9.net
`
	e.SetDoHBlocklist(blocklist)

	// Test exact matches
	if !e.isDoHDomain("cloudflare-dns.com") {
		t.Errorf("expected cloudflare-dns.com to be blocked")
	}
	if !e.isDoHDomain("dns.google") {
		t.Errorf("expected dns.google to be blocked")
	}
	if !e.isDoHDomain("dns.quad9.net") {
		t.Errorf("expected dns.quad9.net to be blocked")
	}

	// Test subdomain matches
	if !e.isDoHDomain("sub.cloudflare-dns.com") {
		t.Errorf("expected sub.cloudflare-dns.com to be blocked via parent rule")
	}
	if !e.isDoHDomain("foo.bar.dns.google") {
		t.Errorf("expected foo.bar.dns.google to be blocked via parent rule")
	}

	// Test case-insensitivity
	if !e.isDoHDomain("DNS.GOOGLE") {
		t.Errorf("expected DNS.GOOGLE (uppercase) to be blocked")
	}

	// Test allowed non-DoH domain
	if e.isDoHDomain("google.com") {
		t.Errorf("did not expect google.com to be blocked by DoH list")
	}
	if e.isDoHDomain("example.com") {
		t.Errorf("did not expect example.com to be blocked by DoH list")
	}
}
