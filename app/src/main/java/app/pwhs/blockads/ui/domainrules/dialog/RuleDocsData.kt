package app.pwhs.blockads.ui.domainrules.dialog

enum class RuleCategory {
    ALL, DOMAIN, IP_GEO, APP, OTHER, POLICY
}

data class RuleDoc(
    val name: String,
    val category: RuleCategory,
    val description: String,
    val syntax: String,
    val matches: List<String>,
    val notMatches: List<String> = emptyList(),
    val usage: String
)

internal val RULE_DOCS = listOf(
    // Domain
    RuleDoc(
        name = "HOST-SUFFIX",
        category = RuleCategory.DOMAIN,
        description = "Matches the specified domain and all its subdomains (alias: DOMAIN-SUFFIX). Most commonly used rule type.",
        syntax = "HOST-SUFFIX, doubleclick.net, REJECT",
        matches = listOf("doubleclick.net", "ad.doubleclick.net", "stats.doubleclick.net"),
        notMatches = listOf("notdoubleclick.net"),
        usage = "Blocking an entire ad/tracker service or whitelisting a whole website domain."
    ),
    RuleDoc(
        name = "HOST",
        category = RuleCategory.DOMAIN,
        description = "Matches only the exact domain name specified (alias: DOMAIN). Does not match subdomains or parent domains.",
        syntax = "HOST, tracking.example.com, REJECT",
        matches = listOf("tracking.example.com"),
        notMatches = listOf("example.com", "sub.tracking.example.com"),
        usage = "Targeting a specific sub-host without affecting the root domain or other subdomains."
    ),
    RuleDoc(
        name = "HOST-KEYWORD",
        category = RuleCategory.DOMAIN,
        description = "Matches any host containing the specified keyword anywhere in its name (alias: DOMAIN-KEYWORD).",
        syntax = "HOST-KEYWORD, analytics, REJECT",
        matches = listOf("google-analytics.com", "myanalytics.net", "app.analytics.io"),
        notMatches = listOf("google.com"),
        usage = "Broadly catching domains containing obvious ad or telemetry keywords."
    ),
    RuleDoc(
        name = "DOMAIN-WILDCARD",
        category = RuleCategory.DOMAIN,
        description = "Matches domains using * (any sequence) and ? (single character) glob wildcards (alias: HOST-WILDCARD).",
        syntax = "DOMAIN-WILDCARD, *.ads.*.example.com, REJECT",
        matches = listOf("cdn.ads.v2.example.com", "img.ads.us.example.com"),
        notMatches = listOf("example.com", "ads.example.com"),
        usage = "Flexible multi-segment subdomain patterns without writing full regular expressions."
    ),
    RuleDoc(
        name = "DOMAIN-REGEX",
        category = RuleCategory.DOMAIN,
        description = "Matches domains using a case-insensitive regular expression (alias: HOST-REGEX).",
        syntax = "DOMAIN-REGEX, ^ad[0-9]+\\..+\\.com$, REJECT",
        matches = listOf("ad1.tracker.com", "ad42.cdn.com"),
        notMatches = listOf("ads.tracker.com", "cdn.example.net"),
        usage = "Precision matching for rotating numbered ad hosts or complex naming schemes."
    ),
    RuleDoc(
        name = "GEOSITE",
        category = RuleCategory.DOMAIN,
        description = "Matches domains belonging to a curated Geosite category (such as ads, social media, or regional services).",
        syntax = "GEOSITE, category-ads, REJECT",
        matches = listOf("All domains included in the 'category-ads' geosite group"),
        notMatches = listOf("Domains outside the specified category"),
        usage = "Bulk-matching well-known service categories like category-ads, apple, or private."
    ),
    RuleDoc(
        name = "USER-AGENT",
        category = RuleCategory.DOMAIN,
        description = "Matches the HTTP User-Agent header of requests with wildcard support.",
        syntax = "USER-AGENT, *TrackingSDK*, REJECT",
        matches = listOf("TrackingSDK/2.1 Android/34", "App-TrackingSDK"),
        notMatches = listOf("Mozilla/5.0 Chrome"),
        usage = "Blocking or routing HTTP traffic sent by a specific SDK or client."
    ),

    // IP & Network
    RuleDoc(
        name = "IP-CIDR",
        category = RuleCategory.IP_GEO,
        description = "Matches destination IPv4 addresses belonging to the specified CIDR subnet block.",
        syntax = "IP-CIDR, 192.168.0.0/16, DIRECT",
        matches = listOf("192.168.1.1", "192.168.100.5"),
        notMatches = listOf("10.0.0.1", "8.8.8.8"),
        usage = "Whitelisting local area networks (LAN) or filtering specific IPv4 subnets."
    ),
    RuleDoc(
        name = "IP-CIDR6",
        category = RuleCategory.IP_GEO,
        description = "Matches destination IPv6 addresses belonging to the specified CIDR subnet block.",
        syntax = "IP-CIDR6, 2001:db8::/32, REJECT",
        matches = listOf("2001:db8::1", "2001:db8:ffff::1"),
        notMatches = listOf("2001:db9::1"),
        usage = "Filtering or routing specific IPv6 network ranges."
    ),
    RuleDoc(
        name = "IP-SUFFIX",
        category = RuleCategory.IP_GEO,
        description = "Matches destination IP addresses by suffix bitmask (trailing bits).",
        syntax = "IP-SUFFIX, 0.0.0.1/8, DIRECT",
        matches = listOf("192.168.1.1", "10.0.0.1"),
        notMatches = listOf("192.168.1.2"),
        usage = "Matching specific host IDs across multiple subnets (e.g. gateway addresses)."
    ),
    RuleDoc(
        name = "IP-ASN",
        category = RuleCategory.IP_GEO,
        description = "Matches destination IP addresses announced by a specific Autonomous System Number (ASN).",
        syntax = "IP-ASN, 15169, DIRECT",
        matches = listOf("IPs belonging to AS15169 (Google)"),
        notMatches = listOf("IPs belonging to other ASNs"),
        usage = "Routing or blocking all traffic owned by a specific ISP, CDN, or cloud provider."
    ),
    RuleDoc(
        name = "GEOIP",
        category = RuleCategory.IP_GEO,
        description = "Matches the geographic country code (ISO 3166-1 alpha-2) of the destination server.",
        syntax = "GEOIP, VN, DIRECT",
        matches = listOf("Destination IPs located in Vietnam (VN)"),
        notMatches = listOf("Destination IPs located outside VN"),
        usage = "Bypassing domestic traffic directly while tunneling international traffic."
    ),
    RuleDoc(
        name = "SRC-IP-CIDR",
        category = RuleCategory.IP_GEO,
        description = "Matches the source IPv4 address of an incoming connection against a CIDR subnet.",
        syntax = "SRC-IP-CIDR, 10.0.0.0/8, DIRECT",
        matches = listOf("Requests originating from 10.0.0.5"),
        notMatches = listOf("Requests originating from 192.168.1.5"),
        usage = "Applying rules to traffic originating from a specific local or hotspot subnet."
    ),
    RuleDoc(
        name = "SRC-IP-SUFFIX",
        category = RuleCategory.IP_GEO,
        description = "Matches the source IP address by suffix bitmask.",
        syntax = "SRC-IP-SUFFIX, 0.0.0.1/8, DIRECT",
        matches = listOf("Source IPs ending in .1"),
        notMatches = listOf("Source IPs ending in .2"),
        usage = "Advanced LAN/hotspot routing based on client host-ID suffix."
    ),
    RuleDoc(
        name = "SRC-IP-ASN",
        category = RuleCategory.IP_GEO,
        description = "Matches the source IP address by its Autonomous System Number (ASN).",
        syntax = "SRC-IP-ASN, 13335, DIRECT",
        matches = listOf("Traffic originating from AS13335"),
        notMatches = listOf("Traffic originating from other ASNs"),
        usage = "Filtering inbound connections by source network operator."
    ),
    RuleDoc(
        name = "SRC-GEOIP",
        category = RuleCategory.IP_GEO,
        description = "Matches the geographic country code of the source IP address.",
        syntax = "SRC-GEOIP, VN, DIRECT",
        matches = listOf("Connections originating from Vietnam (VN)"),
        notMatches = listOf("Connections originating from other countries"),
        usage = "Geo-based filtering on source addresses for inbound proxy listeners."
    ),
    RuleDoc(
        name = "DST-PORT",
        category = RuleCategory.IP_GEO,
        description = "Matches the destination port number, port range, or slash-separated list of ports.",
        syntax = "DST-PORT, 443, DIRECT",
        matches = listOf("Port 443", "With 8000-9000: ports 8000 through 9000", "With 80/443: port 80 or 443"),
        notMatches = listOf("Port 53"),
        usage = "Blocking QUIC/UDP 443, DoT port 853, or targeting specific service ports."
    ),
    RuleDoc(
        name = "SRC-PORT",
        category = RuleCategory.IP_GEO,
        description = "Matches the source port number or range of the connection.",
        syntax = "SRC-PORT, 5353, DIRECT",
        matches = listOf("Connections originating from port 5353 (mDNS)"),
        notMatches = listOf("Connections from ephemeral ports"),
        usage = "Bypassing local discovery protocols or specific client source ports."
    ),
    RuleDoc(
        name = "IN-PORT",
        category = RuleCategory.IP_GEO,
        description = "Matches the local inbound listener port that received the connection.",
        syntax = "IN-PORT, 7890, PROXY",
        matches = listOf("Traffic arriving on inbound port 7890"),
        notMatches = listOf("Traffic arriving on other listener ports"),
        usage = "Separating routing policies when multiple local proxy ports are in use."
    ),
    RuleDoc(
        name = "NETWORK",
        category = RuleCategory.IP_GEO,
        description = "Matches the transport layer protocol: TCP or UDP.",
        syntax = "NETWORK, UDP, REJECT",
        matches = listOf("All UDP packets (when set to UDP)"),
        notMatches = listOf("TCP connections"),
        usage = "Combining with AND/DST-PORT to block QUIC (UDP 443) or control UDP traffic."
    ),
    RuleDoc(
        name = "DSCP",
        category = RuleCategory.IP_GEO,
        description = "Matches the IP Differentiated Services Code Point (DSCP / QoS) marking (0–63).",
        syntax = "DSCP, 46, DIRECT",
        matches = listOf("Packets marked with DSCP 46 (Expedited Forwarding / VoIP)"),
        notMatches = listOf("Default best-effort packets (DSCP 0)"),
        usage = "Bypassing latency-sensitive VoIP or real-time media traffic."
    ),

    // App & Process
    RuleDoc(
        name = "PROCESS-NAME",
        category = RuleCategory.APP,
        description = "Matches the exact Android package name or process name initiating the connection.",
        syntax = "PROCESS-NAME, com.zhiliaoapp.musically, REJECT",
        matches = listOf("Connections made by com.zhiliaoapp.musically"),
        notMatches = listOf("Connections made by other apps"),
        usage = "Allowing, proxying, or blocking network traffic for a specific app."
    ),
    RuleDoc(
        name = "PROCESS-NAME-WILDCARD",
        category = RuleCategory.APP,
        description = "Matches app package or process names using * and ? glob wildcards.",
        syntax = "PROCESS-NAME-WILDCARD, com.google.*, DIRECT",
        matches = listOf("com.google.android.gms", "com.google.android.youtube"),
        notMatches = listOf("org.mozilla.firefox"),
        usage = "Applying one rule to an entire suite of apps sharing a package prefix."
    ),
    RuleDoc(
        name = "PROCESS-NAME-REGEX",
        category = RuleCategory.APP,
        description = "Matches app package or process names using a regular expression.",
        syntax = "PROCESS-NAME-REGEX, ^com\\.facebook\\..*, REJECT",
        matches = listOf("com.facebook.katana", "com.facebook.orca"),
        notMatches = listOf("com.whatsapp"),
        usage = "Complex per-app matching patterns across multiple package names."
    ),
    RuleDoc(
        name = "PROCESS-PATH",
        category = RuleCategory.APP,
        description = "Matches the exact filesystem executable path of the originating process.",
        syntax = "PROCESS-PATH, /system/bin/curl, DIRECT",
        matches = listOf("Process launched from /system/bin/curl"),
        notMatches = listOf("Process launched from other paths"),
        usage = "Targeting specific native binaries or system daemons."
    ),
    RuleDoc(
        name = "PROCESS-PATH-WILDCARD",
        category = RuleCategory.APP,
        description = "Matches executable paths using * and ? glob wildcards.",
        syntax = "PROCESS-PATH-WILDCARD, */chrome/*, DIRECT",
        matches = listOf("/data/app/com.android.chrome/base.apk"),
        notMatches = listOf("/system/bin/sh"),
        usage = "Matching binaries located inside a directory pattern."
    ),
    RuleDoc(
        name = "PROCESS-PATH-REGEX",
        category = RuleCategory.APP,
        description = "Matches executable paths using a regular expression.",
        syntax = "PROCESS-PATH-REGEX, .*/ads/.*, REJECT",
        matches = listOf("/data/local/ads/daemon"),
        notMatches = listOf("/system/bin/netd"),
        usage = "Advanced path-based matching for native or rooted environments."
    ),
    RuleDoc(
        name = "UID",
        category = RuleCategory.APP,
        description = "Matches the Linux / Android numeric user ID (UID) assigned to an app or system service.",
        syntax = "UID, 10123, DIRECT",
        matches = listOf("All traffic from UID 10123"),
        notMatches = listOf("Traffic from other UIDs"),
        usage = "Targeting apps or system processes (e.g. UID 0 for root, 1000 for system) directly by UID."
    ),

    // Advanced / Special
    RuleDoc(
        name = "IN-TYPE",
        category = RuleCategory.OTHER,
        description = "Matches the inbound handler type that accepted the connection (e.g. TUN, DNS, HTTP, SOCKS).",
        syntax = "IN-TYPE, TUN, DIRECT",
        matches = listOf("Connections entering through the TUN interface"),
        notMatches = listOf("Connections entering through HTTP/SOCKS inbound"),
        usage = "Distinguishing TUN-captured app traffic from local proxy or DNS queries."
    ),
    RuleDoc(
        name = "IN-USER",
        category = RuleCategory.OTHER,
        description = "Matches the authenticated username on a local inbound proxy listener.",
        syntax = "IN-USER, alice, PROXY",
        matches = listOf("Proxy requests authenticated as 'alice'"),
        notMatches = listOf("Unauthenticated requests or other users"),
        usage = "Per-user routing rules when sharing a local proxy inbound."
    ),
    RuleDoc(
        name = "IN-NAME",
        category = RuleCategory.OTHER,
        description = "Matches the configured name of the inbound listener.",
        syntax = "IN-NAME, socks-in, PROXY",
        matches = listOf("Traffic received by listener named 'socks-in'"),
        notMatches = listOf("Traffic from other listeners"),
        usage = "Routing traffic differently per named inbound entry point."
    ),
    RuleDoc(
        name = "RULE-SET",
        category = RuleCategory.OTHER,
        description = "References a named external or local rule provider / rule-set.",
        syntax = "RULE-SET, ads-list, REJECT",
        matches = listOf("Any request matching an entry inside 'ads-list'"),
        notMatches = listOf("Requests not in 'ads-list'"),
        usage = "Reusing large, externally maintained rule collections cleanly."
    ),
    RuleDoc(
        name = "AND",
        category = RuleCategory.OTHER,
        description = "Logical AND: matches only when ALL enclosed sub-rules match.",
        syntax = "AND, ((NETWORK,UDP),(DST-PORT,443)), REJECT",
        matches = listOf("UDP traffic going to port 443 (QUIC)"),
        notMatches = listOf("TCP port 443", "UDP port 53"),
        usage = "Combining multiple conditions, such as blocking QUIC or restricting a specific app's port."
    ),
    RuleDoc(
        name = "OR",
        category = RuleCategory.OTHER,
        description = "Logical OR: matches when ANY enclosed sub-rule matches.",
        syntax = "OR, ((DST-PORT,80),(DST-PORT,443)), DIRECT",
        matches = listOf("Traffic to port 80", "Traffic to port 443"),
        notMatches = listOf("Traffic to port 8080"),
        usage = "Grouping alternative conditions into a single rule."
    ),
    RuleDoc(
        name = "NOT",
        category = RuleCategory.OTHER,
        description = "Logical NOT: matches when the enclosed sub-rule does NOT match.",
        syntax = "NOT, ((GEOIP,VN)), PROXY",
        matches = listOf("All traffic whose destination is outside Vietnam"),
        notMatches = listOf("Traffic whose destination is in Vietnam (VN)"),
        usage = "Inverting a condition, such as routing all non-domestic traffic through a proxy."
    ),
    RuleDoc(
        name = "SUB-RULE",
        category = RuleCategory.OTHER,
        description = "When the condition matches, jumps evaluation into a named sub-rule group.",
        syntax = "SUB-RULE, (GEOIP,CN), my-subrule",
        matches = listOf("Requests matching (GEOIP,CN) are evaluated against 'my-subrule'"),
        notMatches = listOf("Requests not matching the condition continue to the next rule"),
        usage = "Structuring complex multi-stage routing trees cleanly."
    ),
    RuleDoc(
        name = "FINAL",
        category = RuleCategory.OTHER,
        description = "Fallback rule evaluated when no preceding rules match (alias: MATCH). Does not take any parameter.",
        syntax = "FINAL, DIRECT",
        matches = listOf("All remaining unmatched traffic"),
        notMatches = emptyList(),
        usage = "Defining the default catch-all action for all unhandled requests."
    ),

    // Policies
    RuleDoc(
        name = "REJECT (Policy)",
        category = RuleCategory.POLICY,
        description = "Blocks the request immediately (returns NXDOMAIN / 0.0.0.0 for DNS or refuses connection).",
        syntax = "HOST-SUFFIX, adservice.google.com, REJECT",
        matches = listOf("Ads, telemetry, trackers, scam & malware domains"),
        notMatches = emptyList(),
        usage = "Standard blocklisting of unwanted domains and advertisements."
    ),
    RuleDoc(
        name = "REJECT-DROP (Policy)",
        category = RuleCategory.POLICY,
        description = "Silently drops matching packets without sending any reset or error response.",
        syntax = "AND, ((NETWORK,UDP),(DST-PORT,443)), REJECT-DROP",
        matches = listOf("Aggressive retry trackers, QUIC/UDP probes"),
        notMatches = emptyList(),
        usage = "Silently blackholing UDP traffic or trackers that spam retries on immediate rejection."
    ),
    RuleDoc(
        name = "DIRECT (Policy)",
        category = RuleCategory.POLICY,
        description = "Bypasses ad-blocking and proxy routing, allowing the connection directly (Whitelist).",
        syntax = "HOST-SUFFIX, banking.example.com, DIRECT",
        matches = listOf("Whitelisted trusted sites, local network traffic, critical apps"),
        notMatches = emptyList(),
        usage = "Fixing broken websites or apps that reject VPN/ad-blocking connections."
    ),
    RuleDoc(
        name = "PASS (Policy)",
        category = RuleCategory.POLICY,
        description = "Stops evaluating remaining filter rules and allows the request to proceed.",
        syntax = "HOST-SUFFIX, trusted-cdn.com, PASS",
        matches = listOf("Traffic that should skip subsequent block rules"),
        notMatches = emptyList(),
        usage = "Early-exit allow rule that prevents lower-priority rules from blocking a request."
    ),
    RuleDoc(
        name = "PASS-RULE (Policy)",
        category = RuleCategory.POLICY,
        description = "Skips the current rule group/sub-rule and continues evaluating the remaining rules.",
        syntax = "HOST-SUFFIX, example.com, PASS-RULE",
        matches = listOf("Exceptions inside a sub-rule or rule group"),
        notMatches = emptyList(),
        usage = "Carving out exceptions inside a SUB-RULE block while still running global rules afterward."
    ),
    RuleDoc(
        name = "PROXY (Policy)",
        category = RuleCategory.POLICY,
        description = "Forwards matching traffic through the configured proxy node when proxy routing is active.",
        syntax = "HOST-SUFFIX, overseas-service.com, PROXY",
        matches = listOf("Foreign services, geo-restricted content"),
        notMatches = emptyList(),
        usage = "Selective proxying for cross-border or restricted traffic."
    )
)
