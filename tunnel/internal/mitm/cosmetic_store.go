package mitm

import (
	"strings"
	"sync"
)

// CosmeticStore organizes cosmetic CSS rules into generic rules, per-host rules,
// and procedural cosmetic rules (:has-text, :upward, :remove) executed via JS.
type CosmeticStore struct {
	mu                sync.RWMutex
	genericCSS        string
	byHost            map[string][]string
	genericProcedural []string
	proceduralByHost  map[string][]string
}

// Global cosmetic store singleton
var defaultCosmeticStore = NewCosmeticStore()

// NewCosmeticStore creates an empty cosmetic store.
func NewCosmeticStore() *CosmeticStore {
	return &CosmeticStore{
		byHost:           make(map[string][]string),
		proceduralByHost: make(map[string][]string),
	}
}

// ParseAndLoad parses cosmetic CSS rules from raw filter list text.
// Supports standard AdBlock cosmetic rules (domain##selector, ##genericSelector),
// uBlock extended CSS (:style(...)), procedural cosmetic rules (:has-text, :upward, :remove),
// and raw CSS blocks (e.g. from adblock_cosmetic.css), even when combined in one input.
func (s *CosmeticStore) ParseAndLoad(content string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.byHost = make(map[string][]string)
	s.genericProcedural = nil
	s.proceduralByHost = make(map[string][]string)
	var genericBuilder strings.Builder

	lines := strings.Split(content, "\n")
	hasAdblockRules := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "!") || strings.HasPrefix(trimmed, "[") {
			continue
		}
		if strings.Contains(trimmed, "#@#") || strings.Contains(trimmed, "##+js") ||
			strings.Contains(trimmed, "##^") || strings.Contains(trimmed, "#%#") {
			continue
		}

		// Support both standard `##` and extended `#?#` / `#$#` cosmetic rules
		sep := ""
		sepIdx := -1
		if idx := strings.Index(trimmed, "##"); idx >= 0 {
			sep = "##"
			sepIdx = idx
		} else if idx := strings.Index(trimmed, "#?#"); idx >= 0 {
			sep = "#?#"
			sepIdx = idx
		} else if idx := strings.Index(trimmed, "#$#"); idx >= 0 {
			sep = "#$#"
			sepIdx = idx
		}

		if sepIdx >= 0 {
			hasAdblockRules = true
			domainPart := strings.TrimSpace(trimmed[:sepIdx])
			selector := strings.TrimSpace(trimmed[sepIdx+len(sep):])
			if selector == "" {
				continue
			}

			cssRule, procRule := classifyCosmeticSelector(selector)
			if procRule != "" {
				if domainPart == "" {
					s.genericProcedural = append(s.genericProcedural, procRule)
				} else {
					for _, d := range strings.Split(domainPart, ",") {
						d = strings.ToLower(strings.TrimSpace(d))
						if d != "" && !strings.HasPrefix(d, "~") {
							s.proceduralByHost[d] = append(s.proceduralByHost[d], procRule)
						}
					}
				}
				continue
			}

			if cssRule == "" {
				continue
			}

			if domainPart == "" {
				genericBuilder.WriteString(cssRule)
				genericBuilder.WriteString("\n")
			} else {
				for _, d := range strings.Split(domainPart, ",") {
					d = strings.ToLower(strings.TrimSpace(d))
					if d != "" && !strings.HasPrefix(d, "~") {
						s.byHost[d] = append(s.byHost[d], cssRule)
					}
				}
			}
			continue
		}

		// Raw CSS line or comment (e.g. from adblock_cosmetic.css)
		// If a pre-compiled .css file contains `:style(...)` or procedural selectors without `{`, handle them too.
		if !strings.Contains(trimmed, "{") && (isProceduralSelector(trimmed) || strings.Contains(strings.ToLower(trimmed), ":style(")) {
			hasAdblockRules = true
			cssRule, procRule := classifyCosmeticSelector(trimmed)
			if procRule != "" {
				s.genericProcedural = append(s.genericProcedural, procRule)
			} else if cssRule != "" {
				genericBuilder.WriteString(cssRule)
				genericBuilder.WriteString("\n")
			}
			continue
		}

		genericBuilder.WriteString(line)
		genericBuilder.WriteString("\n")
	}

	if !hasAdblockRules {
		s.genericCSS = content
	} else {
		s.genericCSS = genericBuilder.String()
	}
	logf("CosmeticStore updated: generic rules=%d bytes, hosts=%d, procedural=%d global / %d hosts",
		len(s.genericCSS), len(s.byHost), len(s.genericProcedural), len(s.proceduralByHost))
}

// AddHostCSS adds custom CSS specifically for a domain (e.g. from RuleManifest).
func (s *CosmeticStore) AddHostCSS(domain string, css string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	domain = strings.ToLower(strings.TrimSpace(domain))
	if domain != "" && css != "" {
		s.byHost[domain] = append(s.byHost[domain], css)
	}
}

// GetCSSForHost returns all cosmetic CSS applicable to the specific hostname.
func (s *CosmeticStore) GetCSSForHost(host string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	host = normalizeHost(host)
	var rules []string

	for d, cssList := range s.byHost {
		if host == d || strings.HasSuffix(host, "."+d) {
			rules = append(rules, cssList...)
		}
	}

	if len(rules) == 0 {
		return ""
	}
	return strings.Join(rules, "\n")
}

// GetProceduralForHost returns generic + host-specific procedural cosmetic rules.
func (s *CosmeticStore) GetProceduralForHost(host string) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	host = normalizeHost(host)
	var out []string
	if len(s.genericProcedural) > 0 {
		out = append(out, s.genericProcedural...)
	}
	for d, procList := range s.proceduralByHost {
		if host == d || strings.HasSuffix(host, "."+d) {
			out = append(out, procList...)
		}
	}
	return out
}

// GetGenericCSS returns the generic CSS rules applied globally.
func (s *CosmeticStore) GetGenericCSS() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.genericCSS
}

// SetCosmeticStoreRules replaces the active cosmetic rules in the default store.
func SetCosmeticStoreRules(content string) {
	defaultCosmeticStore.ParseAndLoad(content)
}

// GetHostCosmeticCSS returns CSS for host from the default store.
func GetHostCosmeticCSS(host string) string {
	return defaultCosmeticStore.GetCSSForHost(host)
}
