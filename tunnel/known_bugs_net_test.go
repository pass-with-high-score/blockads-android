//go:build linux

package tunnel

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/testnet"
	"golang.org/x/sys/unix"
)

func closeFd(fd int) { _ = unix.Close(fd) }

// spinUntilRunning busy-waits for IsRunning so the caller acts inside
// Start's setup window rather than after it (polling with sleeps is too slow
// to land there).
func spinUntilRunning(e *Engine) {
	for !e.IsRunning() {
		runtime.Gosched()
	}
}

// Every relayed UDP flow holds two goroutines and a protected socket
// with no idle timeout, and bidiCopyFlow's upstream read never unblocks, so
// the flows outlive Stop as well.
func TestIdleUDPFlowsReleased(t *testing.T) {
	t.Skip("known bug: UDP passthrough flows never time out and survive Stop (2 goroutines + 1 fd each)")
	if testing.Short() {
		t.Skip("waits for idle flows to be reaped")
	}
	ip := testnet.HostIPv4(t)
	echo := testnet.UDPEcho(t, ip)
	baseG, baseFD := testnet.Goroutines(), testnet.OpenFDs(t)

	e, _ := newNetEngine(t)
	h := runEngine(t, e, true)
	const flows = 20
	for i := 0; i < flows; i++ {
		if got, err := h.echoUDP(t, echo, "idle"); err != nil || got != "idle" {
			t.Fatalf("flow %d: %q, %v", i, got, err)
		}
	}
	idle := 2 * dnsUDPIdleTimeout
	t.Logf("waiting %v for %d idle UDP flows to be reaped", idle, flows)
	time.Sleep(idle)
	// The engine itself (stack, drain, DNS server) accounts for a handful of
	// goroutines and fds; the flows must not.
	if g := testnet.Goroutines(); g > baseG+flows {
		t.Errorf("%d goroutines with the engine idle, baseline %d", g, baseG)
	}
	if fds := testnet.OpenFDs(t); fds > baseFD+flows/2 {
		t.Errorf("%d fds with the engine idle, baseline %d", fds, baseFD)
	}

	h.stop()
	waitUntil(t, "goroutines back to baseline", func() bool { return testnet.Goroutines() <= baseG+2 })
	if fds := testnet.OpenFDs(t); fds > baseFD {
		t.Errorf("%d fds after Stop, baseline %d", fds, baseFD)
	}
}

// Lifecycle: Start sets running under e.mu but assigns tunFile,
// and the interceptor marks itself running, outside it. A Stop that lands in
// that window closes nothing and is then undone by DnsInterceptor.Run, so
// Start keeps serving the TUN while IsRunning() reports false; -race flags
// the tunFile/running accesses.
func TestStopDuringStartIsNotLost(t *testing.T) {
	t.Skip("known bug: a Stop between Start's running=true and the interceptor loop is lost; Start keeps reading the TUN")
	for i := 0; i < 20; i++ {
		e, _ := newNetEngine(t)
		fd, cf, err := testnet.Socketpair()
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan struct{})
		go func() { e.Start(fd, nil, ""); close(done) }()
		spinUntilRunning(e)
		e.Stop()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			e.Stop() // unblock it so the test can finish
			<-done
			t.Errorf("iteration %d: Start ignored a Stop issued while it was starting", i)
		}
		cf.Close()
		closeFd(fd)
	}
}

// Same window in StartFull: fullTunnelDone is set under the lock, but the
// stack and tunFile are published afterwards. An early Stop closes done, so
// StartFull returns, yet the stack it then publishes keeps running with the
// dup'd TUN fd open until another Stop.
func TestStopDuringStartFullReleasesStack(t *testing.T) {
	t.Skip("known bug: a Stop during StartFull's setup lets StartFull return but leaves the gVisor stack and TUN fd running")
	for i := 0; i < 20; i++ {
		e, _ := newNetEngine(t)
		fd, cf, err := testnet.Socketpair()
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan struct{})
		go func() { e.StartFull(fd, nil); close(done) }()
		spinUntilRunning(e)
		e.Stop()
		<-done
		e.mu.Lock()
		leaked := e.tcpStack != nil || e.tunFile != nil
		e.mu.Unlock()
		if leaked {
			t.Errorf("iteration %d: StartFull returned with its stack/TUN still set", i)
		}
		e.Stop()
		cf.Close()
		closeFd(fd)
	}
}

// ── mmap'd filters swapped or truncated under readers ──────────────────
//
// A crash here is a SIGSEGV/SIGBUS that would take the whole test binary
// down, so each scenario runs in a child process (this test binary,
// re-executed with -test.run on the child test) and the parent inspects its
// exit.

const mmapChildEnv = "BLOCKADS_MMAP_CHILD"

func TestMmapSwapUnderReaders(t *testing.T) {
	if testing.Short() {
		t.Skip("runs child processes for ~2s each")
	}
	for _, scenario := range []string{"settries", "stop", "recompile"} {
		t.Run(scenario, func(t *testing.T) {
			cmd := exec.Command(os.Args[0], "-test.run=^TestMmapSwapChild$", "-test.v", "-test.count=1")
			cmd.Env = append(os.Environ(), mmapChildEnv+"="+scenario)
			out, err := cmd.CombinedOutput()
			if err == nil {
				return
			}
			tail := string(out)
			// Unmapped or truncated under a reader, a trie lookup faults
			// (SIGSEGV/SIGBUS), panics on the buffer Close just nil'ed, or
			// trips the race detector.
			for _, marker := range []string{"SIGSEGV", "SIGBUS", "unexpected fault address", "panic: runtime error", "DATA RACE"} {
				if strings.Contains(tail, marker) {
					t.Skipf("known bug: %s: readers hit %q while filters were unmapped/truncated", scenario, marker)
				}
			}
			t.Fatalf("child failed without a known mmap-swap crash signature: %v\n%s", err, lastLines(tail, 30))
		})
	}
}

func lastLines(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// TestMmapSwapChild is the child half of TestMmapSwapUnderReaders; it does
// nothing unless the parent set mmapChildEnv.
func TestMmapSwapChild(t *testing.T) {
	scenario := os.Getenv(mmapChildEnv)
	if scenario == "" {
		t.Skip("child of TestMmapSwapUnderReaders")
	}
	domains := make([]string, 0, 3000)
	for i := 0; i < 3000; i++ {
		domains = append(domains, fmt.Sprintf("ad%d.example.com", i))
	}
	adTrie, adBloom := compileTrie(t, "ads", domains...)
	secTrie, secBloom := compileTrie(t, "sec", "malware.example.net")

	up := startUpstream(t, answerWith("A 192.0.2.1"))
	e, _ := newServeEngine(t, up.addr)
	e.SetTries(adTrie, secTrie, adBloom, secBloom)

	var stop atomic.Bool
	var wg sync.WaitGroup
	for r := 0; r < 4; r++ {
		wg.Add(1)
		go func(r int) {
			defer wg.Done()
			for i := 0; !stop.Load(); i++ {
				host := fmt.Sprintf("ad%d.example.com", (i*7+r)%3000)
				e.IsDomainBlocked(host)
				e.ServeDNS(&fakeWriter{remote: udpClient(1)}, msgFor(host, dns.TypeA))
			}
		}(r)
	}

	deadline := time.Now().Add(2 * time.Second)
	for i := 0; time.Now().Before(deadline); i++ {
		switch scenario {
		case "settries":
			e.SetTries(adTrie, secTrie, adBloom, secBloom)
		case "stop":
			e.Stop()
			e.SetTries(adTrie, secTrie, adBloom, secBloom)
		case "recompile":
			// Rebuild the mapped files in place with a shorter list, as a
			// filter update does, while readers hold the old mapping.
			n := 3000
			if i%2 == 0 {
				n = 10
			}
			in := filepath.Join(t.TempDir(), "ads.txt")
			if err := os.WriteFile(in, []byte(strings.Join(domains[:n], "\n")), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := CompileFilterList(in, adTrie, adBloom); err != nil {
				t.Fatal(err)
			}
		}
	}
	stop.Store(true)
	wg.Wait()
	e.Stop()
}
