//go:build linux

package testnet

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"sync"
	"testing"
)

// HostIPv4 returns a non-loopback IPv4 address assigned to this host.
//
// The engine's gVisor stack drops martian packets addressed to 127.0.0.0/8,
// so flows the engine should relay to a local upstream must target an
// address that is both routable in gVisor and served by the host kernel.
// Tests skip when the host has no such address.
func HostIPv4(t testing.TB) netip.Addr {
	t.Helper()
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Skipf("list interface addresses: %v", err)
	}
	for _, a := range addrs {
		n, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip, ok := netip.AddrFromSlice(n.IP)
		if !ok {
			continue
		}
		ip = ip.Unmap()
		if ip.Is4() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
			return ip
		}
	}
	t.Skip("host has no non-loopback IPv4 address")
	return netip.Addr{}
}

// TCPEcho listens on ip:0 and echoes every connection until the test ends.
func TCPEcho(t testing.TB, ip netip.Addr) netip.AddrPort {
	t.Helper()
	ln, err := net.Listen("tcp", netip.AddrPortFrom(ip, 0).String())
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	conns := map[net.Conn]struct{}{}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns[c] = struct{}{}
			mu.Unlock()
			wg.Add(1)
			go func() {
				defer wg.Done()
				_, _ = io.Copy(c, c)
				c.Close()
			}()
		}
	}()
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		for c := range conns {
			c.Close()
		}
		mu.Unlock()
		wg.Wait()
	})
	return netip.MustParseAddrPort(ln.Addr().String())
}

// UDPEcho listens on ip:0 and echoes every datagram until the test ends.
func UDPEcho(t testing.TB, ip netip.Addr) netip.AddrPort {
	t.Helper()
	pc, err := net.ListenPacket("udp", netip.AddrPortFrom(ip, 0).String())
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 65536)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				continue
			}
			_, _ = pc.WriteTo(buf[:n], from)
		}
	}()
	t.Cleanup(func() {
		pc.Close()
		<-done
	})
	return netip.MustParseAddrPort(pc.LocalAddr().String())
}

// OpenFDs counts this process's open file descriptors.
func OpenFDs(t testing.TB) int {
	t.Helper()
	ents, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Skipf("read /proc/self/fd: %v", err)
	}
	return len(ents)
}

// Goroutines returns the current goroutine count after a GC pass, so
// goroutines that have just exited are no longer counted.
func Goroutines() int {
	runtime.GC()
	return runtime.NumGoroutine()
}
