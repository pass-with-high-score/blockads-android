package mitm

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestClassifyCosmeticSelector_StyleAndProcedural(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		wantCSS  string
		wantProc string
	}{
		{
			name:    "standard selector",
			input:   ".ad-banner",
			wantCSS: ".ad-banner { display: none !important; }",
		},
		{
			name:    "ublock style rule",
			input:   "body:style(overflow: auto !important; background: rgba(0,0,0,0) !important;)",
			wantCSS: "body { overflow: auto !important; background: rgba(0,0,0,0) !important; }",
		},
		{
			name:    "already formatted CSS",
			input:   "div.overlay { visibility: hidden !important; }",
			wantCSS: "div.overlay { visibility: hidden !important; }",
		},
		{
			name:     "procedural has-text and upward",
			input:    "div.card:has-text(Sponsored):upward(2)",
			wantProc: "div.card:has-text(Sponsored):upward(2)",
		},
		{
			name:     "procedural remove",
			input:    ".popup-modal:remove()",
			wantProc: ".popup-modal:remove()",
		},
		{
			name:     "mixed procedural with style",
			input:    "article:has-text(/Promoted/i):style(opacity: 0 !important;)",
			wantProc: "article:has-text(/Promoted/i):style(opacity: 0 !important;)",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			gotCSS, gotProc := classifyCosmeticSelector(tc.input)
			if gotCSS != tc.wantCSS {
				t.Errorf("css = %q, want %q", gotCSS, tc.wantCSS)
			}
			if gotProc != tc.wantProc {
				t.Errorf("proc = %q, want %q", gotProc, tc.wantProc)
			}
		})
	}
}

func TestCosmeticStore_ExtendedCSSAndProcedural(t *testing.T) {
	store := NewCosmeticStore()
	content := strings.Join([]string{
		"example.com##.banner",
		"example.com##body:style(overflow: auto !important;)",
		"example.com##div.feed-item:has-text(Sponsored):upward(1)",
		"example.com#?#.modal:remove()",
		"##.global-ad",
		"##.global-promo:has-text(Buy Now)",
	}, "\n")

	store.ParseAndLoad(content)

	hostCSS := store.GetCSSForHost("sub.example.com")
	if !strings.Contains(hostCSS, ".banner { display: none !important; }") {
		t.Fatalf("missing standard host CSS: %s", hostCSS)
	}
	if !strings.Contains(hostCSS, "body { overflow: auto !important; }") {
		t.Fatalf("missing :style() CSS conversion: %s", hostCSS)
	}
	if strings.Contains(hostCSS, ":has-text(") || strings.Contains(hostCSS, ":remove()") {
		t.Fatalf("procedural rules leaked into CSS stylesheet: %s", hostCSS)
	}

	proc := store.GetProceduralForHost("sub.example.com")
	if len(proc) != 3 {
		t.Fatalf("expected 3 procedural rules (1 global + 2 host), got %d: %v", len(proc), proc)
	}
}

func TestModuleStore_StyleAndProceduralServedViaLocalServer(t *testing.T) {
	rawJSON := `{
		"domains": {
			"news.example.com": {
				"css": [
					".top-ad",
					"html:style(overflow-y: scroll !important;)",
					"div.card:has-text(Sponsored):upward(2)",
					".nag-overlay:remove()"
				]
			}
		}
	}`
	if err := ActiveModuleStore.UpdateFromJSON(rawJSON); err != nil {
		t.Fatalf("UpdateFromJSON failed: %v", err)
	}
	defer ActiveModuleStore.UpdateFromJSON("")

	cssRules := ActiveModuleStore.GetCosmeticCSS("news.example.com")
	joinedCSS := strings.Join(cssRules, "\n")
	if !strings.Contains(joinedCSS, "html { overflow-y: scroll !important; }") {
		t.Fatalf("expected :style() converted in module CSS, got: %s", joinedCSS)
	}
	if strings.Contains(joinedCSS, ":has-text(") || strings.Contains(joinedCSS, ":remove()") {
		t.Fatalf("procedural selector leaked into module CSS: %s", joinedCSS)
	}

	procRules := ActiveModuleStore.GetProceduralCSS("news.example.com")
	if len(procRules) != 2 {
		t.Fatalf("expected 2 procedural rules in module store, got %d: %v", len(procRules), procRules)
	}

	req := &http.Request{
		Method: http.MethodGet,
		URL:    &url.URL{Path: "/sl-news.example.com.js"},
	}
	resp := ServeLocalAsset(req)
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	buf := make([]byte, resp.ContentLength)
	_, _ = resp.Body.Read(buf)
	body := string(buf)
	if !strings.Contains(body, "Procedural cosmetic runtime") || !strings.Contains(body, "Sponsored") || !strings.Contains(body, ":remove()") {
		t.Fatalf("expected procedural runtime and rules in /sl-news.example.com.js, got: %s", body)
	}
}
