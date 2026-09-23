//go:build linux

package tunnel

import (
	"context"
	"net/netip"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/testnet"
)

// StartFull answers DNS on any address through ServeDNS: allowed names are
// resolved upstream, blocked ones get 0.0.0.0, over IPv4 and IPv6.
func TestStartFullDNS(t *testing.T) {
	leakCheck(t)
	resetConnLogState()
	e, up := newNetEngine(t)
	h := runEngine(t, e, true)
	uidr := &countingUIDResolver{uid: UIDUnknown}
	e.SetUIDResolver(uidr) // reaches the running stack

	if got := answerIPs(h.mustQuery(t, "ok.example.com", dns.TypeA)); len(got) != 1 || got[0] != "192.0.2.1" {
		t.Errorf("allowed answer = %v, want [192.0.2.1]", got)
	}
	if got := answerIPs(h.mustQuery(t, "ads.example.com", dns.TypeA)); len(got) != 1 || got[0] != "0.0.0.0" {
		t.Errorf("blocked answer = %v, want [0.0.0.0]", got)
	}
	r := h.query(t, netip.MustParseAddr("fd00::1"), "ads.example.com", dns.TypeA, 3*time.Second)
	if r == nil || len(answerIPs(r)) != 1 || answerIPs(r)[0] != "0.0.0.0" {
		t.Errorf("IPv6 blocked answer = %v", r)
	}
	if up.count.Load() != 1 {
		t.Errorf("upstream saw %d queries, want 1", up.count.Load())
	}
	if got := e.GetStats(); got != `{"total":3,"blocked":2}` {
		t.Errorf("GetStats = %s", got)
	}
	if uidr.calls.Load() == 0 {
		t.Error("UID resolver set while running was never consulted")
	}
}

// A datagram that is not DNS is skipped and the flow keeps serving queries.
func TestStartFullDNSSkipsGarbage(t *testing.T) {
	e, _ := newNetEngine(t)
	h := runEngine(t, e, true)
	conn, err := h.c.DialUDP(netip.AddrPortFrom(fakeDNSIP, 53))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	m := new(dns.Msg)
	m.SetQuestion("ok.example.com.", dns.TypeA)
	q, _ := m.Pack()
	if _, err := conn.Write(q); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 512)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("no reply after a garbage datagram: %v", err)
	}
	var r dns.Msg
	if err := r.Unpack(buf[:n]); err != nil || r.Id != m.Id {
		t.Fatalf("reply = %v, %v", r, err)
	}
}

// TCP and UDP to a non-DNS destination are relayed through protected
// sockets, with or without the MITM layer.
func TestStartFullRelay(t *testing.T) {
	ip := testnet.HostIPv4(t)
	tcpEcho := testnet.TCPEcho(t, ip)
	udpEcho := testnet.UDPEcho(t, ip)
	for _, mitm := range []bool{false, true} {
		name := "passthrough"
		if mitm {
			name = "mitm"
		}
		t.Run(name, func(t *testing.T) {
			if mitm && !ip.IsPrivate() {
				t.Skip("MITM passthrough to the echo server needs a private host address")
			}
			resetConnLogState()
			e, _ := newNetEngine(t)
			log := &entryLog{}
			e.SetLogCallback(log)
			e.SetConnLogEnabled(true)
			if mitm {
				e.StartStackMitm(t.TempDir())
			}
			p := &countingProtector{}
			h := runEngineProtected(t, e, true, p)
			if got, err := h.echoTCP(t, tcpEcho, "tcp through full"); err != nil || got != "tcp through full" {
				t.Errorf("TCP echo = %q, %v", got, err)
			}
			if got, err := h.echoUDP(t, udpEcho, "udp through full"); err != nil || got != "udp through full" {
				t.Errorf("UDP echo = %q, %v", got, err)
			}
			if p.calls.Load() < 2 {
				t.Errorf("Protect called %d times, want one per relayed flow", p.calls.Load())
			}
			for _, want := range []string{"TCP " + tcpEcho.String(), "UDP " + udpEcho.String()} {
				if !waitFor(t, func() bool { return hasLogDomain(log, want) }) {
					t.Errorf("no connection log entry %q in %v", want, log.all())
				}
			}
		})
	}
}

// Flows the passthrough TCP handler must refuse close at once instead of
// being dialed: DoT, the VPN's own DNS address, a closed upstream port, and
// known DoH IPs while DoH blocking is on.
func TestStartFullTCPGates(t *testing.T) {
	leakCheck(t)
	ip := testnet.HostIPv4(t)
	e, _ := newNetEngine(t)
	e.SetDoHBlocklist("dns.google\n")
	p := &countingProtector{}
	h := runEngineProtected(t, e, true, p)
	for _, dst := range []netip.AddrPort{
		netip.AddrPortFrom(fakeDNSIP, 853),
		netip.MustParseAddrPort("100.64.100.1:80"),
		netip.MustParseAddrPort("[fd00::1]:80"),
		netip.MustParseAddrPort("8.8.8.8:443"),
	} {
		start := time.Now()
		if got, err := h.echoTCP(t, dst, "x"); err == nil {
			t.Errorf("%s: flow echoed %q", dst, got)
		}
		if d := time.Since(start); d > 2*time.Second {
			t.Errorf("%s: took %v to close; the flow was dialed", dst, d)
		}
	}
	if n := p.calls.Load(); n != 0 {
		t.Errorf("gated flows dialed %d sockets", n)
	}
	if _, err := h.echoTCP(t, netip.AddrPortFrom(ip, closedPort(t, ip)), "x"); err == nil {
		t.Error("flow to a closed port echoed")
	}
}

// udpFlowsSeen returns how many UDP flows the full-tunnel stack dispatched.
func udpFlowsSeen(e *Engine) int64 {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.tcpStack == nil {
		return 0
	}
	return e.tcpStack.UdpFlowCount()
}

// quicFlowDropped sends datagrams on one client socket to dst:443 until the
// stack has seen two flows for it (the handler closed the first) or 2s pass.
func quicFlowDropped(t *testing.T, h *netHarness, dst netip.Addr) bool {
	t.Helper()
	conn, err := h.c.DialUDP(netip.AddrPortFrom(dst, 443))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	base := udpFlowsSeen(h.e)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := conn.Write([]byte("quic")); err != nil {
			t.Fatal(err)
		}
		time.Sleep(20 * time.Millisecond)
		if udpFlowsSeen(h.e)-base >= 2 {
			return true
		}
	}
	return false
}

// Browser QUIC (UDP 443) is dropped only when HTTP/3 filtering is on, and,
// with MITM UIDs configured, only for allowed or unknown UIDs.
func TestStartFullQUIC(t *testing.T) {
	ip := testnet.HostIPv4(t)
	tests := []struct {
		name      string
		filterH3  bool
		mitmUIDs  string
		uid       int
		wantDrop  bool
		wantDials bool
	}{
		{"flag off relays", false, "", 0, false, true},
		{"flag on drops", true, "", 0, true, false},
		{"flag on allowed uid drops", true, "10100", 10100, true, false},
		{"flag on unknown uid drops", true, "10100", UIDUnknown, true, false},
		{"flag on other uid relays", true, "10100", 10200, false, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e, _ := newNetEngine(t)
			e.SetFilterHttp3(tt.filterH3)
			if tt.mitmUIDs != "" {
				e.StartStackMitm(t.TempDir())
				e.SetMitmAllowedUIDs(tt.mitmUIDs)
				e.SetUIDResolver(&countingUIDResolver{uid: tt.uid})
			}
			p := &countingProtector{}
			h := runEngineProtected(t, e, true, p)
			if tt.wantDials {
				h.sendUDP(t, netip.AddrPortFrom(ip, 443), "quic")
				waitUntil(t, "relay dial", func() bool { return p.calls.Load() > 0 })
				return
			}
			if !quicFlowDropped(t, h, ip) {
				t.Error("QUIC flow was not closed")
			}
			if p.calls.Load() != 0 {
				t.Errorf("dropped QUIC flow dialed %d sockets", p.calls.Load())
			}
		})
	}
}

// Stop unblocks StartFull even while flows are open, and the engine can then
// be started again.
func TestStartFullStopWithOpenFlows(t *testing.T) {
	ip := testnet.HostIPv4(t)
	echo := testnet.TCPEcho(t, ip)
	e, _ := newNetEngine(t)
	h := runEngine(t, e, true)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := h.c.DialTCP(ctx, echo)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if got, err := roundTrip(conn, "open"); err != nil || got != "open" {
		t.Fatalf("echo = %q, %v", got, err)
	}
	h.stop()
	if e.IsRunning() {
		t.Fatal("still running after Stop")
	}
	h2 := runEngine(t, e, true)
	h2.mustQuery(t, "ok.example.com", dns.TypeA)
}

func hasLogDomain(l *entryLog, domain string) bool {
	for _, e := range l.all() {
		if e.domain == domain {
			return true
		}
	}
	return false
}
