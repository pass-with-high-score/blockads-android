package trie

import (
	"net/netip"
	"strings"
)

// parseIPOrCIDR parses a raw filter line that represents a single public IPv4/IPv6
// address or CIDR prefix, matching the blockads-filtering server compiler spec:
//   - Strips inline comments (#, !, ;) and surrounding whitespace
//   - Rejects private (RFC1918 / ULA), loopback, link-local, multicast, unspecified IPs
//   - Rejects overly broad CIDRs (< /8 for IPv4, < /16 for IPv6)
//   - Returns canonical string: single host as plain IP (e.g. "162.243.103.246"),
//     subnet as masked CIDR (e.g. "1.10.16.0/20", "2001:db8::/32").
func parseIPOrCIDR(rawLine string) (string, bool) {
	line := strings.TrimSpace(rawLine)
	if line == "" || line[0] == '#' || line[0] == '!' || line[0] == ';' {
		return "", false
	}

	// Strip inline comments
	if idx := strings.IndexAny(line, "#!;"); idx >= 0 {
		line = strings.TrimSpace(line[:idx])
	}
	// If line has extra fields (e.g., "0.0.0.0 example.com"), it's a hosts rule, not an IP block rule.
	if line == "" || strings.ContainsAny(line, " \t,") {
		return "", false
	}

	if strings.ContainsRune(line, '/') {
		pfx, err := netip.ParsePrefix(line)
		if err != nil {
			return "", false
		}
		pfx = pfx.Masked()
		addr := pfx.Addr()
		if !isPublicBlockableAddr(addr) {
			return "", false
		}
		if addr.Is4() {
			if pfx.Bits() < 8 {
				return "", false
			}
			if pfx.Bits() == 32 {
				return addr.String(), true
			}
		} else {
			if pfx.Bits() < 16 {
				return "", false
			}
			if pfx.Bits() == 128 {
				return addr.String(), true
			}
		}
		return pfx.String(), true
	}

	addr, err := netip.ParseAddr(line)
	if err != nil {
		return "", false
	}
	addr = addr.Unmap()
	if !isPublicBlockableAddr(addr) {
		return "", false
	}
	return addr.String(), true
}

func isPublicBlockableAddr(addr netip.Addr) bool {
	if !addr.IsValid() || addr.IsLoopback() || addr.IsPrivate() ||
		addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() ||
		addr.IsMulticast() || addr.IsUnspecified() {
		return false
	}
	// Reject CGNAT 100.64.0.0/10 and broadcast 255.255.255.255
	if addr.Is4() {
		b := addr.As4()
		if b[0] == 0 || b[0] == 100 && (b[1]&0xC0) == 64 || (b[0] == 255 && b[1] == 255 && b[2] == 255 && b[3] == 255) {
			return false
		}
	}
	return true
}
