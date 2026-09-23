package tunnel

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"golang.org/x/sys/unix"
)

// ── Fakes for the Kotlin-side interfaces ─────────────────────────────────────

// fakeChecker implements DomainChecker from static maps.
type fakeChecker struct {
	custom  map[string]int // 1 block, 0 allow; missing = -1
	reason  map[string]string
	blocked map[string]bool
}

func (f *fakeChecker) IsBlocked(d string) bool        { return f.blocked[d] }
func (f *fakeChecker) GetBlockReason(d string) string { return f.reason[d] }
func (f *fakeChecker) HasCustomRule(d string) int {
	if v, ok := f.custom[d]; ok {
		return v
	}
	return -1
}

type fakeFirewall struct{ block map[string]bool }

func (f *fakeFirewall) ShouldBlock(app string) bool { return f.block[app] }

// fakeAppResolver answers every lookup with name and records the arguments.
type fakeAppResolver struct {
	name     string
	mu       sync.Mutex
	lastPort int
	lastIP   []byte
}

func (f *fakeAppResolver) ResolveApp(sourcePort int, sourceIP, destIP []byte, destPort int) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lastPort = sourcePort
	f.lastIP = append([]byte(nil), sourceIP...)
	return f.name
}

type logEntry struct {
	domain     string
	blocked    bool
	qtype      int
	app        string
	resolvedIP string
	blockedBy  string
}

// entryLog records every OnDNSQuery call in full.
type entryLog struct {
	mu      sync.Mutex
	entries []logEntry
}

func (l *entryLog) OnDNSQuery(domain string, blocked bool, queryType int, _ int64, appName, resolvedIP, blockedBy string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.entries = append(l.entries, logEntry{domain, blocked, queryType, appName, resolvedIP, blockedBy})
}

func (l *entryLog) all() []logEntry {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]logEntry(nil), l.entries...)
}

// fakeAdapter is an L3 OutboundAdapter that records routed packets.
type fakeAdapter struct {
	mu      sync.Mutex
	packets [][]byte
}

func (a *fakeAdapter) Name() string          { return "fake" }
func (a *fakeAdapter) Start() error          { return nil }
func (a *fakeAdapter) Stop()                 {}
func (a *fakeAdapter) SupportsStreams() bool { return false }
func (a *fakeAdapter) HandlePacket(p []byte, n int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.packets = append(a.packets, append([]byte(nil), p[:n]...))
}

// ── Local upstream DNS ───────────────────────────────────────────────────────

// upstream is a loopback miekg server. reply may be swapped per test.
type upstream struct {
	addr  string
	count atomic.Int64
	reply atomic.Value // func(*dns.Msg) *dns.Msg
}

func startUpstream(t *testing.T, reply func(*dns.Msg) *dns.Msg) *upstream {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	u := &upstream{addr: pc.LocalAddr().String()}
	u.reply.Store(reply)
	started := make(chan struct{})
	srv := &dns.Server{
		PacketConn:        pc,
		NotifyStartedFunc: func() { close(started) },
		Handler: dns.HandlerFunc(func(w dns.ResponseWriter, r *dns.Msg) {
			u.count.Add(1)
			if m := u.reply.Load().(func(*dns.Msg) *dns.Msg)(r); m != nil {
				_ = w.WriteMsg(m)
			}
		}),
	}
	go func() { _ = srv.ActivateAndServe() }()
	<-started
	t.Cleanup(func() { _ = srv.Shutdown() })
	return u
}

// answerWith replies with one record per rdata string, e.g. "A 192.0.2.1".
func answerWith(rdata ...string) func(*dns.Msg) *dns.Msg {
	return func(r *dns.Msg) *dns.Msg {
		m := new(dns.Msg)
		m.SetReply(r)
		for _, rd := range rdata {
			rr, err := dns.NewRR(r.Question[0].Name + " 60 IN " + rd)
			if err == nil {
				m.Answer = append(m.Answer, rr)
			}
		}
		return m
	}
}

// deadUpstream fails before sending (invalid port), so tests never wait on a
// timeout and never fall back to a real public resolver.
const deadUpstream = "127.0.0.1:99999"

// ── Engine harness ───────────────────────────────────────────────────────────

// tunHarness is an Engine whose TUN is one end of a datagram socketpair, so
// every writeToTUN call arrives as exactly one readable packet.
type tunHarness struct {
	e    *Engine
	peer *os.File
	log  *entryLog
}

func newTunHarness(t *testing.T, upstreamAddr string) *tunHarness {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, fd := range fds {
		if err := unix.SetNonblock(fd, true); err != nil {
			t.Fatal(err)
		}
	}
	tun := os.NewFile(uintptr(fds[0]), "tun")
	peer := os.NewFile(uintptr(fds[1]), "peer")

	e := NewEngine()
	log := &entryLog{}
	e.SetLogCallback(log)
	e.SetDNS("PLAIN", upstreamAddr, upstreamAddr, "")
	r := NewResolver(nil)
	r.Configure(ProtocolPlain, upstreamAddr, upstreamAddr, "")
	e.mu.Lock()
	e.running = true
	e.tunFile = tun
	e.resolver = r
	e.mu.Unlock()

	t.Cleanup(func() {
		e.Stop()
		peer.Close()
	})
	return &tunHarness{e: e, peer: peer, log: log}
}

// queryInfo builds the DNSQueryInfo the interceptor would hand over for an
// IPv4 query from 10.0.0.2:40000 to 10.0.0.1:53.
func queryInfo(t *testing.T, name string, qtype uint16) *DNSQueryInfo {
	t.Helper()
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(name), qtype)
	m.Id = 0x4242
	payload, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	pkt := buildIPv4UDPPacket(net.IPv4(10, 0, 0, 2).To4(), net.IPv4(10, 0, 0, 1).To4(), 40000, 53, payload)
	info := ParseTUNPacket(pkt, len(pkt))
	if info == nil {
		t.Fatalf("ParseTUNPacket rejected query for %q", name)
	}
	return info
}

// read returns the DNS message of the next packet written to the TUN, or nil
// if none arrives within wait.
func (h *tunHarness) read(t *testing.T, wait time.Duration) *dns.Msg {
	t.Helper()
	_ = h.peer.SetReadDeadline(time.Now().Add(wait))
	buf := make([]byte, 65536)
	n, err := h.peer.Read(buf)
	if err != nil {
		return nil
	}
	pkt := buf[:n]
	hdr := ipv4HeaderSize
	if pkt[0]>>4 == 6 {
		hdr = ipv6HeaderSize
	} else {
		hdr = int(pkt[0]&0x0f) * 4
	}
	var m dns.Msg
	if err := m.Unpack(pkt[hdr+udpHeaderSize:]); err != nil {
		t.Fatalf("TUN packet does not carry a DNS message: %v", err)
	}
	return &m
}

func (h *tunHarness) mustRead(t *testing.T) *dns.Msg {
	t.Helper()
	m := h.read(t, 2*time.Second)
	if m == nil {
		t.Fatal("no packet written to TUN")
	}
	return m
}

// answerIPs lists the A/AAAA addresses in m.
func answerIPs(m *dns.Msg) []string {
	var out []string
	for _, rr := range m.Answer {
		switch r := rr.(type) {
		case *dns.A:
			out = append(out, r.A.String())
		case *dns.AAAA:
			out = append(out, r.AAAA.String())
		}
	}
	return out
}

// compileTrie writes a filter list and compiles it to <dir>/<id>.trie and
// <dir>/<id>.bloom, returning both paths.
func compileTrie(t *testing.T, id string, domains ...string) (triePath, bloomPath string) {
	t.Helper()
	dir := t.TempDir()
	in := filepath.Join(dir, id+".txt")
	if err := os.WriteFile(in, []byte(strings.Join(domains, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	triePath = filepath.Join(dir, id+".trie")
	bloomPath = filepath.Join(dir, id+".bloom")
	if _, err := CompileFilterList(in, triePath, bloomPath); err != nil {
		t.Fatalf("CompileFilterList: %v", err)
	}
	return triePath, bloomPath
}

// fakeWriter is a dns.ResponseWriter that keeps the last message after
// packing it, as the real server would.
type fakeWriter struct {
	remote net.Addr
	msg    *dns.Msg
	writes int
}

func (w *fakeWriter) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 53}
}
func (w *fakeWriter) RemoteAddr() net.Addr { return w.remote }
func (w *fakeWriter) WriteMsg(m *dns.Msg) error {
	if _, err := m.Pack(); err != nil {
		return err
	}
	w.msg = m
	w.writes++
	return nil
}
func (w *fakeWriter) Write(b []byte) (int, error) { return len(b), nil }
func (w *fakeWriter) Close() error                { return nil }
func (w *fakeWriter) TsigStatus() error           { return nil }
func (w *fakeWriter) TsigTimersOnly(bool)         {}
func (w *fakeWriter) Hijack()                     {}

// otherAddr is a net.Addr of neither UDP nor TCP type.
type otherAddr string

func (a otherAddr) Network() string { return "other" }
func (a otherAddr) String() string  { return string(a) }

func msgFor(name string, qtype uint16) *dns.Msg {
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(name), qtype)
	m.Id = 0x1234
	return m
}
