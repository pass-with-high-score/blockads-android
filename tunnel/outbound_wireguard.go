package tunnel

import (
	"fmt"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// ─────────────────────────────────────────────────────────────────────────────
// WgOutbound — WireGuard L3 OutboundAdapter implementation.
//
// Architecture:
//   DnsInterceptor (sole TUN reader)
//     ├── DNS → adblock engine
//     └── non-DNS → WgOutbound.HandlePacket()
//                      └── channelTUN.Inject(packet)
//                            └── wireguard-go reads ← encrypts → UDP out
//                                wireguard-go receives ← decrypts → channelTUN.Write()
//                                  └── writes to real TUN fd
//
// Key: WireGuard does NOT share the real TUN fd. It uses a virtual
// channelTUN. The DnsInterceptor is the sole reader of the real TUN.
// ─────────────────────────────────────────────────────────────────────────────

// WgOutbound implements OutboundAdapter for WireGuard.
type WgOutbound struct {
	mu     sync.Mutex
	dev    *device.Device
	tunDev tun.Device
	chTun  *channelTUN // reference for Inject()

	running bool
}

// NewWgOutbound creates a new WireGuard outbound adapter from a channelTUN
// and an UAPI IPC config string. If protectFn is non-nil, the WireGuard
// outbound UDP socket is protected from the VPN routing loop (required
// when WireGuard is layered inside our own VpnService TUN).
// The caller must call Start() to bring the WireGuard device online.
func NewWgOutbound(tunDev tun.Device, ipcConfig string, protectFn func(fd int) bool) (*WgOutbound, error) {
	// Create wireguard-go logger
	logger := device.NewLogger(device.LogLevelVerbose, "[BlockAds/WG] ")

	// Pick a bind that protects its UDP sockets so encrypted WG packets
	// reach the underlying network instead of looping back into our TUN.
	var bind conn.Bind
	if protectFn != nil {
		bind = newProtectedBind(protectFn)
	} else {
		bind = conn.NewDefaultBind()
	}

	// Create wireguard-go device
	dev := device.NewDevice(tunDev, bind, logger)

	// Apply IPC config
	if err := dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet failed: %v", err)
	}

	// Type-assert to get channelTUN reference for Inject()
	chTun, _ := tunDev.(*channelTUN)

	return &WgOutbound{
		dev:    dev,
		tunDev: tunDev,
		chTun:  chTun,
	}, nil
}

// Name returns the adapter name.
func (w *WgOutbound) Name() string {
	return "wireguard"
}

// Start brings the WireGuard device online.
func (w *WgOutbound) Start() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.running {
		return fmt.Errorf("WgOutbound already running")
	}

	if err := w.dev.Up(); err != nil {
		return fmt.Errorf("device.Up failed: %v", err)
	}

	w.running = true
	logf("WgOutbound: started")

	// Monitor device lifecycle in background
	go func() {
		w.dev.Wait()
		logf("WgOutbound: device closed")
		w.mu.Lock()
		w.running = false
		w.mu.Unlock()
	}()

	return nil
}

// Stop shuts down the WireGuard device.
func (w *WgOutbound) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()

	if !w.running || w.dev == nil {
		return
	}

	w.dev.Close()
	w.dev = nil
	w.tunDev = nil
	w.chTun = nil
	w.running = false
	logf("WgOutbound: stopped")
}

// HandlePacket injects a non-DNS packet into WireGuard for encryption.
// Called by the DnsInterceptor via Router.RoutePacket() for all non-DNS traffic.
// The packet is sent through the channelTUN → wireguard-go encrypts it → UDP.
func (w *WgOutbound) HandlePacket(packet []byte, length int) {
	if w.chTun != nil {
		w.chTun.Inject(packet[:length])
	}
}

// SupportsStreams returns false — WireGuard is a Layer 3 protocol.
func (w *WgOutbound) SupportsStreams() bool {
	return false
}

// IsRunning returns whether the adapter is active.
func (w *WgOutbound) IsRunning() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.running
}
