package safesearch

import (
	"net"
	"sync"
	"testing"
)

const (
	qA    = 1
	qAAAA = 28
	qMX   = 15
	qTXT  = 16
)

func TestCheckDisabledByDefault(t *testing.T) {
	s := NewSafeSearch()
	if got := s.Check("www.google.com", qA); got.Action != ActionNone {
		t.Fatalf("disabled SafeSearch redirected: %+v", got)
	}
	if yt, _ := s.CheckYouTube("www.youtube.com", qA); yt {
		t.Fatal("disabled YouTube restriction redirected")
	}
}

func TestCheck(t *testing.T) {
	s := NewSafeSearch()
	s.SetEnabled(true)

	tests := []struct {
		domain string
		qtype  uint16
		want   string // "" = no redirect
	}{
		{"google.com", qA, "forcesafesearch.google.com"},
		{"www.google.com", qA, "forcesafesearch.google.com"},
		{"www.google.com", qAAAA, "forcesafesearch.google.com"},
		{"google.co.uk", qA, "forcesafesearch.google.com"},
		{"www.google.co.uk", qA, "forcesafesearch.google.com"},
		{"WWW.Google.COM", qA, "forcesafesearch.google.com"},
		{"www.google.com.", qA, "forcesafesearch.google.com"},
		{"bing.com", qA, "strict.bing.com"},
		{"www.bing.com", qA, "strict.bing.com"},
		{"cn.bing.com", qAAAA, "strict.bing.com"},

		// Only A and AAAA are rewritten.
		{"www.google.com", qMX, ""},
		{"www.google.com", qTXT, ""},
		{"bing.com", 0, ""},

		// Non-search Google hosts are left alone.
		{"mail.google.com", qA, ""},
		{"maps.google.co.uk", qA, ""},
		{"forcesafesearch.google.com", qA, ""},
		{"googleapis.com", qA, ""},
		{"notgoogle.com", qA, ""},
		{"bing.co", qA, ""},
		{"notbing.com", qA, ""},
		{"example.com", qA, ""},
		{"", qA, ""},
	}
	for _, tt := range tests {
		got := s.Check(tt.domain, tt.qtype)
		if tt.want == "" {
			if got.Action != ActionNone {
				t.Errorf("Check(%q, %d) = %+v, want no redirect", tt.domain, tt.qtype, got)
			}
			continue
		}
		if got.Action != ActionRedirect || got.RedirectDomain != tt.want {
			t.Errorf("Check(%q, %d) = %+v, want redirect to %s", tt.domain, tt.qtype, got, tt.want)
		}
	}

	s.SetEnabled(false)
	if got := s.Check("www.google.com", qA); got.Action != ActionNone {
		t.Errorf("Check after disable = %+v, want none", got)
	}
}

// The "google." pattern matches any hostname whose first (or www-prefixed
// second) label is "google", whatever the suffix. That rewrites private and
// unrelated names that merely start with "google".
func TestCheckGoogleOverMatch(t *testing.T) {
	t.Skip("known bug: \"google.\" pattern over-matches google.internal, google.evil.example and bare \"google\"")
	s := NewSafeSearch()
	s.SetEnabled(true)
	for _, d := range []string{"google.internal", "google.evil.example", "www.google.lan", "google"} {
		if got := s.Check(d, qA); got.Action != ActionNone {
			t.Errorf("Check(%q) = %+v, want no redirect", d, got)
		}
	}
}

func TestCheckYouTube(t *testing.T) {
	s := NewSafeSearch()
	s.SetYouTubeRestricted(true)

	tests := []struct {
		domain string
		qtype  uint16
		want   bool
	}{
		{"youtube.com", qA, true},
		{"www.youtube.com", qA, true},
		{"m.youtube.com", qAAAA, true},
		{"WWW.YOUTUBE.COM", qA, true},
		{"youtube-nocookie.com", qA, true},
		{"www.youtube-nocookie.com", qA, true},
		{"youtube.googleapis.com", qA, true},
		{"youtubei.googleapis.com", qA, true},
		{"www.youtube.com", qMX, false},
		{"notyoutube.com", qA, false},
		{"youtube.com.evil.example", qA, false},
		{"googleapis.com", qA, false},
		{"example.com", qA, false},
	}
	for _, tt := range tests {
		got, dom := s.CheckYouTube(tt.domain, tt.qtype)
		if got != tt.want {
			t.Errorf("CheckYouTube(%q, %d) = %v, want %v", tt.domain, tt.qtype, got, tt.want)
			continue
		}
		if got && dom != "restrict.youtube.com" {
			t.Errorf("CheckYouTube(%q) redirect = %q, want restrict.youtube.com", tt.domain, dom)
		}
		if !got && dom != "" {
			t.Errorf("CheckYouTube(%q) redirect = %q on miss, want empty", tt.domain, dom)
		}
	}

	// YouTube restriction is independent of SafeSearch.
	if got := s.Check("www.google.com", qA); got.Action != ActionNone {
		t.Errorf("SafeSearch fired with only YouTube restriction on: %+v", got)
	}
	s.SetYouTubeRestricted(false)
	if got, _ := s.CheckYouTube("www.youtube.com", qA); got {
		t.Error("CheckYouTube still redirects after disable")
	}
}

func TestIPCache(t *testing.T) {
	s := NewSafeSearch()
	if ip := s.GetCachedIP("forcesafesearch.google.com"); ip != nil {
		t.Fatalf("empty cache returned %v", ip)
	}
	want := net.IPv4(216, 239, 38, 120)
	s.CacheIP("forcesafesearch.google.com", want)
	if got := s.GetCachedIP("forcesafesearch.google.com"); !got.Equal(want) {
		t.Fatalf("GetCachedIP = %v, want %v", got, want)
	}
	if got := s.GetCachedIP("strict.bing.com"); got != nil {
		t.Fatalf("GetCachedIP(other) = %v, want nil", got)
	}
	s.ClearCache()
	if got := s.GetCachedIP("forcesafesearch.google.com"); got != nil {
		t.Fatalf("GetCachedIP after ClearCache = %v, want nil", got)
	}
}

func TestMatchesDomain(t *testing.T) {
	tests := []struct {
		domain, pattern string
		want            bool
	}{
		{"bing.com", "bing.com", true},
		{"www.bing.com", "bing.com", true},
		{"a.b.bing.com", "bing.com", true},
		{"xbing.com", "bing.com", false},
		{"bing.com.evil", "bing.com", false},
		{"google.com", "google.", true},
		{"www.google.de", "google.", true},
		{"news.google.com", "google.", false},
		{"www.news.google.com", "google.", false},
		{"", "google.", false},
		{"", "bing.com", false},
	}
	for _, tt := range tests {
		if got := matchesDomain(tt.domain, tt.pattern); got != tt.want {
			t.Errorf("matchesDomain(%q, %q) = %v, want %v", tt.domain, tt.pattern, got, tt.want)
		}
	}
}

// Check, CheckYouTube and the IP cache are hit from every DNS goroutine while
// the UI toggles the settings. Run under -race.
func TestConcurrentAccess(t *testing.T) {
	s := NewSafeSearch()
	var wg sync.WaitGroup
	const n = 8
	for i := 0; i < n; i++ {
		wg.Add(4)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				s.Check("www.google.com", qA)
				s.CheckYouTube("www.youtube.com", qAAAA)
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				s.CacheIP("forcesafesearch.google.com", net.IPv4(1, 2, 3, byte(j)))
				_ = s.GetCachedIP("forcesafesearch.google.com")
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				s.SetEnabled(j%2 == 0)
				s.SetYouTubeRestricted(j%3 == 0)
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				s.ClearCache()
			}
		}(i)
	}
	wg.Wait()
}
