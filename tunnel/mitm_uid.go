package tunnel

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

// ─────────────────────────────────────────────────────────────────────────────
// UID Resolver — Maps a local TCP socket to its owning Android app UID.
//
// On Android/Linux, /proc/net/tcp and /proc/net/tcp6 contain all active
// TCP sockets with their owning UID. We parse these files to find the UID
// that owns the client-side of a connection to our proxy (127.0.0.1:8080).
//
// Format (from kernel docs):
//   sl  local_address  rem_address  st  ...  uid  ...
//   0:  0100007F:1F90  0100007F:A1B2  01  ...  10145  ...
//
// The addresses are in hex (little-endian for IPv4).
// ─────────────────────────────────────────────────────────────────────────────

// resolveConnUID looks up the UID of the process that owns the given
// TCP connection by parsing /proc/net/tcp and /proc/net/tcp6.
// Returns -1 if the UID cannot be determined.
func resolveConnUID(conn net.Conn) int {
	addr := conn.RemoteAddr()
	if addr == nil {
		return -1
	}

	tcpAddr, ok := addr.(*net.TCPAddr)
	if !ok {
		return -1
	}

	// Format the address as it appears in /proc/net/tcp{,6}
	localHex := formatProcNetAddr(tcpAddr.IP, tcpAddr.Port)
	if localHex == "" {
		return -1
	}

	// Try /proc/net/tcp6 first (covers both IPv4-mapped and IPv6)
	if uid := lookupUIDInProcNet("/proc/net/tcp6", localHex); uid >= 0 {
		return uid
	}
	// Fall back to /proc/net/tcp (IPv4 only)
	if uid := lookupUIDInProcNet("/proc/net/tcp", localHex); uid >= 0 {
		return uid
	}

	return -1
}

// lookupUIDInProcNet scans a /proc/net/tcp{,6} file for a matching local
// address and returns the owning UID. Returns -1 if not found.
func lookupUIDInProcNet(procPath, targetLocalAddr string) int {
	f, err := os.Open(procPath)
	if err != nil {
		return -1
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Scan() // Skip header line

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		fields := strings.Fields(line)
		// Format: sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid ...
		// Index:  0  1              2           3  4                 5           6        7
		if len(fields) < 8 {
			continue
		}

		localAddr := fields[1]
		if strings.EqualFold(localAddr, targetLocalAddr) {
			uid, err := strconv.Atoi(fields[7])
			if err != nil {
				continue
			}
			return uid
		}
	}
	return -1
}

// formatProcNetAddr converts a net.IP + port into the hex format used in
// /proc/net/tcp and /proc/net/tcp6.
//
// IPv4 example: 127.0.0.1:41394 → "0100007F:A1B2"
// IPv6 example: ::1:41394       → "00000000000000000000000001000000:A1B2"
func formatProcNetAddr(ip net.IP, port int) string {
	portHex := fmt.Sprintf("%04X", port)

	// Check if it's an IPv4 address (or IPv4-mapped IPv6)
	ip4 := ip.To4()
	if ip4 != nil {
		// /proc/net/tcp uses little-endian hex for IPv4
		addrHex := fmt.Sprintf("%02X%02X%02X%02X", ip4[3], ip4[2], ip4[1], ip4[0])
		return addrHex + ":" + portHex
	}

	// IPv6: /proc/net/tcp6 uses groups of 4 bytes, each in little-endian
	ip6 := ip.To16()
	if ip6 == nil {
		return ""
	}
	// Each 4-byte group is stored in little-endian
	var sb strings.Builder
	for i := 0; i < 16; i += 4 {
		sb.WriteString(fmt.Sprintf("%02X%02X%02X%02X", ip6[i+3], ip6[i+2], ip6[i+1], ip6[i]))
	}
	return sb.String() + ":" + portHex
}
