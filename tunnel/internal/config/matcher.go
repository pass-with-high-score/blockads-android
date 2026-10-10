package config

import (
	"net"
	"strings"
)

// Matcher evaluates traffic against parsed rules in declaration order.
type Matcher struct {
	rules            []Rule
	finalPolicy      string
	geoIPLookup      func(net.IP) string
	dnsExclusionList []string
	dnsServers       []string
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
		rules:            cfg.Rules,
		finalPolicy:      cfg.FinalPolicy,
		dnsExclusionList: cfg.DNSExclusionList,
		dnsServers:       cfg.DNSServers,
	}
}

// DNSExclusionList returns the configured DNS exclusion patterns.
func (m *Matcher) DNSExclusionList() []string {
	if m == nil {
		return nil
	}
	return m.dnsExclusionList
}

// DNSServers returns the configured DNS upstream servers.
func (m *Matcher) DNSServers() []string {
	if m == nil {
		return nil
	}
	return m.dnsServers
}

// Rules returns the current list of rules.
func (m *Matcher) Rules() []Rule {
	if m == nil {
		return nil
	}
	return m.rules
}

// NewMatcherFromRules constructs a Matcher directly from rules and options.
func NewMatcherFromRules(rules []Rule, finalPolicy string, dnsExclusionList, dnsServers []string) *Matcher {
	return &Matcher{
		rules:            rules,
		finalPolicy:      finalPolicy,
		dnsExclusionList: dnsExclusionList,
		dnsServers:       dnsServers,
	}
}

// AppendRules appends additional rules (such as from remote filter lists) to the matcher.
func (m *Matcher) AppendRules(rules []Rule) {
	if m != nil && len(rules) > 0 {
		m.rules = append(m.rules, rules...)
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

	// Check dns_exclusion_list first for domains
	if normDomain != "" {
		for _, excl := range m.dnsExclusionList {
			if matchDomainExclusion(excl, normDomain) {
				return PolicyDirect, "dns_exclusion_list:" + excl
			}
		}
	}

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

func matchDomainExclusion(pattern, domain string) bool {
	p := NormalizeDomain(pattern)
	d := NormalizeDomain(domain)
	if p == d {
		return true
	}
	if strings.HasPrefix(p, "*.") {
		suffix := p[2:]
		return d == suffix || strings.HasSuffix(d, "."+suffix)
	}
	if strings.HasPrefix(p, ".") {
		suffix := p[1:]
		return d == suffix || strings.HasSuffix(d, "."+suffix)
	}
	if strings.Contains(p, "*") {
		return matchWildcard(p, d)
	}
	return false
}

