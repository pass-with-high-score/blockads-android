package tunnel

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"strings"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/mitm"
)

type sniProxyRuleInput struct {
	TargetAddr string   `json:"targetAddr"`
	UIDs       []int    `json:"uids"`
	Domains    []string `json:"domains"`
	Enabled    bool     `json:"enabled"`
}

type compiledSniProxyRule struct {
	targetAddr string
	uids       map[int]bool
	domains    []string
}

// SetSniProxyRules configures per-app / per-SNI TCP 443 redirection rules from a JSON array.
func (e *Engine) SetSniProxyRules(rulesJSON string) {
	if strings.TrimSpace(rulesJSON) == "" {
		e.sniProxyRules.Store(nil)
		return
	}
	var inputs []sniProxyRuleInput
	if err := json.Unmarshal([]byte(rulesJSON), &inputs); err != nil {
		logf("[SniProxy] invalid rules JSON: %v", err)
		return
	}
	compiled := make([]compiledSniProxyRule, 0, len(inputs))
	for _, in := range inputs {
		if !in.Enabled {
			continue
		}
		target := normalizeSniProxyTarget(in.TargetAddr)
		if target == "" {
			continue
		}
		var uidMap map[int]bool
		if len(in.UIDs) > 0 {
			uidMap = make(map[int]bool, len(in.UIDs))
			for _, u := range in.UIDs {
				if u >= 0 {
					uidMap[u] = true
				}
			}
		}
		domains := make([]string, 0, len(in.Domains))
		for _, d := range in.Domains {
			clean := strings.TrimSpace(strings.ToLower(d))
			clean = strings.TrimPrefix(clean, "*.")
			clean = strings.TrimPrefix(clean, ".")
			clean = strings.TrimSuffix(clean, ".")
			if clean != "" {
				domains = append(domains, clean)
			}
		}
		compiled = append(compiled, compiledSniProxyRule{
			targetAddr: target,
			uids:       uidMap,
			domains:    domains,
		})
	}
	if len(compiled) == 0 {
		e.sniProxyRules.Store(nil)
	} else {
		e.sniProxyRules.Store(&compiled)
	}
	logf("[SniProxy] Loaded %d active SNI proxy rules", len(compiled))
}

func normalizeSniProxyTarget(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if _, _, err := net.SplitHostPort(raw); err == nil {
		return raw
	}
	if ip := net.ParseIP(raw); ip != nil {
		return net.JoinHostPort(ip.String(), "443")
	}
	return ""
}

func (e *Engine) shouldDropQuicForSniProxy(uidr UIDResolver, flow flowID) bool {
	ptr := e.sniProxyRules.Load()
	if ptr == nil || len(*ptr) == 0 {
		return false
	}
	rules := *ptr
	for _, r := range rules {
		if len(r.uids) == 0 {
			return true
		}
	}
	uid := resolveFlowUID(uidr, ProtocolUDP, flow)
	if uid == UIDUnknown {
		return false
	}
	for _, r := range rules {
		if r.uids[uid] {
			return true
		}
	}
	return false
}

func (e *Engine) sniProxyCandidatesForFlow(uidr UIDResolver, flow flowID) []compiledSniProxyRule {
	ptr := e.sniProxyRules.Load()
	if ptr == nil || len(*ptr) == 0 {
		return nil
	}
	rules := *ptr
	hasAppScoped := false
	for _, r := range rules {
		if len(r.uids) > 0 {
			hasAppScoped = true
			break
		}
	}
	uid := UIDUnknown
	if hasAppScoped {
		uid = resolveFlowUID(uidr, ProtocolTCP, flow)
	}
	var matched []compiledSniProxyRule
	for _, r := range rules {
		if len(r.uids) == 0 || (uid != UIDUnknown && r.uids[uid]) {
			matched = append(matched, r)
		}
	}
	return matched
}

func matchSniDomain(sni string, domains []string) bool {
	if len(domains) == 0 {
		return true
	}
	sni = strings.TrimSpace(strings.ToLower(strings.TrimSuffix(sni, ".")))
	if sni == "" {
		return false
	}
	for _, d := range domains {
		if sni == d || strings.HasSuffix(sni, "."+d) {
			return true
		}
	}
	return false
}

// prefixConn replays already-peeked bytes before reading from the underlying net.Conn.
type prefixConn struct {
	net.Conn
	reader io.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) {
	return c.reader.Read(p)
}

func peekTLSClientHello(conn net.Conn) ([]byte, error) {
	const maxHelloBytes = 16 * 1024
	buf := make([]byte, maxHelloBytes)
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	defer func() { _ = conn.SetReadDeadline(time.Time{}) }()

	n, err := conn.Read(buf)
	if n == 0 {
		return nil, err
	}
	// If it's a TLS Handshake record (0x16), read until the full ClientHello record arrives.
	if n >= 5 && buf[0] == 0x16 {
		recLen := int(binary.BigEndian.Uint16(buf[3:5]))
		target := 5 + recLen
		if target > maxHelloBytes {
			target = maxHelloBytes
		}
		for n < target && err == nil {
			m, rerr := conn.Read(buf[n:target])
			n += m
			if rerr != nil {
				err = rerr
				break
			}
		}
	}
	return buf[:n], err
}

func trySniProxyRedirect(
	conn net.Conn,
	flow flowID,
	engine *Engine,
	uidr UIDResolver,
	protectFn func(fd int) bool,
) (bool, net.Conn) {
	candidates := engine.sniProxyCandidatesForFlow(uidr, flow)
	if len(candidates) == 0 {
		return false, nil
	}
	peeked, err := peekTLSClientHello(conn)
	if len(peeked) == 0 {
		return true, nil
	}
	if err == nil || len(peeked) >= 5 {
		sni := mitm.ParseClientHelloSNI(peeked)
		for _, rule := range candidates {
			if matchSniDomain(sni, rule.domains) {
				dialer := &net.Dialer{
					Timeout: flowDialTimeout,
					Control: protectedControl(protectFn),
				}
				remote, derr := dialer.Dial("tcp", rule.targetAddr)
				if derr != nil {
					logf("[SniProxy] dial %s for %s failed: %v", rule.targetAddr, sni, derr)
					return true, nil
				}
				defer remote.Close()
				if _, werr := remote.Write(peeked); werr != nil {
					logf("[SniProxy] write ClientHello to %s failed: %v", rule.targetAddr, werr)
					return true, nil
				}
				bidiCopyFlow(conn, remote)
				return true, nil
			}
		}
	}
	return false, &prefixConn{
		Conn:   conn,
		reader: io.MultiReader(bytes.NewReader(peeked), conn),
	}
}
