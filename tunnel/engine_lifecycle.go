package tunnel

import (
	"encoding/json"
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

	e.tunFile = os.NewFile(uintptr(dupFd), "tun")
	if e.tunFile == nil {
		logf("Failed to open TUN fd %d", fd)
		e.running = false
		return
	}

	logf("Engine started, reading from TUN fd=%d", fd)

	if wgConfigJSON != "" {
		logf("WireGuard config provided, initializing...")

		wgCfg, err := ParseWgConfigJSON(wgConfigJSON)
		if err != nil {
			logf("WireGuard config parse error: %v", err)
		} else {
			ipcConfig, err := BuildIpcConfig(wgCfg)
			if err != nil {
				logf("WireGuard IPC config build error: %v", err)
			} else {
				tunDevice := newChannelTUN(e.tunFile)
				wgAdapter, err := NewWgOutbound(tunDevice, ipcConfig, e.protectFn)
				if err != nil {
					logf("WireGuard adapter create error: %v", err)
				} else {
					if err := wgAdapter.Start(); err != nil {
						logf("WireGuard adapter start error: %v", err)
					} else {
						e.router.SetAdapter(wgAdapter)
						logf("WireGuard adapter fully initialized and active")

						if len(wgCfg.Interface.DNS) > 0 && e.splitZones != "" {
							e.applySplitDNS(wgCfg.Interface.DNS[0])
						}
					}
				}
			}
		}
	} else {
		logf("No WireGuard config, running in DNS-only mode")
	}

	if e.useTcpStack.Load() {
		if err := e.startTcpStackParallel(); err != nil {
			logf("TcpIpStack parallel start failed, falling back to legacy path: %v", err)
		}
	}

	e.interceptor.Run(e.tunFile)

	logf("Engine stopped")
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
