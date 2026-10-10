package config

import (
	"net"
	"strings"
)

// RuleType defines the type of a filtering rule.
type RuleType string

const (
	RuleHost        RuleType = "HOST"
	RuleHostSuffix  RuleType = "HOST-SUFFIX"
	RuleHostKeyword RuleType = "HOST-KEYWORD"
	RuleIPCidr      RuleType = "IP-CIDR"
	RuleIPCidr6     RuleType = "IP-CIDR6"
	RuleGeoIP       RuleType = "GEOIP"
	RuleUserAgent   RuleType = "USER-AGENT"
	RuleFinal       RuleType = "FINAL"
)

// Policy defines the target action for matching traffic.
const (
	PolicyDirect     = "DIRECT"
	PolicyReject     = "REJECT"
	PolicyRejectDrop = "REJECT-DROP"
)

// Rule represents a parsed routing or blocking ruleset rule.
type Rule struct {
	Type      RuleType
	Value     string
	Policy    string
	Order     int
	NoResolve bool
	IPNet     *net.IPNet
}

// RemoteFilter represents a remote ruleset subscription.
type RemoteFilter struct {
	URL         string
	Tag         string
	Interval    int
	OptParser   bool
	UpdateToken string
}

// Config represents a parsed Quantumult X configuration.
type Config struct {
	General       map[string]string
	DNSServers    []string
	Rules         []Rule
	RemoteFilters []RemoteFilter
	FinalPolicy   string
}

// NormalizeDomain lowercases domain and strips leading/trailing dots and spaces.
func NormalizeDomain(domain string) string {
	d := strings.TrimSpace(strings.ToLower(domain))
	d = strings.TrimPrefix(d, ".")
	return strings.TrimSuffix(d, ".")
}
