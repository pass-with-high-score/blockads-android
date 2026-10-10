package tunnel

import (
	"encoding/json"
	"net"
	"strings"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/config"
)

// SetRulesetConfig parses and activates ruleset configuration on the engine.
// Returns the number of parsed rules, or an error if parsing failed.
func (e *Engine) SetRulesetConfig(content string) (int, error) {
	if content == "" {
		e.rulesetMatcher.Store(nil)
		e.activeRulesetCfg.Store(nil)
		return 0, nil
	}

	cfg, err := config.ParseRuleset(content)
	if err != nil {
		return 0, err
	}

	e.activeRulesetCfg.Store(cfg)

	matcher := config.NewMatcher(cfg)
	if db := e.geoIPDB.Load(); db != nil {
		matcher.SetGeoIPLookup(db.Lookup)
	}
	e.rulesetMatcher.Store(matcher)

	if len(cfg.DNSExclusionList) > 0 {
		logf("Ruleset: loaded %d dns_exclusion_list entries: %s", len(cfg.DNSExclusionList), strings.Join(cfg.DNSExclusionList, ", "))
	}
	if len(cfg.DNSServers) > 0 {
		logf("Ruleset: loaded %d upstream DNS servers: %s", len(cfg.DNSServers), strings.Join(cfg.DNSServers, ", "))
	}
	if len(cfg.RemoteFilters) > 0 {
		logf("Ruleset: loaded %d remote filter subscriptions", len(cfg.RemoteFilters))
	}

	return matcher.RulesCount(), nil
}

// checkMsgAnswerRuleset evaluates resolved A/AAAA records in a DNS response against the ruleset matcher.
// Returns matchedRule if any resolved IP triggers a REJECT policy, or empty string otherwise.
func checkMsgAnswerRuleset(msg *dns.Msg, matcher *config.Matcher) string {
	if msg == nil || matcher == nil {
		return ""
	}
	for _, rr := range msg.Answer {
		var ip net.IP
		switch a := rr.(type) {
		case *dns.A:
			ip = a.A
		case *dns.AAAA:
			ip = a.AAAA
		}
		if ip != nil {
			policy, matchedRule := matcher.MatchNetIP("", ip)
			if strings.HasPrefix(strings.ToUpper(policy), "REJECT") {
				logf("Ruleset DNS BLOCKED resolved IP %s (matched: %s)", ip.String(), matchedRule)
				return matchedRule
			}
		}
	}
	return ""
}

// checkDNSResponseRuleset unpacks a raw DNS response and evaluates answer IPs against the ruleset matcher.
func checkDNSResponseRuleset(resp []byte, matcher *config.Matcher) (bool, string) {
	if matcher == nil || len(resp) == 0 {
		return false, ""
	}
	var msg dns.Msg
	if err := msg.Unpack(resp); err != nil {
		return false, ""
	}
	matched := checkMsgAnswerRuleset(&msg, matcher)
	if matched != "" {
		return true, matched
	}
	return false, ""
}

// ClearRulesetConfig clears the active ruleset configuration.
func (e *Engine) ClearRulesetConfig() {
	e.rulesetMatcher.Store(nil)
	e.activeRulesetCfg.Store(nil)
}

// GetRulesetDNSServersCSV returns comma-separated upstream DNS servers from active profile ruleset.
func (e *Engine) GetRulesetDNSServersCSV() string {
	matcher := e.rulesetMatcher.Load()
	if matcher == nil || len(matcher.DNSServers()) == 0 {
		return ""
	}
	return strings.Join(matcher.DNSServers(), ",")
}

// GetRulesetDNSExclusionListCSV returns comma-separated DNS exclusion patterns from active profile ruleset.
func (e *Engine) GetRulesetDNSExclusionListCSV() string {
	matcher := e.rulesetMatcher.Load()
	if matcher == nil || len(matcher.DNSExclusionList()) == 0 {
		return ""
	}
	return strings.Join(matcher.DNSExclusionList(), ",")
}

type RemoteFilterSubscription struct {
	URL      string `json:"url"`
	Tag      string `json:"tag"`
	Interval int    `json:"interval"`
}

// GetRulesetRemoteFiltersJSON returns JSON array of remote filter subscriptions from active profile.
func (e *Engine) GetRulesetRemoteFiltersJSON() string {
	cfg := e.activeRulesetCfg.Load()
	if cfg == nil || len(cfg.RemoteFilters) == 0 {
		return "[]"
	}
	items := make([]RemoteFilterSubscription, len(cfg.RemoteFilters))
	for i, rf := range cfg.RemoteFilters {
		items[i] = RemoteFilterSubscription{
			URL:      rf.URL,
			Tag:      rf.Tag,
			Interval: rf.Interval,
		}
	}
	b, _ := json.Marshal(items)
	return string(b)
}

// AppendRemoteFilterRules parses plain text rule lines and appends them to the active matcher.
// Safe for concurrent use: builds and atomically stores a new matcher.
func (e *Engine) AppendRemoteFilterRules(tag, content string) (int, error) {
	if content == "" {
		return 0, nil
	}
	parsed := config.ParseRuleSnippet(content)
	if len(parsed) == 0 {
		return 0, nil
	}

	e.mu.Lock()
	defer e.mu.Unlock()

	matcher := e.rulesetMatcher.Load()
	if matcher == nil {
		return 0, nil
	}

	currentRules := matcher.Rules()
	combined := make([]config.Rule, len(currentRules)+len(parsed))
	copy(combined, currentRules)
	copy(combined[len(currentRules):], parsed)

	newMatcher := config.NewMatcherFromRules(combined, matcher.FinalPolicy(), matcher.DNSExclusionList(), matcher.DNSServers())
	if db := e.geoIPDB.Load(); db != nil {
		newMatcher.SetGeoIPLookup(db.Lookup)
	}
	e.rulesetMatcher.Store(newMatcher)
	logf("Ruleset: appended %d rules from remote filter [%s] (total rules: %d)", len(parsed), tag, newMatcher.RulesCount())
	return len(parsed), nil
}

// RulesetRuleCount returns the number of active ruleset rules.
func (e *Engine) RulesetRuleCount() int {
	matcher := e.rulesetMatcher.Load()
	if matcher == nil {
		return 0
	}
	return matcher.RulesCount()
}

// MatchRuleset evaluates a domain and IP against active ruleset rules.
// Returns JSON string {"policy": string, "matched": string}.
func (e *Engine) MatchRuleset(domain, ip string) string {
	matcher := e.rulesetMatcher.Load()
	if matcher == nil {
		return `{"policy":"DIRECT","matched":"FINAL"}`
	}
	policy, matched := matcher.Match(domain, ip)
	res, _ := json.Marshal(map[string]string{
		"policy":  policy,
		"matched": matched,
	})
	return string(res)
}

// ParseRulesetSummary parses ruleset content and returns a brief summary string.
func ParseRulesetSummary(content string) string {
	cfg, err := config.ParseRuleset(content)
	if err != nil {
		return "Error: " + err.Error()
	}
	return cfg.String()
}

// Backward compatibility methods
func (e *Engine) SetQuanXConfig(content string) (int, error) {
	return e.SetRulesetConfig(content)
}

func (e *Engine) ClearQuanXConfig() {
	e.ClearRulesetConfig()
}

func (e *Engine) QuanXRuleCount() int {
	return e.RulesetRuleCount()
}

func (e *Engine) MatchQuanX(domain, ip string) string {
	return e.MatchRuleset(domain, ip)
}

func ParseQuanXSummary(content string) string {
	return ParseRulesetSummary(content)
}
