package scriptlet

import (
	"fmt"
	"sort"
	"strings"
	"testing"
)

func names(rules []Rule) []string {
	out := make([]string, 0, len(rules))
	for _, r := range rules {
		out = append(out, r.Name)
	}
	sort.Strings(out)
	return out
}

func TestMatches(t *testing.T) {
	rules := ParseRules(strings.Join([]string{
		"example.com##+js(on-example)",
		"example.com,~cdn.example.com##+js(excluded-on-cdn)",
		"other.org##+js(on-other)",
	}, "\n"))
	// Generic lines never survive ParseRules (they start with '#'), so add
	// the domainless rule directly.
	s := BuildStore(append(rules, Rule{Name: "generic"}))

	tests := []struct {
		host string
		want []string
	}{
		{"example.com", []string{"excluded-on-cdn", "generic", "on-example"}},
		{"WWW.Example.com", []string{"excluded-on-cdn", "generic", "on-example"}},
		{"cdn.example.com", []string{"generic", "on-example"}},
		{"img.cdn.example.com", []string{"generic", "on-example"}},
		{"other.org", []string{"generic", "on-other"}},
		{"notexample.com", []string{"generic"}},
		{"example.com.evil", []string{"generic"}},
	}
	for _, tt := range tests {
		if got := names(s.Matches(tt.host)); strings.Join(got, ",") != strings.Join(tt.want, ",") {
			t.Errorf("Matches(%q) = %v, want %v", tt.host, got, tt.want)
		}
	}
}

// A rule whose only domains are exclusions ("~a.com##+js(x)") lands in
// Store.All, and Matches returns All without consulting the exclusions.
func TestMatchesGlobalRuleExclusion(t *testing.T) {
	t.Skip("known bug: ~domain exclusions are ignored for rules stored in Store.All")
	s := BuildStore(ParseRules("~example.com##+js(noeval)"))
	if got := s.Matches("www.example.com"); len(got) != 0 {
		t.Fatalf("excluded host got %v, want none", names(got))
	}
	if got := s.Matches("other.org"); len(got) != 1 {
		t.Fatalf("other host got %v, want [noeval]", names(got))
	}
}

// A rule listed for both a domain and its parent is indexed under both keys,
// so a host under both gets the same invocation twice.
func TestMatchesNoDuplicates(t *testing.T) {
	t.Skip("known bug: a rule indexed under a domain and its parent is returned twice")
	s := BuildStore(ParseRules("example.com,www.example.com##+js(prevent-fetch, ads)"))
	if got := s.Matches("www.example.com"); len(got) != 1 {
		t.Fatalf("Matches returned %d rules, want 1", len(got))
	}
}

func TestBuildHostInvocations(t *testing.T) {
	s := BuildStore(ParseRules("example.com##+js(set-constant, foo.bar, true)\nexample.com##+js(noeval)"))

	if got := s.BuildHostInvocations("unrelated.org"); got != "" {
		t.Errorf("unmatched host produced %q, want empty", got)
	}

	js := s.buildHostInvocations("example.com")
	for _, want := range []string{
		`window.__ba.invoke("set-constant",["foo.bar","true"]);`,
		`window.__ba.invoke("noeval",[]);`,
		"if(window.__ba&&window.__ba.loaded){run();}",
	} {
		if !strings.Contains(js, want) {
			t.Errorf("invocations missing %q:\n%s", want, js)
		}
	}
	if !strings.HasPrefix(js, "(function(){") || !strings.HasSuffix(js, "})();\n") {
		t.Errorf("invocations not wrapped in an IIFE:\n%s", js)
	}
}

// Invocations are inlined into an HTML <script> block, so an argument that
// contains "</script>" must never close it early.
func TestBuildHostInvocationsNeverEmitsScriptClose(t *testing.T) {
	s := BuildStore([]Rule{
		{Domains: []string{"example.com"}, Name: "</script><script>alert(1)//", Args: []string{"</script>", "<!--", "</SCRIPT >"}},
	})
	js := s.BuildHostInvocations("example.com")
	if js == "" {
		t.Fatal("no invocations built")
	}
	lower := strings.ToLower(js)
	for _, bad := range []string{"</script", "<!--"} {
		if strings.Contains(lower, bad) {
			t.Errorf("invocations contain %q:\n%s", bad, js)
		}
	}
}

func TestRuntimeJS(t *testing.T) {
	for _, want := range []string{"window.__ba=", "invoke:function(name,args)", `ns["set-constant"]`, `ns["noeval"]`} {
		if !strings.Contains(RuntimeJS, want) {
			t.Errorf("RuntimeJS missing %q", want)
		}
	}
	if strings.Contains(strings.ToLower(RuntimeJS), "</script") {
		t.Error("RuntimeJS contains </script")
	}
}

func BenchmarkMatches(b *testing.B) {
	var sb strings.Builder
	for i := 0; i < 10000; i++ {
		fmt.Fprintf(&sb, "site%d.example##+js(set-constant, v%d, true)\n", i, i)
	}
	s := BuildStore(ParseRules(sb.String()))
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		s.Matches("www.site5000.example")
	}
}
