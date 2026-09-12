package tunnel

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"

	"github.com/nqmgaming/blockads-tunnel/internal/mitm"
)

const defaultTunMTU = 1500

var localAssetSynthIP = net.IPv4(198, 51, 100, 1)

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// StartStackMitm initialises MITM state for the userspace TCP/IP
// stack path. Call this in addition to SetUseTcpStack(true) to have
// the stack handle HTTPS filtering. The persistent Root CA is loaded
// from (or generated in) certDir; the returned PEM must be installed
// on-device as a user CA for browsers to accept intercepted TLS.
//
// Returns empty string on error (check logs).
//
// Kotlin usage:
//
//	adapter.setUseTcpStack(true)
//	val caPem = engine.startStackMitm(context.filesDir.absolutePath)
//	// Write caPem to storage and prompt the user to install it.
//	engine.setMitmAllowedUIDs(browserUids.joinToString(","))
func (e *Engine) StartStackMitm(certDir string) string {
	certMgr, err := NewCertManager(certDir)
	if err != nil {
		logf("StartStackMitm: cert manager init failed: %v", err)
		return ""
	}
	certMgr.WarmLocalAssetCert()

	e.mu.Lock()
	e.stackCertMgr = certMgr
	if e.stackMitmFilter == nil {
		e.stackMitmFilter = NewMitmFilter()
	}
	filter := e.stackMitmFilter
	e.certDir = certDir
	e.mu.Unlock()

	// Persist the auto-blacklist alongside the CA so cert-pinned / EV
	// domains discovered in one session are remembered in the next —
	// otherwise every pinned site breaks once per app launch.
	filter.LoadPersistentBlacklist(filepath.Join(certDir, "mitm_blacklist.txt"))

	return certMgr.GetCACertPEM()
}

// StopStackMitm clears stack-mode MITM state. The stack itself keeps
// running on the direct-dial handler after this call.
func (e *Engine) StopStackMitm() {
	e.mu.Lock()
	e.stackCertMgr = nil
	e.stackMitmFilter = nil
	e.mu.Unlock()
}

// SetUseTcpStack toggles whether non-DNS packets are routed into the
// userspace TCP/IP stack. When true (recommended for HTTPS filtering),
// the DnsInterceptor redirects non-DNS packets into the stack for
// per-flow processing (direct dial or MITM depending on whether
// StartStackMitm was called). When false, non-DNS packets go through
// the Router → outbound adapter path for DNS-only or WireGuard use.
//
// The flag must be set before Engine.Start. Runtime toggling after
// Start is not supported.
func (e *Engine) SetUseTcpStack(enabled bool) {
	e.useTcpStack.Store(enabled)
}

// IsUsingTcpStack reports the current flag value.
func (e *Engine) IsUsingTcpStack() bool { return e.useTcpStack.Load() }

// SetUIDResolver registers the Kotlin-implemented resolver used to look
// up the owning app UID for each TCP/UDP flow terminated by the
// userspace TCP/IP stack. Typically wired once at VPN start.
//
// Passing nil clears the resolver; flows will then report UIDUnknown.
func (e *Engine) SetUIDResolver(r UIDResolver) {
	e.mu.Lock()
	e.uidResolver = r
	stack := e.tcpStack
	e.mu.Unlock()

	if stack != nil {
		stack.SetUIDResolver(r)
	}
}

// startTcpStackParallel brings up the userspace TCP/IP stack on a
// packet pipe that the DnsInterceptor will feed from. The legacy
// mitmProxy and Router remain in place — only non-DNS packets diverge
// into the stack for Phase C testing. Called with e.mu unlocked
// (sets up state visible to other goroutines atomically via fields
// protected by locks where necessary).
func (e *Engine) startTcpStackParallel() error {
	pipe := newPacketPipe()
	stack := NewTcpIpStack()

	e.mu.Lock()
	uidr := e.uidResolver
	protectFn := e.protectFn
	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	mtu := uint32(defaultTunMTU)
	e.tcpStack = stack
	e.mu.Unlock()
	e.tcpStackPipe.Store(pipe)

	stack.SetUIDResolver(uidr)
	if certMgr != nil && filter != nil {
		// Phase D path — MITM handler applies the full filtering flow.
		stack.SetTcpHandler(newMitmTcpHandler(certMgr, filter, e, uidr, protectFn))
		// Drop browser QUIC so HTTP/3 can't bypass the TCP-TLS MITM.
		stack.SetUdpHandler(newMitmUdpHandler(filter, uidr, newProtectedUdpHandler(uidr, protectFn)))
		logf("TcpIpStack: MITM handler registered (TCP + QUIC-suppressing UDP)")
	} else {
		// Phase C default — direct-dial passthrough, no MITM.
		stack.SetTcpHandler(newProtectedTcpHandler(uidr, protectFn))
		stack.SetUdpHandler(newProtectedUdpHandler(uidr, protectFn))
	}

	if err := stack.Start(pipe, mtu); err != nil {
		e.mu.Lock()
		e.tcpStack = nil
		e.mu.Unlock()
		e.tcpStackPipe.Store(nil)
		pipe.Close()
		return fmt.Errorf("stack start: %w", err)
	}

	// Drain outbound packets from the stack and write them back to the
	// real TUN so responses reach the originating app. Runs until the
	// pipe is closed (Stop → pipe.Close → Pop returns nil).
	go e.runTcpStackOutboundWriter(pipe)

	logf("TcpIpStack: parallel path started (flag=on)")
	return nil
}

// runTcpStackOutboundWriter drains outbound packets emitted by the
// stack and forwards them to the real TUN device. The TUN file is
// captured once at start so the hot path doesn't acquire e.mu on
// every packet; if Stop closes the TUN, tun.Write returns an error
// and the goroutine exits cleanly.
func (e *Engine) runTcpStackOutboundWriter(p *packetPipe) {
	e.mu.Lock()
	tun := e.tunFile
	e.mu.Unlock()
	if tun == nil {
		logf("TcpIpStack: outbound writer started with nil TUN, exiting")
		return
	}

	var written, dropped int64
	defer func() {
		logf("TcpIpStack: outbound writer stopped (written=%d dropped=%d)", written, dropped)
	}()

	for {
		pkt := p.Pop()
		if pkt == nil {
			return
		}
		if _, err := tun.Write(pkt); err != nil {
			dropped++
			logf("TcpIpStack: TUN write error after %d packets: %v", written, err)
			return
		}
		written++
	}
}

// IsMitmActive returns true when the HTTPS MITM filter is active
// (stack handler registered with cert manager + filter).
func (e *Engine) IsMitmActive() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.stackCertMgr != nil
}

// GetMitmCACert returns the PEM-encoded Root CA certificate. Reads
// from disk at certDir; stack MITM uses the same ca.crt file.
func (e *Engine) GetMitmCACert(certDir string) string {
	e.mu.Lock()
	certMgr := e.stackCertMgr
	e.mu.Unlock()

	if certMgr != nil {
		return certMgr.GetCACertPEM()
	}

	certPath := filepath.Join(certDir, caCertFile)
	if !fileExists(certPath) {
		return ""
	}
	data, err := os.ReadFile(certPath)
	if err != nil {
		logf("Failed to read persistent CA cert: %v", err)
		return ""
	}
	return string(data)
}

// SetMitmAllowedUIDs sets the list of Android app UIDs that are allowed
// for MITM interception (typically browser UIDs).
//
// uidsCsv: comma-separated UIDs, e.g., "10145,10200,10201"
// gomobile doesn't support []int, so we use a CSV string.
//
// Kotlin usage:
//
//	val browserUids = listOf(chromeUid, firefoxUid, braveUid)
//	engine.setMitmAllowedUIDs(browserUids.joinToString(","))
func (e *Engine) SetMitmAllowedUIDs(uidsCsv string) {
	e.mu.Lock()
	stackFilter := e.stackMitmFilter
	e.mu.Unlock()

	if stackFilter == nil {
		logf("MITM: SetAllowedUIDs called but stack MITM is not active")
		return
	}

	var uids []int
	for _, s := range strings.Split(uidsCsv, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		uid := 0
		for _, c := range s {
			if c >= '0' && c <= '9' {
				uid = uid*10 + int(c-'0')
			}
		}
		if uid > 0 {
			uids = append(uids, uid)
		}
	}
	stackFilter.SetAllowedUIDs(uids)
}

// SetExtraPassthroughSuffixes loads the runtime passthrough list onto
// the stack-mode MITM filter. Call this after StartStackMitm. The
// input is a newline-separated string (the raw contents of
// assets/https_passthrough.txt); blank lines and # / // comments are
// ignored. gomobile doesn't bridge []string cleanly, so we use a
// single string and split inside Go.
//
// Kotlin usage:
//
//	val raw = context.assets.open("https_passthrough.txt").bufferedReader().readText()
//	engine.setExtraPassthroughSuffixes(raw)
func (e *Engine) SetExtraPassthroughSuffixes(content string) {
	e.mu.Lock()
	filter := e.stackMitmFilter
	e.mu.Unlock()
	if filter == nil {
		logf("SetExtraPassthroughSuffixes: stack MITM not active")
		return
	}
	filter.SetExtraPassthroughSuffixes(strings.Split(content, "\n"))
}

// SetScriptletRules parses +js() rules from the supplied filter-list
// content and rebuilds the in-memory store. Lines that aren't +js()
// rules are ignored, so the same content that drives cosmetic CSS
// can be passed verbatim. Pass an empty string to clear the store.
//
// Kotlin usage:
//
//	val raw = filterRepo.allFilterTextConcatenated()
//	engine.setScriptletRules(raw)
func (e *Engine) SetScriptletRules(content string) {
	if content == "" {
		SetScriptletStore(nil)
		return
	}
	rules := parseScriptletRules(content)
	if len(rules) == 0 {
		SetScriptletStore(nil)
		logf("Scriptlet rules: parsed 0 rules from %d-byte input", len(content))
		return
	}
	store := buildScriptletStore(rules)
	SetScriptletStore(store)
	logf("Scriptlet rules: parsed %d rules (%d global, %d host-bound)",
		len(rules), len(store.All), len(store.ByHost))
}

// SetCosmeticCSS sets the minified CSS string to inject into HTML responses
// for cosmetic ad hiding (e.g., EasyList `##.ad-banner` rules).
//
// Kotlin usage:
//
//	val css = CosmeticRuleParser.parseToCss(lines)
//	engine.setCosmeticCSS(css)
func (e *Engine) SetCosmeticCSS(css string) {
	SetCosmeticCSS(css)
}

// ── AdBlockChecker implementation ────────────────────────────────────────────
// IsDomainBlocked satisfies the AdBlockChecker interface used by the MITM
// proxy.  It replicates the exact same blocking pipeline used for DNS queries:
//   CustomRule(allow override) → SecurityTrie → AdTrie → Kotlin DomainChecker.
func (e *Engine) IsDomainBlocked(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	if host == "" {
		return false
	}

	if e.isDoHDomain(host) {
		return true
	}

	// ── Custom rule allow/block override ──
	if e.domainChecker != nil {
		override := e.domainChecker.HasCustomRule(host)
		if override == 0 {
			return false // explicitly allowed
		}
		if override == 1 {
			return true // explicitly blocked
		}
	}

	// ── Security trie (Bloom pre-filter → Mmap Trie) ──
	e.mu.Lock()
	secBlooms := e.secBlooms
	secTries := e.secTries
	adBlooms := e.adBlooms
	adTries := e.adTries
	e.mu.Unlock()

	for i, secTrie := range secTries {
		if secTrie == nil {
			continue
		}
		var secBloom *BloomFilter
		if i < len(secBlooms) {
			secBloom = secBlooms[i]
		}
		if secBloom == nil || secBloom.MightContainDomainOrParent(host) {
			if secTrie.ContainsOrParent(host) {
				return true
			}
		}
	}

	// ── Ad trie (Bloom pre-filter → Mmap Trie) ──
	for i, adTrie := range adTries {
		if adTrie == nil {
			continue
		}
		var adBloom *BloomFilter
		if i < len(adBlooms) {
			adBloom = adBlooms[i]
		}
		if adBloom == nil || adBloom.MightContainDomainOrParent(host) {
			if adTrie.ContainsOrParent(host) {
				return true
			}
		}
	}

	// ── Kotlin DomainChecker fallback ──
	if e.domainChecker != nil {
		if e.domainChecker.IsBlocked(host) {
			return true
		}
	}

	return false
}

// LookupIP resolves a hostname to an IP using the engine's internal resolver.
func (e *Engine) LookupIP(host string) (net.IP, error) {
	return e.lookupIP(host)
}

// LogConnection logs a flow for connection monitoring.
func (e *Engine) LogConnection(flow mitm.FlowID, protocol int) {
	e.logConnection(flowID{
		appIP:      flow.ClientIP(),
		appPort:    int(flow.ClientPort()),
		serverIP:   flow.ServerIP(),
		serverPort: int(flow.ServerPort()),
	}, protocol)
}
