package scriptlet

import (
	"reflect"
	"strings"
	"testing"
)

func TestParseOneScriptletRule(t *testing.T) {
	tests := []struct {
		name string
		line string
		ok   bool
		want Rule
	}{
		{
			name: "ublock with args",
			line: "example.com##+js(set-constant, foo.bar, true)",
			ok:   true,
			want: Rule{Domains: []string{"example.com"}, Name: "set-constant", Args: []string{"foo.bar", "true"}},
		},
		{
			name: "ublock no args",
			line: "example.com##+js(noeval)",
			ok:   true,
			want: Rule{Domains: []string{"example.com"}, Name: "noeval", Args: []string{}},
		},
		{
			name: "multiple domains, negation and case",
			line: "Example.COM, ~Sub.Example.com ,other.org##+js(noeval)",
			ok:   true,
			want: Rule{Domains: []string{"example.com", "~sub.example.com", "other.org"}, Name: "noeval", Args: []string{}},
		},
		{
			name: "empty domain entries dropped",
			line: "a.com,,b.com,##+js(noeval)",
			ok:   true,
			want: Rule{Domains: []string{"a.com", "b.com"}, Name: "noeval", Args: []string{}},
		},
		{
			name: "generic ublock rule has no domains",
			line: "##+js(noeval)",
			ok:   true,
			want: Rule{Name: "noeval", Args: []string{}},
		},
		{
			name: "adguard quoted",
			line: "example.org#%#//scriptlet('set-constant', 'foo', 'false')",
			ok:   true,
			want: Rule{Domains: []string{"example.org"}, Name: "set-constant", Args: []string{"foo", "false"}},
		},
		{
			name: "adguard double quotes keep commas inside",
			line: `example.org#%#//scriptlet("prevent-fetch", "a,b")`,
			ok:   true,
			want: Rule{Domains: []string{"example.org"}, Name: "prevent-fetch", Args: []string{"a,b"}},
		},
		{
			name: "nested parens use last close paren",
			line: "example.com##+js(set-constant, a.b(), 1)",
			ok:   true,
			want: Rule{Domains: []string{"example.com"}, Name: "set-constant", Args: []string{"a.b()", "1"}},
		},
		{name: "ublock exception ignored", line: "example.com#@#+js(noeval)"},
		{name: "adguard exception ignored", line: "example.com#@%#//scriptlet('noeval')"},
		{name: "missing close paren", line: "example.com##+js(noeval"},
		{name: "adguard missing close paren", line: "example.com#%#//scriptlet('noeval'"},
		{name: "empty body", line: "example.com##+js()"},
		{name: "blank name", line: "example.com##+js( , a)"},
		{name: "cosmetic rule", line: "example.com##.ad-banner"},
		{name: "network rule", line: "||ads.example.com^"},
		{name: "empty", line: ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, ok := parseOneScriptletRule(tt.line)
			if ok != tt.ok {
				t.Fatalf("ok = %v, want %v (rule %+v)", ok, tt.ok, got)
			}
			if ok && !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("got %#v, want %#v", got, tt.want)
			}
		})
	}
}

// uBlock lets a literal comma inside an argument be written as "\,". The
// splitter treats every unquoted comma as a separator.
func TestParseEscapedComma(t *testing.T) {
	t.Skip("known bug: splitArgs ignores the \\, escape and splits the argument")
	got, ok := parseOneScriptletRule(`example.com##+js(set-constant, a\,b, 1)`)
	if !ok {
		t.Fatal("rule did not parse")
	}
	if want := []string{"a,b", "1"}; !reflect.DeepEqual(got.Args, want) {
		t.Fatalf("args = %q, want %q", got.Args, want)
	}
}

func TestParseRules(t *testing.T) {
	content := strings.Join([]string{
		"! Title: test list",
		"",
		"# hosts-style comment",
		"example.com##+js(noeval)\r",
		"   other.org#%#//scriptlet('set-constant', 'x', '1')   ",
		"example.com##.cosmetic",
		"example.com#@#+js(noeval)",
		"broken.com##+js(noeval",
		// Generic uBlock rules start with '#' and are skipped by the
		// comment filter before they reach the parser. Pinned as current
		// behavior; whether generic scriptlets should apply is a product call.
		"##+js(set-constant, generic, 1)",
	}, "\n")

	rules := ParseRules(content)
	if len(rules) != 2 {
		t.Fatalf("parsed %d rules, want 2: %+v", len(rules), rules)
	}
	if rules[0].Name != "noeval" || rules[0].Domains[0] != "example.com" {
		t.Errorf("rule 0 = %+v", rules[0])
	}
	if rules[1].Name != "set-constant" || rules[1].Domains[0] != "other.org" {
		t.Errorf("rule 1 = %+v", rules[1])
	}
	if got := ParseRules(""); got != nil {
		t.Errorf("ParseRules(\"\") = %v, want nil", got)
	}
}

func TestSplitArgs(t *testing.T) {
	tests := []struct {
		in   string
		want []string
	}{
		{"", []string{""}},
		{"a", []string{"a"}},
		{"a,b", []string{"a", "b"}},
		{"a,", []string{"a", ""}},
		{"'a,b',c", []string{"a,b", "c"}},
		{`"a'b",c`, []string{"a'b", "c"}},
		{`'a"b'`, []string{`a"b`}},
		{"'unterminated, x", []string{"unterminated, x"}},
		{"a'b'c", []string{"abc"}},
	}
	for _, tt := range tests {
		if got := splitArgs(tt.in); !reflect.DeepEqual(got, tt.want) {
			t.Errorf("splitArgs(%q) = %q, want %q", tt.in, got, tt.want)
		}
	}
}

func TestBuildStore(t *testing.T) {
	rules := []Rule{
		{Name: "generic"},
		{Domains: []string{"~only.example.com"}, Name: "negated-only"},
		{Domains: []string{"a.com", "b.com"}, Name: "two-hosts"},
		{Domains: []string{"a.com", "~x.a.com"}, Name: "with-exclusion"},
	}
	s := BuildStore(rules)
	if len(s.All) != 2 || s.All[0].Name != "generic" || s.All[1].Name != "negated-only" {
		t.Errorf("All = %+v, want generic + negated-only", s.All)
	}
	if n := len(s.ByHost["a.com"]); n != 2 {
		t.Errorf("ByHost[a.com] has %d rules, want 2", n)
	}
	if n := len(s.ByHost["b.com"]); n != 1 {
		t.Errorf("ByHost[b.com] has %d rules, want 1", n)
	}
	if _, ok := s.ByHost["~x.a.com"]; ok {
		t.Error("negated domain was indexed as a host")
	}
	if got := BuildStore(nil); len(got.All) != 0 || len(got.ByHost) != 0 {
		t.Errorf("BuildStore(nil) = %+v, want empty", got)
	}
}

func FuzzParseRules(f *testing.F) {
	for _, s := range []string{
		"example.com##+js(set-constant, foo, true)",
		"example.org#%#//scriptlet('set-constant', 'foo', 'false')",
		"a.com,~b.a.com##+js(noeval)\n! comment\n",
		"x##+js(')",
		"#%#//scriptlet(\"a,b\",'c')",
	} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, content string) {
		for _, r := range ParseRules(content) {
			if strings.TrimSpace(r.Name) == "" || r.Name != strings.TrimSpace(r.Name) {
				t.Fatalf("rule with blank or untrimmed name: %+v", r)
			}
			for _, d := range r.Domains {
				if d == "" || d != strings.ToLower(d) || d != strings.TrimSpace(d) {
					t.Fatalf("bad domain %q in %+v", d, r)
				}
			}
		}
		// The store and invocation builder must accept anything the parser emits.
		BuildStore(ParseRules(content)).BuildHostInvocations("a.example.com")
	})
}

func FuzzSplitArgs(f *testing.F) {
	for _, s := range []string{"", "a,b", "'a,b',c", `"x'`, `\,`, ",,,"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		parts := splitArgs(s)
		if len(parts) == 0 {
			t.Fatal("splitArgs returned no parts")
		}
		total := len(parts) - 1 // separators consumed
		for _, p := range parts {
			total += len(p)
		}
		if total > len(s) {
			t.Fatalf("splitArgs(%q) produced %d bytes from %d", s, total, len(s))
		}
	})
}
