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

// MatchNetIP evaluates domain and destination net.IP directly without string allocation.
func (m *Matcher) MatchNetIP(domain string, ip net.IP) (string, string) {
	return MatchRuleContext(m, MatchContext{
		Domain: domain,
		DstIP:  ip,
		UID:    -1,
		DSCP:   -1,
	})
}

// MatchWithUA evaluates domain, IP, and user-agent against rules.
func (m *Matcher) MatchWithUA(domain string, ipStr string, userAgent string) (string, string) {
	var ip net.IP
	if ipStr != "" {
		ip = net.ParseIP(strings.TrimSpace(ipStr))
	}
	return MatchRuleContext(m, MatchContext{
		Domain:    domain,
		DstIP:     ip,
		UserAgent: userAgent,
		UID:       -1,
		DSCP:      -1,
	})
}

// MatchRuleContext evaluates full L3/L4/L7 metadata against all rules in order.
// Kept as a package function so gomobile-exported Matcher aliases stay unchanged.
func MatchRuleContext(m *Matcher, ctx MatchContext) (string, string) {
	if m == nil {
		return PolicyDirect, "FINAL"
	}
	ctx.Domain = NormalizeDomain(ctx.Domain)
	ctx.Network = strings.ToUpper(strings.TrimSpace(ctx.Network))
	ctx.InType = strings.ToUpper(strings.TrimSpace(ctx.InType))
	ctx.UserAgent = strings.ToLower(strings.TrimSpace(ctx.UserAgent))

	if ctx.Domain != "" {
		for _, excl := range m.dnsExclusionList {
			if matchDomainExclusion(excl, ctx.Domain) {
				return PolicyDirect, "dns_exclusion_list:" + excl
			}
		}
	}

	for i := range m.rules {
		r := &m.rules[i]
		if r.Type == RuleFinal {
			continue
		}
		if m.matchesRule(r, &ctx) {
			if r.Policy == PolicyPass || r.Policy == PolicyPassRule {
				return r.Policy, r.Value
			}
			return r.Policy, r.Value
		}
	}
	return m.finalPolicy, "FINAL"
}

func (m *Matcher) matchesRule(r *Rule, ctx *MatchContext) bool {
	switch r.Type {
	case RuleHost:
		return ctx.Domain != "" && ctx.Domain == r.Value

	case RuleHostSuffix:
		return ctx.Domain != "" && (ctx.Domain == r.Value || strings.HasSuffix(ctx.Domain, "."+r.Value))

	case RuleHostKeyword:
		return ctx.Domain != "" && strings.Contains(ctx.Domain, r.Value)

	case RuleDomainWildcard, RuleDomainRegex:
		return ctx.Domain != "" && r.Regex != nil && r.Regex.MatchString(ctx.Domain)

	case RuleGeoSite:
		return matchGeoSite(r.Value, ctx.Domain)

	case RuleIPCidr, RuleIPCidr6:
		return ctx.DstIP != nil && r.IPNet != nil && r.IPNet.Contains(ctx.DstIP)

	case RuleIPSuffix:
		return matchIPSuffix(r.IPSuffix, ctx.DstIP)

	case RuleIPASN:
		return ctx.DstASN != "" && strings.EqualFold(ctx.DstASN, r.Value)

	case RuleGeoIP:
		return m.matchGeoIP(r.Value, ctx.DstIP)

	case RuleSrcIPCidr:
		return ctx.SrcIP != nil && r.IPNet != nil && r.IPNet.Contains(ctx.SrcIP)

	case RuleSrcIPSuffix:
		return matchIPSuffix(r.IPSuffix, ctx.SrcIP)

	case RuleSrcIPASN:
		return ctx.SrcASN != "" && strings.EqualFold(ctx.SrcASN, r.Value)

	case RuleSrcGeoIP:
		return m.matchGeoIP(r.Value, ctx.SrcIP)

	case RuleDstPort:
		return matchPortRanges(r.PortRanges, ctx.DstPort)

	case RuleSrcPort:
		return matchPortRanges(r.PortRanges, ctx.SrcPort)

	case RuleInPort:
		return matchPortRanges(r.PortRanges, ctx.InPort)

	case RuleNetwork:
		return ctx.Network != "" && ctx.Network == r.Value

	case RuleDSCP:
		return ctx.DSCP >= 0 && r.DSCPVal >= 0 && ctx.DSCP == r.DSCPVal

	case RuleProcessName:
		return ctx.ProcessName != "" && strings.EqualFold(ctx.ProcessName, r.Value)

	case RuleProcessNameRegex, RuleProcessNameWildcard:
		return ctx.ProcessName != "" && r.Regex != nil && r.Regex.MatchString(ctx.ProcessName)

	case RuleProcessPath:
		return ctx.ProcessPath != "" && strings.EqualFold(ctx.ProcessPath, r.Value)

	case RuleProcessPathRegex, RuleProcessPathWildcard:
		return ctx.ProcessPath != "" && r.Regex != nil && r.Regex.MatchString(ctx.ProcessPath)

	case RuleUID:
		return ctx.UID >= 0 && r.UIDVal >= 0 && ctx.UID == r.UIDVal

	case RuleInType:
		return ctx.InType != "" && ctx.InType == r.Value

	case RuleInUser:
		return ctx.InUser != "" && strings.EqualFold(ctx.InUser, r.Value)

	case RuleInName:
		return ctx.InName != "" && strings.EqualFold(ctx.InName, r.Value)

	case RuleUserAgent:
		return ctx.UserAgent != "" && matchWildcard(r.Value, ctx.UserAgent)

	case RuleAnd:
		if len(r.SubRules) == 0 {
			return false
		}
		for i := range r.SubRules {
			if !m.matchesRule(&r.SubRules[i], ctx) {
				return false
			}
		}
		return true

	case RuleOr:
		for i := range r.SubRules {
			if m.matchesRule(&r.SubRules[i], ctx) {
				return true
			}
		}
		return false

	case RuleNot:
		if len(r.SubRules) == 0 {
			return false
		}
		return !m.matchesRule(&r.SubRules[0], ctx)

	case RuleFinal:
		return true

	default:
		return false
	}
}

func (m *Matcher) matchGeoIP(countryCode string, ip net.IP) bool {
	if ip == nil {
		return false
	}
	if strings.EqualFold(countryCode, "LAN") || strings.EqualFold(countryCode, "PRIVATE") {
		return isPrivateOrLANIP(ip)
	}
	if m.geoIPLookup != nil {
		cc := m.geoIPLookup(ip)
		return cc != "" && strings.EqualFold(cc, countryCode)
	}
	return false
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
