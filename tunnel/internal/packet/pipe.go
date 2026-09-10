package packet

import (
	"fmt"
	"io"
	"os"
	"sync"
	"sync/atomic"
)

const (
	packetQueueDepth = 1024
)

// PacketPipe is a bidirectional IP-packet queue used only as a
// stack.LinkEndpoint backing store.
type PacketPipe struct {
	inbound  chan []byte
	outbound chan []byte

	done     chan struct{}
	doneOnce sync.Once

	inboundDropped  atomic.Int64
	outboundDropped atomic.Int64
	outboundWritten atomic.Int64
}

// NewPacketPipe creates a new PacketPipe instance.
func NewPacketPipe() *PacketPipe {
	return &PacketPipe{
		inbound:  make(chan []byte, packetQueueDepth),
		outbound: make(chan []byte, packetQueueDepth),
		done:     make(chan struct{}),
	}
}

// Read is called by the gVisor iobased endpoint to fetch the next inbound IP packet.
func (p *PacketPipe) Read(buf []byte) (int, error) {
	select {
	case pkt := <-p.inbound:
		return copy(buf, pkt), nil
	case <-p.done:
		return 0, io.EOF
	}
}

// Write is called by the stack when it emits an outbound IP packet.
func (p *PacketPipe) Write(buf []byte) (int, error) {
	pkt := make([]byte, len(buf))
	copy(pkt, buf)
	select {
	case <-p.done:
		return len(buf), nil
	default:
	}
	select {
	case p.outbound <- pkt:
		c := p.outboundWritten.Add(1)
		if c <= 5 {
			fmt.Fprintf(os.Stderr, "[BlockAds/Go] PacketPipe: outbound write #%d (size=%d)\n", c, len(buf))
		}
	case <-p.done:
	default:
		c := p.outboundDropped.Add(1)
		if c <= 3 {
			fmt.Fprintf(os.Stderr, "[BlockAds/Go] PacketPipe: outbound DROPPED #%d (queue full, size=%d)\n", c, len(buf))
		}
	}
	return len(buf), nil
}

// Push enqueues an inbound packet from the interceptor.
func (p *PacketPipe) Push(pkt []byte) {
	select {
	case <-p.done:
		return
	default:
	}
	buf := make([]byte, len(pkt))
	copy(buf, pkt)
	select {
	case p.inbound <- buf:
	case <-p.done:
	default:
		c := p.inboundDropped.Add(1)
		if c <= 3 {
			fmt.Fprintf(os.Stderr, "[BlockAds/Go] PacketPipe: inbound DROPPED #%d (queue full, size=%d)\n", c, len(pkt))
		}
	}
}

// Pop returns the next outbound packet produced by the stack.
func (p *PacketPipe) Pop() []byte {
	select {
	case pkt := <-p.outbound:
		return pkt
	case <-p.done:
		return nil
	}
}

// Close unblocks pending Read/Pop and causes future Push/Write to drop.
func (p *PacketPipe) Close() {
	p.doneOnce.Do(func() { close(p.done) })
}
