package config

import (
	"net"
	"regexp"
	"strings"
)

// RuleType defines the type of a filtering or routing rule.
type RuleType string

const (
	// Domain rules (Quantumult X + Clash/FlClash aliases normalized to canonical types)
	RuleHost           RuleType = "HOST"
	RuleHostSuffix     RuleType = "HOST-SUFFIX"
	RuleHostKeyword    RuleType = "HOST-KEYWORD"
	RuleDomainWildcard RuleType = "DOMAIN-WILDCARD"
	RuleDomainRegex    RuleType = "DOMAIN-REGEX"
	RuleGeoSite        RuleType = "GEOSITE"

	// Destination IP & Geo rules
	RuleIPCidr   RuleType = "IP-CIDR"
	RuleIPCidr6  RuleType = "IP-CIDR6"
	RuleIPSuffix RuleType = "IP-SUFFIX"
	RuleIPASN    RuleType = "IP-ASN"
	RuleGeoIP    RuleType = "GEOIP"

	// Source IP & Geo rules
	RuleSrcIPCidr   RuleType = "SRC-IP-CIDR"
	RuleSrcIPSuffix RuleType = "SRC-IP-SUFFIX"
	RuleSrcIPASN    RuleType = "SRC-IP-ASN"
	RuleSrcGeoIP    RuleType = "SRC-GEOIP"

	// Port & Protocol rules
	RuleDstPort RuleType = "DST-PORT"
	RuleSrcPort RuleType = "SRC-PORT"
	RuleInPort  RuleType = "IN-PORT"
	RuleNetwork RuleType = "NETWORK"
	RuleDSCP    RuleType = "DSCP"

	// Process / App rules
	RuleProcessName         RuleType = "PROCESS-NAME"
	RuleProcessNameRegex    RuleType = "PROCESS-NAME-REGEX"
	RuleProcessNameWildcard RuleType = "PROCESS-NAME-WILDCARD"
	RuleProcessPath         RuleType = "PROCESS-PATH"
	RuleProcessPathRegex    RuleType = "PROCESS-PATH-REGEX"
	RuleProcessPathWildcard RuleType = "PROCESS-PATH-WILDCARD"
	RuleUID                 RuleType = "UID"

	// Inbound, Header & Logic rules
	RuleInType    RuleType = "IN-TYPE"
	RuleInUser    RuleType = "IN-USER"
	RuleInName    RuleType = "IN-NAME"
	RuleUserAgent RuleType = "USER-AGENT"
	RuleRuleSet   RuleType = "RULE-SET"
	RuleAnd       RuleType = "AND"
	RuleOr        RuleType = "OR"
	RuleNot       RuleType = "NOT"
	RuleSubRule   RuleType = "SUB-RULE"
	RuleFinal     RuleType = "FINAL"
)

// Policy defines the target action for matching traffic.
const (
	PolicyDirect     = "DIRECT"
	PolicyReject     = "REJECT"
	PolicyRejectDrop = "REJECT-DROP"
	PolicyPass       = "PASS"
	PolicyPassRule   = "PASS-RULE"
	PolicyProxy      = "PROXY"
)

// MatchContext carries all L3/L4/L7 metadata available when evaluating a rule.
type MatchContext struct {
	Domain      string
	DstIP       net.IP
	SrcIP       net.IP
	DstPort     int
	SrcPort     int
	InPort      int
	Network     string // "TCP" or "UDP"
	DSCP        int    // -1 if unknown
	UID         int    // -1 if unknown
	ProcessName string
	ProcessPath string
	InType      string // "TUN", "DNS", "HTTP"
	InUser      string
	InName      string
	UserAgent   string
	DstASN      string
	SrcASN      string
}

type portRange struct {
	start int
	end   int
}

type ipSuffixSpec struct {
	ip   net.IP
	bits int
}

// Rule represents a parsed routing or blocking ruleset rule.
type Rule struct {
	Type       RuleType
	Value      string
	Policy     string
	Order      int
	NoResolve  bool
	IPNet      *net.IPNet
	IPSuffix   *ipSuffixSpec
	PortRanges []portRange
	Regex      *regexp.Regexp
	UIDVal     int
	DSCPVal    int
	SubRules   []Rule
}

// RemoteFilter represents a remote ruleset subscription.
type RemoteFilter struct {
	URL         string
	Tag         string
	Interval    int
	OptParser   bool
	UpdateToken string
}

// Config represents a parsed ruleset configuration.
type Config struct {
	General          map[string]string
	DNSServers       []string
	Rules            []Rule
	RemoteFilters    []RemoteFilter
	FinalPolicy      string
	DNSExclusionList []string
}

// NormalizeDomain lowercases domain and strips leading/trailing dots and spaces.
func NormalizeDomain(domain string) string {
	d := strings.TrimSpace(strings.ToLower(domain))
	d = strings.TrimPrefix(d, ".")
	return strings.TrimSuffix(d, ".")
}
