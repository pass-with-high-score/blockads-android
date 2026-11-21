package tunnel

import (
	"bufio"
	"net"
	"net/netip"
	"os"
	"strings"

	"github.com/miekg/dns"
)

// ipBlocklist holds compiled public IP and CIDR block rules from `.ips` sidecar files.
// Immutable once constructed so it can be swapped lock-free via atomic.Pointer.
type ipBlocklist struct {
	singles  map[netip.Addr]struct{}
	prefixes []netip.Prefix
}

// containsIP checks whether a net.IP matches any single IP or CIDR prefix in the blocklist.
func (b *ipBlocklist) containsIP(ip net.IP) bool {
	if b == nil || len(ip) == 0 {
		return false
	}
	addr, ok := netip.AddrFromSlice(ip)
	if !ok {
		return false
	}
	return b.containsAddr(addr.Unmap())
}

// containsAddr checks whether a netip.Addr matches any entry in the blocklist.
func (b *ipBlocklist) containsAddr(addr netip.Addr) bool {
	if b == nil || !addr.IsValid() {
		return false
	}
	addr = addr.Unmap()
	if _, ok := b.singles[addr]; ok {
		return true
	}
	for _, pfx := range b.prefixes {
		if pfx.Contains(addr) {
			return true
		}
	}
	return false
}

func parseIPBlocklistFiles(paths []string) (*ipBlocklist, error) {
	singles := make(map[netip.Addr]struct{})
	seenPrefixes := make(map[netip.Prefix]struct{})
	var prefixes []netip.Prefix

	for _, p := range paths {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		f, err := os.Open(p)
		if err != nil {
			logf("IPBlocklist: skip unreadable file %s: %v", p, err)
			continue
		}
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == "" || line[0] == '#' || line[0] == '!' || line[0] == ';' {
				continue
			}
			if strings.ContainsRune(line, '/') {
				pfx, err := netip.ParsePrefix(line)
				if err != nil {
					continue
				}
				pfx = pfx.Masked()
				if (pfx.Addr().Is4() && pfx.Bits() == 32) || (pfx.Addr().Is6() && pfx.Bits() == 128) {
					singles[pfx.Addr().Unmap()] = struct{}{}
				} else if _, dup := seenPrefixes[pfx]; !dup {
					seenPrefixes[pfx] = struct{}{}
					prefixes = append(prefixes, pfx)
				}
			} else if addr, err := netip.ParseAddr(line); err == nil {
				singles[addr.Unmap()] = struct{}{}
			}
		}
		_ = f.Close()
	}

	if len(singles) == 0 && len(prefixes) == 0 {
		return nil, nil
	}
	return &ipBlocklist{
		singles:  singles,
		prefixes: prefixes,
	}, nil
}

// SetIPBlocklistFromFiles loads one or more `.ips` files (newline-separated paths)
// into the engine's lock-free IP/CIDR blocklist. Passing an empty string clears it.
func (e *Engine) SetIPBlocklistFromFiles(pathsNewlineSeparated string) error {
	trimmed := strings.TrimSpace(pathsNewlineSeparated)
	if trimmed == "" {
		e.ipBlocklist.Store(nil)
		logf("IPBlocklist cleared")
		return nil
	}
	paths := strings.Split(trimmed, "\n")
	bl, err := parseIPBlocklistFiles(paths)
	if err != nil {
		return err
	}
	e.ipBlocklist.Store(bl)
	if bl != nil {
		logf("IPBlocklist loaded: %d single IPs, %d CIDR prefixes from %d file(s)",
			len(bl.singles), len(bl.prefixes), len(paths))
	} else {
		logf("IPBlocklist cleared (0 valid entries in %d file(s))", len(paths))
	}
	return nil
}

// isIPBlocked checks whether destination IP matches the active `.ips` blocklist.
func (e *Engine) isIPBlocked(ip net.IP) bool {
	bl := e.ipBlocklist.Load()
	if bl == nil {
		return false
	}
	return bl.containsIP(ip)
}

// checkDNSResponseIPBlocklist inspects A/AAAA records in a raw DNS response and
// returns the first blocked IP string if any resolved IP is in the `.ips` blocklist.
func checkDNSResponseIPBlocklist(rawResp []byte, bl *ipBlocklist) (bool, string) {
	if bl == nil || len(rawResp) == 0 {
		return false, ""
	}
	var msg dns.Msg
	if err := msg.Unpack(rawResp); err != nil {
		return false, ""
	}
	for _, rr := range msg.Answer {
		switch r := rr.(type) {
		case *dns.A:
			if bl.containsIP(r.A) {
				return true, r.A.String()
			}
		case *dns.AAAA:
			if bl.containsIP(r.AAAA) {
				return true, r.AAAA.String()
			}
		}
	}
	return false, ""
}
