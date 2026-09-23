package tunnel

import (
	"encoding/json"
	"fmt"
	"os"
	"syscall"
)

// Start initializes the engine, configures adapters, and begins reading TUN packets.
// This call blocks until Stop() is called.
func (e *Engine) Start(fd int, protector SocketProtector, wgConfigJSON string) {
	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		return
	}
	e.running = true
	e.totalQueries.Store(0)
	e.blockedQueries.Store(0)

	var protectFn func(fd int) bool
	if protector != nil {
		protectFn = func(fd int) bool {
			return protector.Protect(fd)
		}
	}
	e.protectFn = protectFn
	e.resolver = NewResolver(protectFn)
	e.resolver.Configure(ParseProtocol(e.protocol), e.primaryDNS, e.fallbackDNS, e.dohURL)
	e.mu.Unlock()

	dupFd, err := syscall.Dup(fd)
	if err != nil {
		logf("Failed to dup TUN fd %d: %v", fd, err)
		e.running = false
		return
	}
	if err := syscall.SetNonblock(dupFd, true); err != nil {
		logf("Failed to set TUN fd %d non-blocking: %v", dupFd, err)
	}

	tunFile := os.NewFile(uintptr(dupFd), "tun")
	if tunFile == nil {
		logf("Failed to open TUN fd %d", fd)
		e.running = false
		return
	}
	// Publish under e.mu: Stop() closes and clears tunFile under it, and
	// endSession compares against it to tell this session from a newer one.
	e.mu.Lock()
	e.tunFile = tunFile
	e.mu.Unlock()

	logf("Engine started, reading from TUN fd=%d", fd)

	if wgConfigJSON != "" {
		logf("WireGuard config provided, initializing...")
		if err := e.startWireGuard(tunFile, wgConfigJSON); err != nil {
			// Fail closed: with no adapter every non-DNS packet is dropped, so
			// carrying on would report a running engine over a dead tunnel.
			logf("WireGuard init failed, stopping engine: %v", err)
			e.endSession(tunFile)
			return
		}
	} else {
		logf("No WireGuard config, running in DNS-only mode")
	}

	if e.useTcpStack.Load() {
		if err := e.startTcpStackParallel(); err != nil {
			logf("TcpIpStack parallel start failed, falling back to legacy path: %v", err)
		}
	}

	e.interceptor.Run(tunFile)

	// Run also returns on a TUN read error, not just Stop(); clear the
	// running state so IsRunning reflects a dead engine.
	e.endSession(tunFile)
	logf("Engine stopped")
}

// startWireGuard parses the config and brings up the WireGuard adapter on
// tunFile.
func (e *Engine) startWireGuard(tunFile *os.File, wgConfigJSON string) error {
	wgCfg, err := ParseWgConfigJSON(wgConfigJSON)
	if err != nil {
		return fmt.Errorf("config parse: %w", err)
	}
	ipcConfig, err := BuildIpcConfig(wgCfg)
	if err != nil {
		return fmt.Errorf("IPC config build: %w", err)
	}
	wgAdapter, err := NewWgOutbound(newChannelTUN(tunFile), ipcConfig, e.protectFn)
	if err != nil {
		return fmt.Errorf("adapter create: %w", err)
	}
	if err := wgAdapter.Start(); err != nil {
		wgAdapter.Stop()
		return fmt.Errorf("adapter start: %w", err)
	}
	e.router.SetAdapter(wgAdapter)
	logf("WireGuard adapter fully initialized and active")

	if len(wgCfg.Interface.DNS) > 0 && e.splitZones != "" {
		e.applySplitDNS(wgCfg.Interface.DNS[0])
	}
	return nil
}

// endSession marks the engine stopped and closes tunFile, unless Stop() has
// already torn this session down (and a new Start may own the engine).
func (e *Engine) endSession(tunFile *os.File) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if tunFile == nil || e.tunFile != tunFile {
		return
	}
	e.running = false
	e.tunFile = nil
	tunFile.Close()
}

// Stop stops the engine and cleans up all running resources.
func (e *Engine) Stop() {
	e.mu.Lock()

	e.running = false

	if e.interceptor != nil {
		e.interceptor.Stop()
	}

	if e.router != nil {
		e.router.Stop()
	}

	stack := e.tcpStack
	e.tcpStack = nil
	pipe := e.tcpStackPipe.Swap(nil)

	fullDone := e.fullTunnelDone
	e.fullTunnelDone = nil

	if e.tunFile != nil {
		e.tunFile.Close()
		e.tunFile = nil
	}

	oldResolver := e.resolver
	e.resolver = nil

	e.safeSearch.ClearCache()

	for _, t := range e.adTries {
		if t != nil {
			t.Close()
		}
	}
	e.adTries = nil
	e.adTrieIDs = nil

	for _, t := range e.secTries {
		if t != nil {
			t.Close()
		}
	}
	e.secTries = nil
	e.secTrieIDs = nil

	for _, bf := range e.adBlooms {
		if bf != nil {
			bf.Close()
		}
	}
	e.adBlooms = nil

	for _, bf := range e.secBlooms {
		if bf != nil {
			bf.Close()
		}
	}
	e.secBlooms = nil

	oldUdp := e.standaloneUdp
	e.standaloneUdp = nil

	oldTcp := e.standaloneTcp
	e.standaloneTcp = nil

	oldUdp6 := e.standaloneUdp6
	e.standaloneUdp6 = nil

	oldTcp6 := e.standaloneTcp6
	e.standaloneTcp6 = nil

	e.mu.Unlock()

	if oldUdp != nil {
		oldUdp.Shutdown()
	}
	if oldTcp != nil {
		oldTcp.Shutdown()
	}
	if oldUdp6 != nil {
		oldUdp6.Shutdown()
	}
	if oldTcp6 != nil {
		oldTcp6.Shutdown()
	}
	if oldResolver != nil {
		oldResolver.Shutdown()
	}
	if pipe != nil {
		pipe.Close()
	}
	if fullDone != nil {
		close(fullDone)
	}
	if stack != nil {
		stack.Stop()
	}
}

// IsRunning returns whether the engine is currently running.
func (e *Engine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.running
}

// GetStats returns engine statistics as JSON.
func (e *Engine) GetStats() string {
	stats := Stats{
		TotalQueries:   e.totalQueries.Load(),
		BlockedQueries: e.blockedQueries.Load(),
	}
	data, _ := json.Marshal(stats)
	return string(data)
}

func (e *Engine) writeToTUN(data []byte) {
	e.mu.Lock()
	tun := e.tunFile
	e.mu.Unlock()

	if tun == nil {
		return
	}

	if _, err := tun.Write(data); err != nil {
		logf("TUN write error: %v", err)
	}
}
