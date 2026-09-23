//go:build linux

package tunnel

import (
	"bytes"
	"encoding/base64"
	"net/netip"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/testnet"
	"golang.org/x/sys/unix"
)

// Start (DNS-only / legacy interceptor path) over a socketpair TUN: DNS is
// answered through the upstream, a custom-blocked domain gets 0.0.0.0, and
// nothing outlives Stop.
func TestStartStopOverSocketpair(t *testing.T) {
	leakCheck(t)
	e, up := newNetEngine(t)
	h := runEngine(t, e, false)

	if !e.IsRunning() {
		t.Fatal("IsRunning = false while Start is serving")
	}
	if got := answerIPs(h.mustQuery(t, "ok.example.com", dns.TypeA)); len(got) != 1 || got[0] != "192.0.2.1" {
		t.Errorf("allowed answer = %v, want [192.0.2.1]", got)
	}
	if got := answerIPs(h.mustQuery(t, "ads.example.com", dns.TypeA)); len(got) != 1 || got[0] != "0.0.0.0" {
		t.Errorf("blocked answer = %v, want [0.0.0.0]", got)
	}
	if up.count.Load() != 1 {
		t.Errorf("upstream saw %d queries, want 1 (the blocked one must not be forwarded)", up.count.Load())
	}
	waitUntil(t, "stats", func() bool { return e.totalQueries.Load() == 2 })
	if got := e.GetStats(); got != `{"total":2,"blocked":1}` {
		t.Errorf("GetStats = %s", got)
	}

	h.stop()
	if e.IsRunning() {
		t.Error("IsRunning = true after Stop")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.tunFile != nil || e.resolver != nil {
		t.Error("Stop left tunFile or resolver set")
	}
}

func TestStopBeforeStartAndDoubleStop(t *testing.T) {
	leakCheck(t)
	e, _ := newNetEngine(t)
	e.Stop()
	e.Stop()
	if e.IsRunning() {
		t.Fatal("IsRunning after Stop on a fresh engine")
	}

	h := runEngine(t, e, false)
	h.mustQuery(t, "ok.example.com", dns.TypeA)
	h.stop()
	e.Stop()
	h.stop()
}

// Stop then Start again on the same Engine: the second session gets a fresh
// resolver and TUN, and stats restart from zero.
func TestRestart(t *testing.T) {
	leakCheck(t)
	for _, full := range []bool{false, true, false} {
		e, _ := newNetEngine(t)
		for i := 0; i < 2; i++ {
			h := runEngine(t, e, full)
			h.mustQuery(t, "ok.example.com", dns.TypeA)
			waitUntil(t, "stats", func() bool { return e.totalQueries.Load() == 1 })
			h.stop()
		}
	}
}

func TestStartWhileRunningReturnsImmediately(t *testing.T) {
	e, _ := newNetEngine(t)
	h := runEngine(t, e, false)
	for _, start := range []func(){
		func() { e.Start(-1, nil, "") },
		func() { e.StartFull(-1, nil) },
	} {
		done := make(chan struct{})
		go func() { start(); close(done) }()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("second Start blocked while the engine was running")
		}
	}
	h.mustQuery(t, "ok.example.com", dns.TypeA)
}

func TestStartBadFdFails(t *testing.T) {
	e, _ := newNetEngine(t)
	e.Start(-1, nil, "")
	if e.IsRunning() {
		t.Error("Start with an invalid fd left the engine running")
	}
	e.StartFull(-1, nil)
	if e.IsRunning() {
		t.Error("StartFull with an invalid fd left the engine running")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.fullTunnelDone != nil {
		t.Error("StartFull failure left fullTunnelDone set")
	}
}

// Non-DNS packets go to the router's adapter; DirectOutbound drops them.
func TestStartRoutesNonDNSToAdapter(t *testing.T) {
	e, _ := newNetEngine(t)
	adapter := &fakeAdapter{}
	e.SetOutboundAdapter(adapter)
	h := runEngine(t, e, false)

	dst := netip.MustParseAddrPort("10.9.9.9:9999")
	h.sendUDP(t, dst, "ping")
	var pkt []byte
	waitUntil(t, "adapter packet", func() bool {
		adapter.mu.Lock()
		defer adapter.mu.Unlock()
		if len(adapter.packets) > 0 {
			pkt = adapter.packets[0]
		}
		return pkt != nil
	})
	if !bytes.Equal(pkt[16:20], dst.Addr().AsSlice()) || !bytes.HasSuffix(pkt, []byte("ping")) {
		t.Errorf("routed packet % x does not carry the datagram to %s", pkt, dst)
	}

	direct := NewDirectOutbound()
	if direct.Name() != "direct" || direct.SupportsStreams() || direct.Start() != nil {
		t.Error("DirectOutbound identity")
	}
	e.SetOutboundAdapter(direct)
	h.sendUDP(t, dst, "ping")
	h.mustQuery(t, "ok.example.com", dns.TypeA) // the loop is still alive
}

// Flag set after Start: the interceptor sees the flag but no pipe exists, so
// non-DNS packets are dropped instead of reaching the router.
func TestStartStackFlagWithoutPipeDrops(t *testing.T) {
	e, _ := newNetEngine(t)
	adapter := &fakeAdapter{}
	e.SetOutboundAdapter(adapter)
	h := runEngine(t, e, false)
	e.SetUseTcpStack(true)

	h.sendUDP(t, netip.MustParseAddrPort("10.9.9.9:9999"), "ping")
	waitUntil(t, "nil-pipe drop", func() bool { return e.interceptor.stackPipeNilDrops.Load() > 0 })
	adapter.mu.Lock()
	defer adapter.mu.Unlock()
	if len(adapter.packets) != 0 {
		t.Errorf("adapter got %d packets with the stack flag on", len(adapter.packets))
	}
}

// SetUseTcpStack(true) before Start: non-DNS traffic goes through the
// parallel gVisor stack (packet pipe + outbound writer) and is relayed.
func TestStartTcpStackRelaysTCP(t *testing.T) {
	leakCheck(t)
	ip := testnet.HostIPv4(t)
	echo := testnet.TCPEcho(t, ip)
	e, _ := newNetEngine(t)
	e.SetUseTcpStack(true)
	if !e.IsUsingTcpStack() {
		t.Fatal("IsUsingTcpStack = false")
	}
	h := runEngine(t, e, false)

	if got, err := h.echoTCP(t, echo, "hello stack"); err != nil || got != "hello stack" {
		t.Fatalf("TCP echo = %q, %v", got, err)
	}
	if got := answerIPs(h.mustQuery(t, "ok.example.com", dns.TypeA)); len(got) != 1 {
		t.Errorf("DNS still answered by the interceptor: %v", got)
	}
	if e.interceptor.stackPacketsPushed.Load() == 0 {
		t.Error("no packets pushed into the stack pipe")
	}
	// :853 is closed before any dial, so the client sees EOF right away.
	if _, err := h.echoTCP(t, netip.AddrPortFrom(ip, 853), "x"); err == nil {
		t.Error("DoT connection was relayed")
	}
	// A refused upstream dial ends the flow.
	if _, err := h.echoTCP(t, netip.AddrPortFrom(ip, closedPort(t, ip)), "x"); err == nil {
		t.Error("flow to a closed port echoed")
	}
	h.stop()
	if e.tcpStackPipe.Load() != nil {
		t.Error("Stop left the stack pipe set")
	}
}

func TestStartTcpStackRelaysUDP(t *testing.T) {
	ip := testnet.HostIPv4(t)
	echo := testnet.UDPEcho(t, ip)
	e, _ := newNetEngine(t)
	e.SetUseTcpStack(true)
	h := runEngine(t, e, false)
	if got, err := h.echoUDP(t, echo, "hello udp"); err != nil || got != "hello udp" {
		t.Fatalf("UDP echo = %q, %v", got, err)
	}
}

// With stack MITM initialized, Start registers the MITM TCP handler and the
// QUIC-suppressing UDP handler; plain TCP to a private address still relays.
func TestStartTcpStackWithMitm(t *testing.T) {
	ip := testnet.HostIPv4(t)
	if !ip.IsPrivate() {
		t.Skip("MITM passthrough to the echo server needs a private host address")
	}
	echo := testnet.TCPEcho(t, ip)
	e, _ := newNetEngine(t)
	if pem := e.StartStackMitm(t.TempDir()); pem == "" {
		t.Fatal("StartStackMitm returned no CA")
	}
	e.SetUseTcpStack(true)
	h := runEngine(t, e, false)
	if got, err := h.echoTCP(t, echo, "via mitm"); err != nil || got != "via mitm" {
		t.Fatalf("TCP echo through MITM handler = %q, %v", got, err)
	}
}

// A WireGuard config makes Start bring up the WireGuard adapter (and apply
// split DNS); an unparsable one falls back to DNS-only.
func TestStartWireGuardConfig(t *testing.T) {
	key := func(b byte) string { return base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{b}, 32)) }
	valid := `{"interfaceConfig":{"privateKey":"` + key(1) + `","address":["10.0.0.2/32"],"listenPort":0,"dns":["10.64.0.1"]},` +
		`"peers":[{"publicKey":"` + key(2) + `","endpoint":"127.0.0.1:9","allowedIPs":["10.64.0.0/16"]}]}`
	for name, cfg := range map[string]string{
		"valid":    valid,
		"bad json": "{",
		"bad key":  `{"interfaceConfig":{"privateKey":"!!"},"peers":[]}`,
	} {
		t.Run(name, func(t *testing.T) {
			e, _ := newNetEngine(t)
			e.SetSplitDNSZones("corp")
			fd, cf, err := testnet.Socketpair()
			if err != nil {
				t.Fatal(err)
			}
			defer cf.Close()
			defer unix.Close(fd)
			done := make(chan struct{})
			go func() { e.Start(fd, nil, cfg); close(done) }()
			waitUntil(t, "interceptor running", e.interceptor.IsRunning)
			adapter := e.GetRouter().GetAdapter()
			if (adapter != nil) != (name == "valid") {
				t.Errorf("adapter = %T", adapter)
			}
			if adapter != nil && adapter.Name() != "wireguard" {
				t.Errorf("adapter name = %q", adapter.Name())
			}
			e.Stop()
			<-done
			if e.GetRouter().GetAdapter() != nil {
				t.Error("Stop left the adapter active")
			}
		})
	}
}
