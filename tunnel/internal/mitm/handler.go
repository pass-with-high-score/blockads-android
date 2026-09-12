package mitm

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"strings"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

// ─────────────────────────────────────────────────────────────────────────────
// mitm_handler.go — Phase D flow-mode MITM handler.
//
// Replaces the Phase C direct-dial default for port 443/80 TCP flows
// when the stack is configured with a CertManager + MitmFilter via
// Engine.StartStackMitm. Decision flow mirrors the legacy MitmProxy
// (mitm_proxy.go handleConnect) but operates on terminated TCP flows
// from the userspace stack rather than HTTP CONNECT requests:
//
//   Gate 0: loopback / private IP → passthrough
//   Gate 1: port != 443/80        → passthrough
//   Gate 2: UID not in allowlist  → passthrough
//   Gate 3: peek first bytes      → classify TLS / HTTP, extract SNI / Host
//   Gate 4: ad-block blocker      → close
//   Gate 5: interception filter   → passthrough (sensitive / pinned domain)
//   Gate 6: local asset server    → serve from memory
//   Gate 7: MITM                  → TLS handshake with our cert, relay HTTP + inject
//
// Every non-MITM path uses the Phase C protected dialer so sockets
// bypass the VPN loop.
// ─────────────────────────────────────────────────────────────────────────────

const (
	// peekSize must be large enough to contain a full TLS ClientHello
	// including any SNI extension. 4 KB is the modern ceiling.
	peekSize = 4 * 1024

	// peekTimeout bounds how long we wait for the client's first bytes
	// before giving up and closing the flow. Browsers typically send
	// data within a few ms of the TCP SYN-ACK; a stuck flow here would
	// otherwise wedge a handler goroutine.
	peekTimeout = 10 * time.Second
)

// newMitmTcpHandler returns a TCP flow handler that MITMs HTTPS/HTTP
// flows for eligible apps and passes every other flow through directly
// NewMitmTcpHandler returns a TCP flow handler that MITMs HTTPS/HTTP
// flows for eligible apps and passes every other flow through directly
// with socket protection. The handler owns the flow for its lifetime.
func NewMitmTcpHandler(
	certMgr *CertManager,
	filter *MitmFilter,
	blocker AdBlockChecker,
	uidr UIDResolver,
	protectFn func(fd int) bool,
) TcpFlowHandler {
	return func(conn adapter.TCPConn) {
		defer conn.Close()

		flow := tcpFlowID(conn)
		uid := resolveFlowUID(uidr, ProtocolTCP, flow)

		if flow.serverIP.IsUnspecified() {
			return
		}

		// Connection log (full-tunnel): surface every flow with its owning
		// app + destination, so apps that barely use DNS (Telegram/WhatsApp
		// → hard-coded IPs) are visible in the log screen.
		if blocker != nil {
			blocker.LogConnection(flow, ProtocolTCP)
		}

		// Gate -1 — DNS-over-TLS (port 853). Under full-tunnel routing the
		// system's Private DNS resolver probes DoT against our fake DNS
		// server (10.0.0.1 / fd00::1), which isn't a real host — the dial
		// would hang for flowDialTimeout (10s) and stall all DNS. Close
		// immediately so Android falls back to plaintext DNS on port 53,
		// which the engine intercepts and filters. Mirrors the fake-DNS /
		// force-port-53 approach already used in WireGuard mode.
		// Gate -1 — DoT (port 853). If DoH/DoT blocking is enabled, close connection
		// so client falls back to port 53 DNS.
		if blocker != nil && blocker.IsDoHBlockingEnabled() && flow.serverPort == 853 {
			return
		}

		// Gate -1.5 — Hardcoded DoH Direct-IP (port 443). If DoH/DoT blocking is enabled
		// and an app tries to connect directly to known public DoH server IPs,
		// close immediately so it falls back to system DNS on port 53.
		if blocker != nil && blocker.IsDoHBlockingEnabled() && flow.serverPort == 443 && IsKnownPublicDoHIP(flow.serverIP) {
			return
		}

		// Gate 0 — never MITM private / loopback destinations. These
		// are local services (LAN printers, router admin pages) that
		// often have self-signed certs or none at all.
		if isLoopbackOrInternal(flow.serverIP.String()) {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 1 — only attempt MITM on HTTP/HTTPS well-known ports.
		if flow.serverPort != 443 && flow.serverPort != 80 {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 2 — browser allowlist (UID). When Kotlin has configured
		// an allowlist, non-allowed UIDs get passthrough. If UID is
		// unknown (API < 29, resolver failure), err on the safe side:
		// passthrough rather than MITM an unknown app.
		if filter.HasAllowedUIDs() && (uid == UIDUnknown || !filter.IsUIDAllowed(uid)) {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 3 — peek first bytes to classify and extract SNI / Host.
		peeked, peekedReader, err := peekFlow(conn, peekSize, peekTimeout)
		if err != nil || len(peeked) == 0 {
			return
		}

		sni := ""
		var classification flowClass
		if len(peeked) >= 3 && peeked[0] == 0x16 && peeked[1] == 0x03 {
			classification = classTLS
			sni = parseClientHelloSNI(peeked)
		} else if looksLikeHTTPRequest(peeked) {
			classification = classHTTP
			sni = parseHTTPHost(peeked)
		} else {
			relayDirectPeeked(conn, peekedReader, flow, "", blocker, protectFn)
			return
		}

		hostname := sni
		if hostname == "" {
			hostname = flow.serverIP.String()
		}
		hostname = strings.ToLower(strings.TrimSpace(hostname))

		// Gate 4 — ad-block blocker.
		if blocker != nil && blocker.IsDomainBlocked(hostname) {
			return
		}

		// Gate 5 — sensitive / cert-pinned domain.
		if !filter.IsInterceptionAllowed(hostname) {
			relayDirectPeeked(conn, peekedReader, flow, hostname, blocker, protectFn)
			return
		}

		// Gate 6 — local asset server.
		if IsLocalAssetHost(hostname) {
			if classification == classTLS {
				serveLocalAssetTLS(conn, peekedReader, certMgr, hostname)
			} else {
				serveLocalAssetPlaintext(conn, peekedReader)
			}
			return
		}

		// Gate 7 — MITM.
		if classification == classTLS {
			mitmTLSFlow(conn, peekedReader, certMgr, filter, blocker, hostname, flow, protectFn)
		} else {
			mitmHTTPFlow(conn, peekedReader, blocker, hostname, flow, protectFn)
		}
	}
}

// NewMitmUdpHandler wraps the protected UDP relay with QUIC suppression
// for browser UIDs.
func NewMitmUdpHandler(filter *MitmFilter, uidr UIDResolver, baseRelay UdpFlowHandler) UdpFlowHandler {
	return func(conn adapter.UDPConn) {
		flow := udpFlowID(conn)
		if flow.serverPort == 443 && filter != nil && filter.HasAllowedUIDs() {
			uid := resolveFlowUID(uidr, ProtocolUDP, flow)
			if uid != UIDUnknown && filter.IsUIDAllowed(uid) {
				_ = conn.Close()
				return
			}
		}
		if baseRelay != nil {
			baseRelay(conn)
		}
	}
}

type flowClass int

const (
	classUnknown flowClass = iota
	classTLS
	classHTTP
)

// peekFlow reads the first batch of client data (up to maxBytes) and
// returns both those bytes for classification and a Reader that
// replays them followed by anything the client sends next.
func peekFlow(conn net.Conn, maxBytes int, timeout time.Duration) ([]byte, io.Reader, error) {
	conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})

	buf := make([]byte, maxBytes)
	n, err := conn.Read(buf)
	if n == 0 {
		return nil, nil, err
	}
	peeked := buf[:n]
	return peeked, io.MultiReader(bytes.NewReader(peeked), conn), nil
}

// parseClientHelloSNI extracts the server_name extension from a TLS
// ClientHello record. Returns "" if the bytes aren't a ClientHello or
// the SNI extension is absent.
func parseClientHelloSNI(record []byte) string {
	if len(record) < 5 || record[0] != 0x16 {
		return ""
	}
	recLen := int(binary.BigEndian.Uint16(record[3:5]))
	if recLen > len(record)-5 {
		recLen = len(record) - 5
	}
	body := record[5 : 5+recLen]

	if len(body) < 4 || body[0] != 0x01 {
		return ""
	}
	ch := body[4:]

	if len(ch) < 2+32+1 {
		return ""
	}
	p := 34
	sidLen := int(ch[p])
	p += 1 + sidLen
	if p+2 > len(ch) {
		return ""
	}
	csLen := int(binary.BigEndian.Uint16(ch[p : p+2]))
	p += 2 + csLen
	if p+1 > len(ch) {
		return ""
	}
	cmLen := int(ch[p])
	p += 1 + cmLen
	if p+2 > len(ch) {
		return ""
	}
	extLen := int(binary.BigEndian.Uint16(ch[p : p+2]))
	p += 2
	if p+extLen > len(ch) {
		extLen = len(ch) - p
	}
	ext := ch[p : p+extLen]

	for len(ext) >= 4 {
		extType := binary.BigEndian.Uint16(ext[0:2])
		extDataLen := int(binary.BigEndian.Uint16(ext[2:4]))
		if 4+extDataLen > len(ext) {
			return ""
		}
		extData := ext[4 : 4+extDataLen]

		if extType == 0x0000 {
			if len(extData) < 5 {
				return ""
			}
			listLen := int(binary.BigEndian.Uint16(extData[0:2]))
			if 2+listLen > len(extData) {
				return ""
			}
			list := extData[2 : 2+listLen]
			if len(list) < 3 || list[0] != 0x00 {
				return ""
			}
			nameLen := int(binary.BigEndian.Uint16(list[1:3]))
			if 3+nameLen > len(list) {
				return ""
			}
			return string(list[3 : 3+nameLen])
		}
		ext = ext[4+extDataLen:]
	}
	return ""
}

// looksLikeHTTPRequest returns true when the first bytes look like an
// HTTP/1.x request line.
func looksLikeHTTPRequest(b []byte) bool {
	if len(b) < 7 {
		return false
	}
	for i := 0; i < len(b) && i < 16; i++ {
		if b[i] == ' ' {
			if i+2 < len(b) && b[i+1] == '/' {
				return true
			}
			return false
		}
		c := b[i]
		if !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
			return false
		}
	}
	return false
}

// parseHTTPHost extracts the Host header value from a raw HTTP request.
func parseHTTPHost(b []byte) string {
	idx := 0
	for idx < len(b) {
		nl := -1
		for j := idx; j < len(b)-1; j++ {
			if b[j] == '\r' && b[j+1] == '\n' {
				nl = j
				break
			}
		}
		if nl < 0 {
			return ""
		}
		line := b[idx:nl]
		idx = nl + 2

		if len(line) == 0 {
			return ""
		}

		colon := -1
		for j := 0; j < len(line); j++ {
			if line[j] == ':' {
				colon = j
				break
			}
		}
		if colon <= 0 {
			continue
		}
		name := line[:colon]
		if strings.EqualFold(string(name), "Host") {
			value := strings.TrimSpace(string(line[colon+1:]))
			if i := strings.IndexByte(value, ':'); i >= 0 {
				value = value[:i]
			}
			return value
		}
	}
	return ""
}

// dialUpstream dials the flow's destination with socket protection.
func dialUpstream(flow flowID, hostname string, blocker adBlockChecker, protectFn func(fd int) bool) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout: flowDialTimeout,
		Control: protectedControl(protectFn),
	}
	dst := net.JoinHostPort(flow.serverIP.String(), intToStr(int(flow.serverPort)))
	conn, err := dialer.Dial("tcp", dst)
	if err == nil {
		return conn, nil
	}

	if hostname != "" && blocker != nil && flow.serverIP.To4() == nil {
		if ip, lerr := blocker.LookupIP(hostname); lerr == nil && ip != nil {
			alt := net.JoinHostPort(ip.String(), intToStr(int(flow.serverPort)))
			if altConn, aerr := dialer.Dial("tcp", alt); aerr == nil {
				logf("[TcpStack] v6 dial to %s failed (%v); fell back to v4 %s", dst, err, alt)
				return altConn, nil
			}
		}
	}
	logf("[TcpStack] upstream dial %s failed: %v", dst, err)
	return nil, err
}

// relayDirectFromFlow dials the flow's real destination and pipes
// bytes bidirectionally.
func relayDirectFromFlow(clientConn net.Conn, flow flowID, blocker adBlockChecker, protectFn func(fd int) bool) {
	remote, err := dialUpstream(flow, "", blocker, protectFn)
	if err != nil {
		return
	}
	defer remote.Close()

	bidiCopyFlow(clientConn, remote)
}

// relayDirectPeeked dials the destination and writes the peeked bytes
// to it first, then pipes bidirectionally.
func relayDirectPeeked(clientConn net.Conn, clientReader io.Reader, flow flowID, hostname string, blocker adBlockChecker, protectFn func(fd int) bool) {
	remote, err := dialUpstream(flow, hostname, blocker, protectFn)
	if err != nil {
		return
	}
	defer remote.Close()

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(remote, clientReader)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		io.Copy(clientConn, remote)
		if cw, ok := clientConn.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}
