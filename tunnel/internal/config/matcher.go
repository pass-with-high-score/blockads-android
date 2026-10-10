package config

import (
	"net"
	"strings"
)

// Matcher evaluates traffic against parsed rules in declaration order.
type Matcher struct {
	rules       []Rule
	finalPolicy string
	geoIPLookup func(net.IP) string
}

// SetGeoIPLookup sets the GeoIP country resolution function.
func (m *Matcher) SetGeoIPLookup(fn func(net.IP) string) {
	m.geoIPLookup = fn
}

// NewMatcher creates a new Rule Matcher from a parsed Config.
func NewMatcher(cfg *Config) *Matcher {
	if cfg == nil {
		return &Matcher{rules: nil, finalPolicy: PolicyDirect}
	}
	return &Matcher{
		rules:       cfg.Rules,
		finalPolicy: cfg.FinalPolicy,
	}
}

// Match evaluates domain and client IP string against rules, returning (policy, matchedRuleStr).
func (m *Matcher) Match(domain string, ipStr string) (string, string) {
	var ip net.IP
	if ipStr != "" {
		ip = net.ParseIP(strings.TrimSpace(ipStr))
	}
	return m.MatchNetIP(domain, ip)
}

// MatchNetIP evaluates domain and client net.IP directly without string allocation.
func (m *Matcher) MatchNetIP(domain string, ip net.IP) (string, string) {
	normDomain := NormalizeDomain(domain)
	for i := range m.rules {
		r := &m.rules[i]
		if r.Type == RuleFinal {
			continue // FINAL is a fallback, specific rules always take precedence
		}
		if m.matches(r, normDomain, ip) {
			return r.Policy, r.Value
		}
	}
	return m.finalPolicy, "FINAL"
}

// MatchWithUA evaluates domain, IP, and user-agent against rules.
func (m *Matcher) MatchWithUA(domain string, ipStr string, userAgent string) (string, string) {
	normDomain := NormalizeDomain(domain)
	var ip net.IP
	if ipStr != "" {
		ip = net.ParseIP(strings.TrimSpace(ipStr))
	}
	normUA := strings.ToLower(strings.TrimSpace(userAgent))

	for i := range m.rules {
		r := &m.rules[i]
		if r.Type == RuleFinal {
			continue // FINAL is a fallback, specific rules always take precedence
		}
		if r.Type == RuleUserAgent {
			if normUA != "" && matchWildcard(r.Value, normUA) {
				return r.Policy, r.Value
			}
			continue
		}
		if m.matches(r, normDomain, ip) {
			return r.Policy, r.Value
		}
	}

	return m.finalPolicy, "FINAL"
}

func (m *Matcher) matches(r *Rule, domain string, ip net.IP) bool {
	switch r.Type {
	case RuleHost:
		return domain == r.Value

	case RuleHostSuffix:
		return domain == r.Value || strings.HasSuffix(domain, "."+r.Value)

	case RuleHostKeyword:
		return strings.Contains(domain, r.Value)

	case RuleIPCidr, RuleIPCidr6:
		if ip != nil && r.IPNet != nil {
			return r.IPNet.Contains(ip)
		}
		return false

	case RuleGeoIP:
		if ip != nil && m.geoIPLookup != nil {
			cc := m.geoIPLookup(ip)
			return cc != "" && strings.EqualFold(cc, r.Value)
		}
		return false

	case RuleFinal:
		return true

	default:
		return false
	}
}

// RulesCount returns the total number of rules configured.
func (m *Matcher) RulesCount() int {
	return len(m.rules)
}

// FinalPolicy returns the default policy when no rules match.
func (m *Matcher) FinalPolicy() string {
	return m.finalPolicy
}

func matchWildcard(pattern, text string) bool {
	p := strings.ToLower(pattern)
	t := strings.ToLower(text)
	if p == "*" || p == "" {
		return true
	}
	if strings.HasPrefix(p, "*") && strings.HasSuffix(p, "*") {
		sub := strings.Trim(p, "*")
		return strings.Contains(t, sub)
	}
	if strings.HasPrefix(p, "*") {
		return strings.HasSuffix(t, strings.TrimPrefix(p, "*"))
	}
	if strings.HasSuffix(p, "*") {
		return strings.HasPrefix(t, strings.TrimSuffix(p, "*"))
	}
	return p == t || strings.Contains(t, p)
}

