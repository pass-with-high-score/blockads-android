//go:build linux

package tunnel

import (
	"net"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// streamAdapter claims L4 support, which the router does not implement yet.
type streamAdapter struct {
	fakeAdapter
	stopped int
}

func (a *streamAdapter) SupportsStreams() bool { return true }
func (a *streamAdapter) Stop()                 { a.stopped++ }

func datagramPair(t *testing.T) (a, b *os.File) {
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
	a, b = os.NewFile(uintptr(fds[0]), "a"), os.NewFile(uintptr(fds[1]), "b")
	t.Cleanup(func() { a.Close(); b.Close() })
	return a, b
}

func TestRouter(t *testing.T) {
	r := NewRouter()
	if r.GetAdapter() != nil {
		t.Fatal("fresh router has an adapter")
	}
	r.RoutePacket([]byte{0x45}, 1) // DNS-only: dropped
	r.WriteToTun([]byte("x"))      // no TUN yet: dropped

	l3 := &fakeAdapter{}
	r.SetAdapter(l3)
	if r.GetAdapter() != l3 {
		t.Fatal("GetAdapter did not return the active adapter")
	}
	r.RoutePacket([]byte{1, 2, 3}, 2)
	if len(l3.packets) != 1 || len(l3.packets[0]) != 2 {
		t.Errorf("L3 adapter got %v, want one 2-byte packet", l3.packets)
	}

	l4 := &streamAdapter{}
	r.SetAdapter(l4)
	r.RoutePacket([]byte{1, 2, 3}, 3)
	if len(l4.packets) != 0 {
		t.Error("L4 adapter received a raw packet")
	}
	r.SetAdapter(nil)
	if l4.stopped != 1 {
		t.Errorf("replaced adapter stopped %d times, want 1", l4.stopped)
	}

	tun, peer := datagramPair(t)
	r.SetTunFile(tun)
	r.WriteToTun([]byte("reply"))
	buf := make([]byte, 16)
	_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	if n, err := peer.Read(buf); err != nil || string(buf[:n]) != "reply" {
		t.Errorf("WriteToTun delivered %q, %v", buf[:n], err)
	}
	tun.Close()
	r.WriteToTun([]byte("after close")) // logged, not fatal

	r.SetAdapter(l4)
	r.Stop()
	if r.GetAdapter() != nil || l4.stopped != 2 {
		t.Error("Stop did not stop and clear the adapter")
	}
}

func TestWriteToTUN(t *testing.T) {
	e := NewEngine()
	e.writeToTUN([]byte("dropped")) // no TUN
	tun, peer := datagramPair(t)
	e.tunFile = tun
	e.writeToTUN([]byte("pkt"))
	buf := make([]byte, 8)
	_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	if n, err := peer.Read(buf); err != nil || string(buf[:n]) != "pkt" {
		t.Errorf("writeToTUN delivered %q, %v", buf[:n], err)
	}
	tun.Close()
	e.writeToTUN([]byte("after close")) // logged, not fatal
	e.tunFile = nil
}

// The parallel stack's outbound writer exits when there is no TUN, when the
// pipe closes, and on the first TUN write error.
func TestTcpStackOutboundWriter(t *testing.T) {
	leakCheck(t)
	e := NewEngine()
	p := newPacketPipe()
	e.runTcpStackOutboundWriter(p) // nil TUN: returns at once

	tun, peer := datagramPair(t)
	e.mu.Lock()
	e.tunFile = tun
	e.mu.Unlock()
	done := make(chan struct{})
	go func() { e.runTcpStackOutboundWriter(p); close(done) }()
	_, _ = p.Write([]byte("to app"))
	buf := make([]byte, 16)
	_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	if n, err := peer.Read(buf); err != nil || string(buf[:n]) != "to app" {
		t.Errorf("writer delivered %q, %v", buf[:n], err)
	}
	p.Close()
	<-done

	p2 := newPacketPipe()
	tun.Close()
	done = make(chan struct{})
	go func() { e.runTcpStackOutboundWriter(p2); close(done) }()
	_, _ = p2.Write([]byte("lost"))
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Error("writer kept running after a TUN write error")
	}
	p2.Close()
	e.mu.Lock()
	e.tunFile = nil
	e.mu.Unlock()
}

func TestIsUDP443Packet(t *testing.T) {
	payload := make([]byte, 8)
	v4 := func(dport uint16) []byte {
		return buildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(192, 0, 2, 1), 40000, dport, payload)
	}
	v6 := func(dport uint16) []byte {
		return buildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("2001:db8::1"), 40000, dport, payload)
	}
	withProto := func(p []byte, off int, proto byte) []byte { p[off] = proto; return p }
	cases := []struct {
		name string
		pkt  []byte
		want bool
	}{
		{"ipv4 udp 443", v4(443), true},
		{"ipv4 udp 53", v4(53), false},
		{"ipv4 tcp 443", withProto(v4(443), 9, 6), false},
		{"ipv4 ihl beyond packet", func() []byte { p := v4(443); p[0] = 0x4f; return p }(), false},
		{"ipv6 udp 443", v6(443), true},
		{"ipv6 udp 80", v6(80), false},
		{"ipv6 tcp 443", withProto(v6(443), 6, 6), false},
		{"ipv6 too short", v6(443)[:44], false},
		{"too short", v4(443)[:27], false},
		{"version 0", func() []byte { p := v4(443); p[0] = 0x05; return p }(), false},
	}
	for _, c := range cases {
		if got := isUDP443Packet(c.pkt, len(c.pkt)); got != c.want {
			t.Errorf("%s: isUDP443Packet = %v, want %v", c.name, got, c.want)
		}
	}
}
