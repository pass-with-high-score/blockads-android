package config

import (
	"net"
	"regexp"
	"strconv"
	"strings"
)

func parsePortRanges(raw string) []portRange {
	var ranges []portRange
	// FlClash / Mihomo supports slash-separated ports and dash ranges, e.g. "3478/19302" or "19302-19309".
	for _, token := range strings.Split(raw, "/") {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if idx := strings.IndexByte(token, '-'); idx > 0 {
			lo, err1 := strconv.Atoi(strings.TrimSpace(token[:idx]))
			hi, err2 := strconv.Atoi(strings.TrimSpace(token[idx+1:]))
			if err1 == nil && err2 == nil && lo >= 0 && hi >= lo && hi <= 65535 {
				ranges = append(ranges, portRange{start: lo, end: hi})
			}
		} else {
			p, err := strconv.Atoi(token)
			if err == nil && p >= 0 && p <= 65535 {
				ranges = append(ranges, portRange{start: p, end: p})
			}
		}
	}
	return ranges
}

func matchPortRanges(ranges []portRange, port int) bool {
	if port <= 0 {
		return false
	}
	for _, r := range ranges {
		if port >= r.start && port <= r.end {
			return true
		}
	}
	return false
}

func parseIPSuffix(raw string) *ipSuffixSpec {
	parts := strings.SplitN(strings.TrimSpace(raw), "/", 2)
	if len(parts) != 2 {
		return nil
	}
	ip := net.ParseIP(strings.TrimSpace(parts[0]))
	bits, err := strconv.Atoi(strings.TrimSpace(parts[1]))
	if ip == nil || err != nil || bits <= 0 {
		return nil
	}
	if ip4 := ip.To4(); ip4 != nil {
		if bits > 32 {
			return nil
		}
		return &ipSuffixSpec{ip: ip4, bits: bits}
	}
	ip16 := ip.To16()
	if ip16 == nil || bits > 128 {
		return nil
	}
	return &ipSuffixSpec{ip: ip16, bits: bits}
}

func matchIPSuffix(spec *ipSuffixSpec, candidate net.IP) bool {
	if spec == nil || candidate == nil {
		return false
	}
	var target, ip []byte
	if len(spec.ip) == net.IPv4len {
		c4 := candidate.To4()
		if c4 == nil {
			return false
		}
		target = spec.ip
		ip = c4
	} else {
		if candidate.To4() != nil {
			return false
		}
		c16 := candidate.To16()
		if c16 == nil {
			return false
		}
		target = spec.ip
		ip = c16
	}

	remaining := spec.bits
	for i := len(target) - 1; i >= 0 && remaining > 0; i-- {
		if remaining >= 8 {
			if target[i] != ip[i] {
				return false
			}
			remaining -= 8
		} else {
			mask := byte((1 << uint(remaining)) - 1)
			if (target[i] & mask) != (ip[i] & mask) {
				return false
			}
			remaining = 0
		}
	}
	return true
}

func compileGlobRegex(glob string) *regexp.Regexp {
	if glob == "" {
		return nil
	}
	var sb strings.Builder
	sb.WriteString("(?i)^")
	for i := 0; i < len(glob); i++ {
		switch ch := glob[i]; ch {
		case '*':
			sb.WriteString(".*")
		case '?':
			sb.WriteByte('.')
		case '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\':
			sb.WriteByte('\\')
			sb.WriteByte(ch)
		default:
			sb.WriteByte(ch)
		}
	}
	sb.WriteByte('$')
	re, err := regexp.Compile(sb.String())
	if err != nil {
		return nil
	}
	return re
}

func compileCaseInsensitiveRegex(pattern string) *regexp.Regexp {
	if pattern == "" {
		return nil
	}
	if !strings.HasPrefix(pattern, "(?") {
		pattern = "(?i)" + pattern
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil
	}
	return re
}

// isPrivateOrLANIP reports whether ip belongs to LAN / private / loopback / link-local ranges.
func isPrivateOrLANIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()
}

// Built-in lightweight GEOSITE categories so common FlClash presets work without external .dat files.
var builtinGeoSiteSuffixes = map[string][]string{
	"private": {
		"local", "localhost", "lan", "home.arpa", "internal", "intranet", "corp", "localdomain",
	},
	"apple": {
		"apple.com", "icloud.com", "mzstatic.com", "cdn-apple.com", "push.apple.com", "aaplimg.com",
	},
	"microsoft": {
		"microsoft.com", "windows.com", "windowsupdate.com", "live.com", "msftncsi.com", "office.com", "azure.com",
	},
	"google": {
		"google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "gvt1.com", "gvt2.com",
	},
	"category-ads": {
		"doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "adsrvr.org",
	},
	"category-public-tracker": {
		"opentrackr.org", "openbittorrent.com", "publicbt.com", "tracker.torrent.eu.org", "explodie.org",
	},
	"category-pt": {
		"hdsky.me", "m-team.cc", "chdbits.co", "ourbits.club",
	},
}

func matchGeoSite(category, domain string) bool {
	if domain == "" {
		return false
	}
	suffixes, ok := builtinGeoSiteSuffixes[strings.ToLower(strings.TrimSpace(category))]
	if !ok {
		return false
	}
	for _, s := range suffixes {
		if domain == s || strings.HasSuffix(domain, "."+s) {
			return true
		}
	}
	return false
}

// parseLogicRuleLine parses AND/OR/NOT rules such as:
// AND,((NETWORK,UDP),(DST-PORT,443)),REJECT-DROP
func parseLogicRuleLine(ruleType RuleType, rest string, order int) (Rule, bool) {
	rest = strings.TrimSpace(rest)
	if !strings.HasPrefix(rest, "(") {
		return Rule{}, false
	}
	depth := 0
	endIdx := -1
	for i := 0; i < len(rest); i++ {
		switch rest[i] {
		case '(':
			depth++
		case ')':
			depth--
			if depth == 0 {
				endIdx = i
			}
		}
		if endIdx != -1 {
			break
		}
	}
	if endIdx == -1 {
		return Rule{}, false
	}

	payload := rest[1:endIdx]
	after := strings.TrimSpace(rest[endIdx+1:])
	policy := PolicyDirect
	if strings.HasPrefix(after, ",") {
		pParts := strings.Split(after[1:], ",")
		if len(pParts) > 0 && strings.TrimSpace(pParts[0]) != "" {
			policy = strings.ToUpper(strings.TrimSpace(pParts[0]))
		}
	}

	subExprs := splitTopLevelParens(payload)
	if len(subExprs) == 0 {
		return Rule{}, false
	}
	subRules := make([]Rule, 0, len(subExprs))
	for _, expr := range subExprs {
		// Each sub-expression is e.g. "(NETWORK,UDP)" -> unwrap outer parens.
		inner := strings.TrimSpace(expr)
		if strings.HasPrefix(inner, "(") && strings.HasSuffix(inner, ")") {
			inner = strings.TrimSpace(inner[1 : len(inner)-1])
		}
		// Sub-conditions don't carry a policy; append dummy policy for parseRuleLine.
		sub, ok := parseRuleLine(inner+",DIRECT", order)
		if !ok {
			return Rule{}, false
		}
		subRules = append(subRules, sub)
	}

	return Rule{
		Type:     ruleType,
		Value:    "(" + payload + ")",
		Policy:   policy,
		Order:    order,
		SubRules: subRules,
	}, true
}

func splitTopLevelParens(s string) []string {
	var out []string
	depth := 0
	start := 0
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '(':
			depth++
		case ')':
			depth--
		case ',':
			if depth == 0 {
				part := strings.TrimSpace(s[start:i])
				if part != "" {
					out = append(out, part)
				}
				start = i + 1
			}
		}
	}
	if tail := strings.TrimSpace(s[start:]); tail != "" {
		out = append(out, tail)
	}
	return out
}
