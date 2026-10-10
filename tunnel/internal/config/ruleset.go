package config

import (
	"bufio"
	"fmt"
	"net"
	"strconv"
	"strings"
)

// ParseRuleset parses a profile ruleset configuration or snippet string.
func ParseRuleset(content string) (*Config, error) {
	cfg := &Config{
		General:          make(map[string]string),
		DNSServers:       make([]string, 0),
		Rules:            make([]Rule, 0),
		RemoteFilters:    make([]RemoteFilter, 0),
		FinalPolicy:      PolicyDirect,
		DNSExclusionList: make([]string, 0),
	}

	scanner := bufio.NewScanner(strings.NewReader(content))
	currentSection := ""
	order := 0

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "#") {
			continue
		}

		// Check for section header [section_name]
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			currentSection = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			continue
		}

		// Remove trailing comment if present
		line = stripComment(line)
		if line == "" {
			continue
		}

		switch currentSection {
		case "general":
			parseGeneralLine(cfg, line)
		case "dns":
			parseDNSLine(cfg, line)
		case "filter_local":
			if rule, ok := parseRuleLine(line, order); ok {
				cfg.Rules = append(cfg.Rules, rule)
				order++
				if rule.Type == RuleFinal {
					cfg.FinalPolicy = rule.Policy
				}
			}
		case "filter_remote":
			if filter, ok := parseRemoteFilterLine(line); ok {
				cfg.RemoteFilters = append(cfg.RemoteFilters, filter)
			}
		default:
			// If not inside a specific recognized section or inside a .snippet file without headers
			if rule, ok := parseRuleLine(line, order); ok {
				cfg.Rules = append(cfg.Rules, rule)
				order++
				if rule.Type == RuleFinal {
					cfg.FinalPolicy = rule.Policy
				}
			}
		}
	}

	return cfg, scanner.Err()
}

// ParseQuanX is a backward-compatible alias for ParseRuleset.
func ParseQuanX(content string) (*Config, error) {
	return ParseRuleset(content)
}

func parseGeneralLine(cfg *Config, line string) {
	parts := strings.SplitN(line, "=", 2)
	if len(parts) == 2 {
		key := strings.ToLower(strings.TrimSpace(parts[0]))
		val := strings.TrimSpace(parts[1])
		cfg.General[key] = val
		if key == "dns_exclusion_list" {
			items := strings.Split(val, ",")
			for _, item := range items {
				item = strings.TrimSpace(strings.ToLower(item))
				if item != "" {
					cfg.DNSExclusionList = append(cfg.DNSExclusionList, item)
				}
			}
		}
	}
}

// ParseRuleSnippet parses a plain text ruleset file (such as a downloaded .list or .snippet).
func ParseRuleSnippet(content string) []Rule {
	rules := make([]Rule, 0)
	scanner := bufio.NewScanner(strings.NewReader(content))
	order := 0
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "#") {
			continue
		}
		line = stripComment(line)
		if line == "" || strings.HasPrefix(line, "[") {
			continue
		}
		if rule, ok := parseRuleLine(line, order); ok {
			rules = append(rules, rule)
			order++
		}
	}
	return rules
}

func parseDNSLine(cfg *Config, line string) {
	parts := strings.SplitN(line, "=", 2)
	if len(parts) == 2 {
		key := strings.ToLower(strings.TrimSpace(parts[0]))
		val := strings.TrimSpace(parts[1])
		if key == "server" && val != "" {
			cfg.DNSServers = append(cfg.DNSServers, val)
		}
	} else if !strings.Contains(line, "=") {
		// Sometimes just raw DNS IP/URL
		cfg.DNSServers = append(cfg.DNSServers, strings.TrimSpace(line))
	}
}

func parseRuleLine(line string, order int) (Rule, bool) {
	parts := strings.Split(line, ",")
	if len(parts) < 2 {
		return Rule{}, false
	}

	ruleTypeStr := strings.ToUpper(strings.TrimSpace(parts[0]))
	val := strings.TrimSpace(parts[1])
	policy := PolicyDirect
	if len(parts) >= 3 {
		policy = strings.ToUpper(strings.TrimSpace(parts[2]))
	}

	var rType RuleType
	switch ruleTypeStr {
	case "HOST":
		rType = RuleHost
		val = NormalizeDomain(val)
	case "HOST-SUFFIX":
		rType = RuleHostSuffix
		val = NormalizeDomain(val)
	case "HOST-KEYWORD":
		rType = RuleHostKeyword
		val = strings.ToLower(val)
	case "IP-CIDR":
		rType = RuleIPCidr
	case "IP-CIDR6":
		rType = RuleIPCidr6
	case "GEOIP":
		rType = RuleGeoIP
		val = strings.ToUpper(val)
	case "USER-AGENT":
		rType = RuleUserAgent
	case "FINAL":
		return Rule{
			Type:   RuleFinal,
			Policy: strings.ToUpper(val), // For FINAL, the value field is the policy (FINAL, DIRECT)
			Value:  "FINAL",
			Order:  order,
		}, true
	default:
		return Rule{}, false
	}

	rule := Rule{
		Type:   rType,
		Value:  val,
		Policy: policy,
		Order:  order,
	}

	// Parse options (e.g., no-resolve)
	for i := 3; i < len(parts); i++ {
		opt := strings.ToLower(strings.TrimSpace(parts[i]))
		if opt == "no-resolve" {
			rule.NoResolve = true
		}
	}

	// Parse IPNet if CIDR
	if rType == RuleIPCidr || rType == RuleIPCidr6 {
		_, ipNet, err := net.ParseCIDR(val)
		if err == nil {
			rule.IPNet = ipNet
		} else {
			// Try parsing as single IP
			ip := net.ParseIP(val)
			if ip != nil {
				mask := net.CIDRMask(32, 32)
				if ip.To4() == nil {
					mask = net.CIDRMask(128, 128)
				}
				rule.IPNet = &net.IPNet{IP: ip, Mask: mask}
			}
		}
	}

	return rule, true
}

func parseRemoteFilterLine(line string) (RemoteFilter, bool) {
	parts := strings.Split(line, ",")
	if len(parts) < 1 {
		return RemoteFilter{}, false
	}

	rf := RemoteFilter{
		URL:      strings.TrimSpace(parts[0]),
		Interval: 24, // default 24h
	}

	for i := 1; i < len(parts); i++ {
		kv := strings.SplitN(strings.TrimSpace(parts[i]), "=", 2)
		if len(kv) != 2 {
			continue
		}
		k := strings.ToLower(strings.TrimSpace(kv[0]))
		v := strings.TrimSpace(kv[1])

		switch k {
		case "tag":
			rf.Tag = v
		case "update-interval":
			if interval, err := strconv.Atoi(v); err == nil && interval > 0 {
				rf.Interval = interval
			}
		case "opt-parser":
			rf.OptParser = strings.ToLower(v) == "true"
		case "update-token":
			rf.UpdateToken = v
		}
	}

	return rf, rf.URL != ""
}

func stripComment(line string) string {
	// Simple inline comment stripping
	for _, sep := range []string{"#", ";"} {
		idx := strings.Index(line, sep)
		if idx >= 0 {
			line = line[:idx]
		}
	}
	return strings.TrimSpace(line)
}

// String returns formatted summary of Config
func (c *Config) String() string {
	return fmt.Sprintf("Ruleset Config (DNS: %d, Rules: %d, Remotes: %d, Final: %s)",
		len(c.DNSServers), len(c.Rules), len(c.RemoteFilters), c.FinalPolicy)
}
