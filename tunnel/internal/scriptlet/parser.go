package scriptlet

import (
	"strings"
)

// Rule is a single parsed +js() or exception (#@#+js) rule.
type Rule struct {
	// Domains the rule applies to. Empty = all domains. Entries
	// starting with "~" are NEGATED (exclude).
	Domains []string

	// Scriptlet name (e.g., "set-constant").
	Name string

	// Positional args.
	Args []string

	// IsException is true for unbreak rules (#@#+js or #@%#//scriptlet).
	IsException bool
}

// ParseRules takes the raw text of a filter list and extracts
// every +js() rule (and #@#+js exception rule) it can parse.
func ParseRules(content string) []Rule {
	var out []Rule
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "!") {
			continue
		}
		if strings.HasPrefix(line, "#") &&
			!strings.HasPrefix(line, "##+js(") &&
			!strings.HasPrefix(line, "#%#//scriptlet(") &&
			!strings.HasPrefix(line, "#@#+js(") &&
			!strings.HasPrefix(line, "#@%#//scriptlet(") {
			continue
		}
		rule, ok := parseOneScriptletRule(line)
		if !ok {
			continue
		}
		out = append(out, rule)
	}
	return out
}

// parseOneScriptletRule parses a single line. Returns (rule, true) on
// success or zero value + false otherwise. Recognises both uBlock
// (##+js / #@#+js) and AdGuard (#%#//scriptlet / #@%#//scriptlet) syntaxes.
func parseOneScriptletRule(line string) (Rule, bool) {
	var domainsStr, body string
	isException := false

	switch {
	case strings.Contains(line, "#@#+js("):
		idx := strings.Index(line, "#@#+js(")
		domainsStr = line[:idx]
		rest := line[idx+len("#@#+js("):]
		closeIdx := strings.LastIndex(rest, ")")
		if closeIdx < 0 {
			return Rule{}, false
		}
		body = rest[:closeIdx]
		isException = true
	case strings.Contains(line, "#@%#//scriptlet("):
		idx := strings.Index(line, "#@%#//scriptlet(")
		domainsStr = line[:idx]
		rest := line[idx+len("#@%#//scriptlet("):]
		closeIdx := strings.LastIndex(rest, ")")
		if closeIdx < 0 {
			return Rule{}, false
		}
		body = rest[:closeIdx]
		isException = true
	case strings.Contains(line, "##+js("):
		idx := strings.Index(line, "##+js(")
		domainsStr = line[:idx]
		rest := line[idx+len("##+js("):]
		closeIdx := strings.LastIndex(rest, ")")
		if closeIdx < 0 {
			return Rule{}, false
		}
		body = rest[:closeIdx]
	case strings.Contains(line, "#%#//scriptlet("):
		idx := strings.Index(line, "#%#//scriptlet(")
		domainsStr = line[:idx]
		rest := line[idx+len("#%#//scriptlet("):]
		closeIdx := strings.LastIndex(rest, ")")
		if closeIdx < 0 {
			return Rule{}, false
		}
		body = rest[:closeIdx]
	default:
		return Rule{}, false
	}

	parts := splitArgs(body)
	if len(parts) == 0 {
		return Rule{}, false
	}
	name := strings.TrimSpace(parts[0])
	if name == "" && !isException {
		return Rule{}, false
	}

	args := make([]string, 0, len(parts)-1)
	for _, p := range parts[1:] {
		args = append(args, strings.TrimSpace(p))
	}

	var domains []string
	if domainsStr != "" {
		for _, d := range strings.Split(domainsStr, ",") {
			d = strings.TrimSpace(strings.ToLower(d))
			if d != "" {
				domains = append(domains, d)
			}
		}
	}

	return Rule{
		Domains:     domains,
		Name:        name,
		Args:        args,
		IsException: isException,
	}, true
}

func splitArgs(s string) []string {
	var out []string
	var cur strings.Builder
	inQuote := false
	var quoteCh byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case inQuote:
			if c == quoteCh {
				inQuote = false
				continue
			}
			cur.WriteByte(c)
		case c == '\'' || c == '"':
			inQuote = true
			quoteCh = c
		case c == ',':
			out = append(out, cur.String())
			cur.Reset()
		default:
			cur.WriteByte(c)
		}
	}
	out = append(out, cur.String())
	return out
}

// BuildStore organises parsed rules into the lookup structure
// used by the per-host invocation builder.
func BuildStore(rules []Rule) *Store {
	s := &Store{
		ByHost:           make(map[string][]Rule),
		ExceptionsByHost: make(map[string][]Rule),
	}
	for _, r := range rules {
		positive := positiveDomains(r)
		if r.IsException {
			if len(positive) == 0 {
				s.Exceptions = append(s.Exceptions, r)
			} else {
				for _, d := range positive {
					s.ExceptionsByHost[d] = append(s.ExceptionsByHost[d], r)
				}
			}
			continue
		}
		if len(positive) == 0 {
			s.All = append(s.All, r)
			continue
		}
		for _, d := range positive {
			s.ByHost[d] = append(s.ByHost[d], r)
		}
	}
	return s
}

func positiveDomains(r Rule) []string {
	var out []string
	for _, d := range r.Domains {
		if !strings.HasPrefix(d, "~") {
			out = append(out, d)
		}
	}
	return out
}
