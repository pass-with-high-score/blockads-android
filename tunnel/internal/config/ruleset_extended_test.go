package config

import (
	"net"
	"testing"
)

func TestExtendedRulesAndLogicMatching(t *testing.T) {
	snippet := `
[filter_local]
AND,((NETWORK,UDP),(DST-PORT,443)),REJECT-DROP
AND,((NETWORK,UDP),(DST-PORT,3478/19302-19309)),REJECT-DROP
DST-PORT,853,REJECT
IP-CIDR,127.0.0.0/8,REJECT,no-resolve
IP-SUFFIX,0.0.0.1/8,DIRECT
SRC-IP-CIDR,10.0.0.0/8,PASS
DOMAIN-WILDCARD,*.ads.?.example.com,REJECT
DOMAIN-REGEX,^ad[0-9]{1,3}\.cdn\.io$,REJECT
GEOSITE,private,DIRECT
GEOIP,LAN,DIRECT,no-resolve
PROCESS-NAME-REGEX,(?i)^(qbittorrent|transmission.*)$,DIRECT
UID,10123,REJECT
OR,((DST-PORT,8080),(DST-PORT,8443)),PROXY
NOT,((IN-TYPE,TUN)),DIRECT
MATCH,DIRECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("ParseRuleset error: %v", err)
	}
	m := NewMatcher(cfg)

	// 1. Block QUIC (AND + NETWORK + DST-PORT + REJECT-DROP)
	policy, _ := MatchRuleContext(m, MatchContext{Network: "UDP", DstPort: 443, UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyRejectDrop {
		t.Fatalf("expected REJECT-DROP for UDP:443, got %s", policy)
	}

	// 2. Block STUN range (3478/19302-19309)
	policy, _ = MatchRuleContext(m, MatchContext{Network: "UDP", DstPort: 19305, UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyRejectDrop {
		t.Fatalf("expected REJECT-DROP for UDP:19305, got %s", policy)
	}

	// 3. Block DoT (TCP:853)
	policy, _ = MatchRuleContext(m, MatchContext{Network: "TCP", DstPort: 853, UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyReject {
		t.Fatalf("expected REJECT for DST-PORT:853, got %s", policy)
	}

	// 4. Block Loopback IP-CIDR
	policy, _ = MatchRuleContext(m, MatchContext{DstIP: net.ParseIP("127.0.0.1"), UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyReject {
		t.Fatalf("expected REJECT for 127.0.0.1, got %s", policy)
	}

	// 5. IP-SUFFIX (.1 trailing octet)
	policy, _ = MatchRuleContext(m, MatchContext{DstIP: net.ParseIP("8.8.8.1"), UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyDirect {
		t.Fatalf("expected DIRECT for IP-SUFFIX 8.8.8.1, got %s", policy)
	}

	// 6. SRC-IP-CIDR -> PASS
	policy, _ = MatchRuleContext(m, MatchContext{SrcIP: net.ParseIP("10.1.2.3"), UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyPass {
		t.Fatalf("expected PASS for SRC-IP-CIDR 10.1.2.3, got %s", policy)
	}

	// 7. DOMAIN-WILDCARD & DOMAIN-REGEX (with comma inside {1,3})
	policy, _ = MatchRuleContext(m, MatchContext{Domain: "img.ads.2.example.com", UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyReject {
		t.Fatalf("expected REJECT for DOMAIN-WILDCARD, got %s", policy)
	}
	policy, _ = MatchRuleContext(m, MatchContext{Domain: "ad42.cdn.io", UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyReject {
		t.Fatalf("expected REJECT for DOMAIN-REGEX with comma quantifier, got %s", policy)
	}

	// 8. GEOSITE private & GEOIP LAN
	policy, _ = MatchRuleContext(m, MatchContext{Domain: "printer.local", UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyDirect {
		t.Fatalf("expected DIRECT for GEOSITE private, got %s", policy)
	}
	policy, _ = MatchRuleContext(m, MatchContext{DstIP: net.ParseIP("192.168.1.50"), UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyDirect {
		t.Fatalf("expected DIRECT for GEOIP LAN, got %s", policy)
	}

	// 9. PROCESS-NAME-REGEX & UID
	policy, _ = MatchRuleContext(m, MatchContext{ProcessName: "qBittorrent", UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyDirect {
		t.Fatalf("expected DIRECT for PROCESS-NAME-REGEX, got %s", policy)
	}
	policy, _ = MatchRuleContext(m, MatchContext{UID: 10123, DSCP: -1, InType: "TUN"})
	if policy != PolicyReject {
		t.Fatalf("expected REJECT for UID 10123, got %s", policy)
	}

	// 10. OR & NOT
	policy, _ = MatchRuleContext(m, MatchContext{DstPort: 8443, UID: -1, DSCP: -1, InType: "TUN"})
	if policy != PolicyProxy {
		t.Fatalf("expected PROXY for OR(8080,8443), got %s", policy)
	}
	policy, _ = MatchRuleContext(m, MatchContext{InType: "HTTP", UID: -1, DSCP: -1})
	if policy != PolicyDirect {
		t.Fatalf("expected DIRECT for NOT(IN-TYPE,TUN), got %s", policy)
	}
}
