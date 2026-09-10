package wireguard

import (
	"os"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
)

const defaultMTU = 1280

// ChannelTUN implements tun.Device using channels for inbound and writing
// directly to the real TUN for outbound (decrypted packets from WireGuard).
type ChannelTUN struct {
	realTUN   *os.File
	inbound   chan []byte
	events    chan tun.Event
	closeOnce sync.Once
	closed    chan struct{}
}

// NewChannelTUN creates a virtual TUN device backed by channels.
func NewChannelTUN(realTUN *os.File) *ChannelTUN {
	t := &ChannelTUN{
		realTUN: realTUN,
		inbound: make(chan []byte, 256),
		events:  make(chan tun.Event, 1),
		closed:  make(chan struct{}),
	}
	t.events <- tun.EventUp
	return t
}

// Inject sends a packet into the virtual TUN for wireguard-go to encrypt.
func (t *ChannelTUN) Inject(packet []byte) {
	select {
	case t.inbound <- packet:
	case <-t.closed:
	default:
	}
}

// Read is called by wireguard-go's receiver goroutine.
func (t *ChannelTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case pkt := <-t.inbound:
		copy(bufs[0][offset:], pkt)
		sizes[0] = len(pkt)
		return 1, nil
	case <-t.closed:
		return 0, os.ErrClosed
	}
}

// Write is called by wireguard-go to inject decrypted packets back into the real TUN.
func (t *ChannelTUN) Write(bufs [][]byte, offset int) (int, error) {
	for i, buf := range bufs {
		packet := buf[offset:]
		if len(packet) == 0 {
			continue
		}
		if _, err := t.realTUN.Write(packet); err != nil {
			return i, err
		}
	}
	return len(bufs), nil
}

// MTU returns the MTU of the TUN device.
func (t *ChannelTUN) MTU() (int, error) {
	return defaultMTU, nil
}

// Name returns the name of the TUN device.
func (t *ChannelTUN) Name() (string, error) {
	return "channel-tun", nil
}

// Events returns a channel of TUN device events.
func (t *ChannelTUN) Events() <-chan tun.Event {
	return t.events
}

// File returns nil.
func (t *ChannelTUN) File() *os.File {
	return nil
}

// Close closes the virtual TUN device.
func (t *ChannelTUN) Close() error {
	t.closeOnce.Do(func() {
		close(t.closed)
		close(t.events)
	})
	return nil
}

// BatchSize returns 1.
func (t *ChannelTUN) BatchSize() int {
	return 1
}
