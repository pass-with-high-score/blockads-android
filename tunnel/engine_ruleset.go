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
		return 0, nil
	}

	cfg, err := config.ParseRuleset(content)
	if err != nil {
		return 0, err
	}

	matcher := config.NewMatcher(cfg)
	if db := e.geoIPDB.Load(); db != nil {
		matcher.SetGeoIPLookup(db.Lookup)
	}
	e.rulesetMatcher.Store(matcher)
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
