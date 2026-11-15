package trie

import (
	"net"
	"strings"
)

type domainModuleRules struct {
	CSS        []string `json:"css,omitempty"`
	BlockPaths []string `json:"block_paths,omitempty"`
}

type moduleRulesPackage struct {
	Name             string                        `json:"name"`
	RuleCount        int                           `json:"rule_count"`
	Domains          map[string]*domainModuleRules `json:"domains,omitempty"`
	GlobalCSS        []string                      `json:"global_css,omitempty"`
	GlobalBlockPaths []string                      `json:"global_block_paths,omitempty"`
}

func parseCosmeticRule(line string) ([]string, string, bool) {
	if !strings.Contains(line, "##") {
		return nil, "", false
	}
	if strings.Contains(line, "#@#") || strings.Contains(line, "##+js") || strings.Contains(line, "##^") {
		return nil, "", false
	}
	idx := strings.Index(line, "##")
	domainsPart := strings.TrimSpace(line[:idx])
	selector := strings.TrimSpace(line[idx+2:])
	if selector == "" {
		return nil, "", false
	}
	if domainsPart == "" {
		return nil, selector, true
	}
	var domains []string
	for _, d := range strings.Split(domainsPart, ",") {
		d = strings.TrimSpace(strings.ToLower(d))
		if d != "" {
			domains = append(domains, d)
		}
	}
	if len(domains) == 0 {
		return nil, selector, true
	}
	return domains, selector, true
}

func isSafePathRuleOptions(options string) bool {
	for _, opt := range strings.Split(options, ",") {
		opt = strings.ToLower(strings.TrimSpace(opt))
		switch opt {
		case "", "important", "all", "script", "xmlhttprequest", "xhr",
			"subdocument", "image", "media", "ping", "beacon", "fetch", "other":
			continue
		default:
			return false
		}
	}
	return true
}

func parseNetworkPathRule(line string) (string, string, bool) {
	line = strings.TrimSpace(line)
	if strings.HasPrefix(line, "@@") || strings.HasPrefix(line, "!") || strings.HasPrefix(line, "#") {
		return "", "", false
	}
	if strings.HasPrefix(line, "||") {
		rest := strings.TrimPrefix(line, "||")
		if dollarIdx := strings.IndexByte(rest, '$'); dollarIdx != -1 {
			if !isSafePathRuleOptions(rest[dollarIdx+1:]) {
				return "", "", false
			}
			rest = rest[:dollarIdx]
		}
		sepIdx := strings.IndexAny(rest, "/^")
		if sepIdx == -1 {
			return "", "", false
		}
		domain := strings.TrimSpace(strings.ToLower(rest[:sepIdx]))
		pathPart := strings.TrimPrefix(strings.TrimPrefix(rest[sepIdx:], "^"), "*")
		if !strings.HasPrefix(pathPart, "/") {
			if slashIdx := strings.IndexByte(pathPart, '/'); slashIdx != -1 {
				pathPart = pathPart[slashIdx:]
			} else {
				return "", "", false
			}
		}
		pathPattern := strings.TrimRight(pathPart, "*^")
		if len(strings.Trim(pathPattern, "/?")) >= 4 && domain != "" &&
			!strings.ContainsAny(domain, "/*?#") &&
			!strings.ContainsAny(pathPattern, "*|^\\[](){}") {
			return domain, pathPattern, true
		}
		return "", "", false
	}
	if strings.HasPrefix(line, "/") && !strings.HasPrefix(line, "//") && !strings.HasPrefix(line, "/*") {
		clean := line
		if dollarIdx := strings.IndexByte(clean, '$'); dollarIdx != -1 {
			if !isSafePathRuleOptions(clean[dollarIdx+1:]) {
				return "", "", false
			}
			clean = clean[:dollarIdx]
		}
		if strings.HasSuffix(clean, "/") {
			return "", "", false
		}
		clean = strings.TrimRight(clean, "*^")
		if len(strings.Trim(clean, "/?")) >= 5 &&
			strings.HasPrefix(clean, "/") &&
			!strings.ContainsAny(clean, "*?|^\\[](){}+") {
			return "*", clean, true
		}
	}
	return "", "", false
}

func parseDomainLine(line string) string {
	line = strings.TrimSpace(line)

	if line == "" || line[0] == '#' || line[0] == '!' {
		return ""
	}

	if strings.HasPrefix(line, "@@") {
		return ""
	}

	// Cosmetic and scriptlet rules are not domains
	if strings.Contains(line, "##") || strings.Contains(line, "#@#") || strings.Contains(line, "#?#") ||
		strings.Contains(line, "#$#") || strings.Contains(line, "#%#") {
		return ""
	}

	// Support Wildcard domain format: *.example.com (HaGeZi wildcard, OISD domainswild)
	if strings.HasPrefix(line, "*.") {
		line = strings.TrimPrefix(line, "*.")
	}

	// dnsmasq format: server=/domain.com/ or address=/domain.com/0.0.0.0
	if strings.HasPrefix(line, "server=/") || strings.HasPrefix(line, "address=/") {
		clean := strings.TrimPrefix(strings.TrimPrefix(line, "server=/"), "address=/")
		if slashIdx := strings.IndexByte(clean, '/'); slashIdx != -1 {
			line = clean[:slashIdx]
		}
	}

	if strings.ContainsAny(line, "$/\\*") && !strings.HasPrefix(line, "||") {
		return ""
	}

	var domain string
	isABP := strings.HasPrefix(line, "||")

	switch {
	case isABP:
		domain = strings.TrimPrefix(line, "||")

		// Handle wildcard prefixes: ||*.example.com^ or ||*.tld^
		domain = strings.TrimPrefix(domain, "*.")
		domain = strings.TrimPrefix(domain, "*")

		// If the rule contains wildcards (*, ?) or paths (/), it's a URL-level rule.
		if strings.ContainsAny(domain, "/*?") {
			return ""
		}

		// Handle modifiers after $
		if dollarIdx := strings.IndexByte(domain, '$'); dollarIdx != -1 {
			domainName := strings.TrimSuffix(domain[:dollarIdx], "^")
			isSingleLabelTLD := domainName != "" && !strings.Contains(domainName, ".")
			options := domain[dollarIdx+1:]
			for _, opt := range strings.Split(options, ",") {
				opt = strings.ToLower(strings.TrimSpace(opt))
				if opt == "" || opt == "important" || opt == "all" ||
					opt == "dns" || strings.HasPrefix(opt, "dnstype") || opt == "cname" ||
					opt == "network" || strings.HasPrefix(opt, "ctag") ||
					(isSingleLabelTLD && strings.HasPrefix(opt, "denyallow=")) {
					continue
				}
				return ""
			}
			domain = domain[:dollarIdx]
		}

		if idx := strings.IndexByte(domain, '^'); idx != -1 {
			domain = domain[:idx]
		}

	case strings.HasPrefix(line, "0.0.0.0 "),
		strings.HasPrefix(line, "127.0.0.1 "):
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			domain = fields[1]
		}
		if idx := strings.IndexByte(domain, '#'); idx != -1 {
			domain = domain[:idx]
		}

	default:
		if !strings.ContainsAny(line, " \t") && strings.Contains(line, ".") {
			domain = line
		}
	}

	domain = strings.TrimSpace(strings.ToLower(domain))

	if domain == "" || domain == "localhost" || domain == "localhost.localdomain" ||
		domain == "local" || domain == "broadcasthost" {
		return ""
	}

	if !strings.Contains(domain, ".") && !isABP {
		return ""
	}

	// Reject IP addresses
	if domain[0] >= '0' && domain[0] <= '9' {
		allDigitsOrDots := true
		for _, r := range domain {
			if (r < '0' || r > '9') && r != '.' {
				allDigitsOrDots = false
				break
			}
		}
		if allDigitsOrDots && net.ParseIP(domain) != nil {
			return ""
		}
	}

	labels := strings.Split(domain, ".")
	if len(labels) < 2 && !isABP {
		return ""
	}
	for _, label := range labels {
		if label == "" {
			return ""
		}
		for i, r := range label {
			if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' && r != '_' {
				return ""
			}
			if (r == '-' || r == '_') && (i == 0 || i == len(label)-1) {
				return ""
			}
		}
	}

	return domain
}
