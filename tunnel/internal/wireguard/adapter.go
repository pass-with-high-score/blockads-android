package wireguard

import (
	"fmt"
	"os"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// WgOutbound implements OutboundAdapter for WireGuard.
type WgOutbound struct {
	mu     sync.Mutex
	dev    *device.Device
	tunDev tun.Device
	chTun  *ChannelTUN

	running bool
}

// NewWgOutbound creates a new WireGuard outbound adapter from a virtual channel TUN
// and a UAPI IPC config string.
func NewWgOutbound(tunDev tun.Device, ipcConfig string, protectFn func(fd int) bool) (*WgOutbound, error) {
	logger := device.NewLogger(device.LogLevelVerbose, "[BlockAds/WG] ")

	var bind conn.Bind
	if protectFn != nil {
		bind = newProtectedBind(protectFn)
	} else {
		bind = conn.NewDefaultBind()
	}

	dev := device.NewDevice(tunDev, bind, logger)

	if err := dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet failed: %v", err)
	}

	chTun, _ := tunDev.(*ChannelTUN)

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
	fmt.Fprintln(os.Stderr, "[BlockAds/Go] WgOutbound: started")

	go func() {
		w.dev.Wait()
		fmt.Fprintln(os.Stderr, "[BlockAds/Go] WgOutbound: device closed")
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
	fmt.Fprintln(os.Stderr, "[BlockAds/Go] WgOutbound: stopped")
}

// HandlePacket injects a non-DNS packet into WireGuard for encryption.
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
