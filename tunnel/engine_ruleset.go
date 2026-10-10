package tunnel

import (
	"encoding/json"

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
	e.rulesetMatcher.Store(matcher)
	return matcher.RulesCount(), nil
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
