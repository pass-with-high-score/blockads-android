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
		case "rewrite_local", "rewrite_remote", "task_local", "http_backend", "mitm", "policy", "server_local", "server_remote":
			// Unsupported or non-filtering sections; ignore lines within them
		default:
			// If inside a raw .snippet or .list file without section headers
			if currentSection == "" {
				if rule, ok := parseRuleLine(line, order); ok {
					cfg.Rules = append(cfg.Rules, rule)
					order++
					if rule.Type == RuleFinal {
						cfg.FinalPolicy = rule.Policy
					}
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
	firstComma := strings.IndexByte(line, ',')
	if firstComma <= 0 {
		return Rule{}, false
	}

	ruleTypeStr := strings.ToUpper(strings.TrimSpace(line[:firstComma]))
	rest := strings.TrimSpace(line[firstComma+1:])

	// Logic rules use balanced parentheses and nested commas: AND,((...),(...)),POLICY
	switch ruleTypeStr {
	case "AND":
		return parseLogicRuleLine(RuleAnd, rest, order)
	case "OR":
		return parseLogicRuleLine(RuleOr, rest, order)
	case "NOT":
		return parseLogicRuleLine(RuleNot, rest, order)
	case "SUB-RULE":
		return parseLogicRuleLine(RuleSubRule, rest, order)
	}

	// Regex rules may contain commas inside the pattern (e.g. {1,3}); split from the right.
	var val, policy string
	var opts []string
	if isCommaPayloadType(ruleTypeStr) {
		lastComma := strings.LastIndexByte(rest, ',')
		if lastComma <= 0 {
			return Rule{}, false
		}
		val = strings.TrimSpace(rest[:lastComma])
		policy = strings.ToUpper(strings.TrimSpace(rest[lastComma+1:]))
	} else {
		parts := strings.Split(rest, ",")
		if len(parts) < 1 {
			return Rule{}, false
		}
		val = strings.TrimSpace(parts[0])
		policy = PolicyDirect
		if len(parts) >= 2 {
			policy = strings.ToUpper(strings.TrimSpace(parts[1]))
		}
		if len(parts) >= 3 {
			opts = parts[2:]
		}
	}

	var rType RuleType
	switch ruleTypeStr {
	case "HOST", "DOMAIN":
		rType = RuleHost
		val = NormalizeDomain(val)
	case "HOST-SUFFIX", "DOMAIN-SUFFIX":
		rType = RuleHostSuffix
		val = NormalizeDomain(val)
	case "HOST-KEYWORD", "DOMAIN-KEYWORD":
		rType = RuleHostKeyword
		val = strings.ToLower(val)
	case "HOST-WILDCARD", "DOMAIN-WILDCARD":
		rType = RuleDomainWildcard
		val = strings.ToLower(val)
	case "HOST-REGEX", "DOMAIN-REGEX":
		rType = RuleDomainRegex
	case "GEOSITE":
		rType = RuleGeoSite
		val = strings.ToLower(val)
	case "IP-CIDR":
		rType = RuleIPCidr
	case "IP-CIDR6":
		rType = RuleIPCidr6
	case "IP-SUFFIX":
		rType = RuleIPSuffix
	case "IP-ASN":
		rType = RuleIPASN
	case "GEOIP":
		rType = RuleGeoIP
		val = strings.ToUpper(val)
	case "SRC-IP-CIDR":
		rType = RuleSrcIPCidr
	case "SRC-IP-SUFFIX":
		rType = RuleSrcIPSuffix
	case "SRC-IP-ASN":
		rType = RuleSrcIPASN
	case "SRC-GEOIP":
		rType = RuleSrcGeoIP
		val = strings.ToUpper(val)
	case "DST-PORT":
		rType = RuleDstPort
	case "SRC-PORT":
		rType = RuleSrcPort
	case "IN-PORT":
		rType = RuleInPort
	case "NETWORK":
		rType = RuleNetwork
		val = strings.ToUpper(val)
	case "DSCP":
		rType = RuleDSCP
	case "PROCESS-NAME":
		rType = RuleProcessName
	case "PROCESS-NAME-REGEX":
		rType = RuleProcessNameRegex
	case "PROCESS-NAME-WILDCARD":
		rType = RuleProcessNameWildcard
	case "PROCESS-PATH":
		rType = RuleProcessPath
	case "PROCESS-PATH-REGEX":
		rType = RuleProcessPathRegex
	case "PROCESS-PATH-WILDCARD":
		rType = RuleProcessPathWildcard
	case "UID":
		rType = RuleUID
	case "IN-TYPE":
		rType = RuleInType
		val = strings.ToUpper(val)
	case "IN-USER":
		rType = RuleInUser
	case "IN-NAME":
		rType = RuleInName
	case "USER-AGENT":
		rType = RuleUserAgent
	case "RULE-SET":
		rType = RuleRuleSet
	case "FINAL", "MATCH":
		return Rule{
			Type:   RuleFinal,
			Policy: strings.ToUpper(val),
			Value:  "FINAL",
			Order:  order,
		}, true
	default:
		return Rule{}, false
	}

	rule := Rule{
		Type:    rType,
		Value:   val,
		Policy:  policy,
		Order:   order,
		UIDVal:  -1,
		DSCPVal: -1,
	}

	for _, rawOpt := range opts {
		if strings.EqualFold(strings.TrimSpace(rawOpt), "no-resolve") {
			rule.NoResolve = true
		}
	}

	switch rType {
	case RuleIPCidr, RuleIPCidr6, RuleSrcIPCidr:
		if _, ipNet, err := net.ParseCIDR(val); err == nil {
			rule.IPNet = ipNet
		} else if ip := net.ParseIP(val); ip != nil {
			mask := net.CIDRMask(32, 32)
			if ip.To4() == nil {
				mask = net.CIDRMask(128, 128)
			}
			rule.IPNet = &net.IPNet{IP: ip, Mask: mask}
		}
	case RuleIPSuffix, RuleSrcIPSuffix:
		rule.IPSuffix = parseIPSuffix(val)
	case RuleDstPort, RuleSrcPort, RuleInPort:
		rule.PortRanges = parsePortRanges(val)
	case RuleDomainWildcard, RuleProcessNameWildcard, RuleProcessPathWildcard:
		rule.Regex = compileGlobRegex(val)
	case RuleDomainRegex, RuleProcessNameRegex, RuleProcessPathRegex:
		rule.Regex = compileCaseInsensitiveRegex(val)
	case RuleUID:
		if uid, err := strconv.Atoi(val); err == nil && uid >= 0 {
			rule.UIDVal = uid
		}
	case RuleDSCP:
		if dscp, err := strconv.Atoi(val); err == nil && dscp >= 0 {
			rule.DSCPVal = dscp
		}
	}

	return rule, true
}

func isCommaPayloadType(ruleType string) bool {
	switch ruleType {
	case "HOST-REGEX", "DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX":
		return true
	default:
		return false
	}
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
