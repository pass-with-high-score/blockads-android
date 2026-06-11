package tunnel

import (
	"io"
	"sync"
	"sync/atomic"
)

// ─────────────────────────────────────────────────────────────────────────────
// packetPipe — an io.ReadWriter that bridges the DnsInterceptor to a
// TcpIpStack in Phase C parallel mode.
//
// The existing DnsInterceptor owns the TUN file descriptor and reads
// every packet first (so it can classify DNS vs non-DNS). When the
// userspace TCP/IP stack runs alongside the old code path, non-DNS
// packets need to reach the stack without the stack reading from the
// TUN fd directly (two readers on the same fd would corrupt each other).
// packetPipe is the adapter: non-DNS packets are Push()-ed in, the stack
// reads them via Read(); outbound packets the stack produces are
// Write()-en in, and the interceptor's writer loop drains them via
// Pop() and sends them out through the TUN fd.
//
// The pipe is bounded. Overflow drops the offending packet silently;
// TCP retransmits recover it.
//
// Shutdown: Close() signals every blocked operation to unblock but
// NEVER closes the data channels — senders racing Close() would
// otherwise panic with "send on closed channel". A done channel +
// sync.Once provides a panic-free teardown signal.
// ─────────────────────────────────────────────────────────────────────────────

const (
	// packetQueueDepth is the per-direction queue length. ~matches
	// tun2socks' defaultOutQueueLen and leaves headroom for short
	// traffic bursts without noticeable memory cost (each slot holds a
	// bounded buffer of one MTU).
	packetQueueDepth = 1024
)

// packetPipe is a bidirectional IP-packet queue used only as a
// stack.LinkEndpoint backing store. It implements io.ReadWriter in the
// direction the stack needs (Read → fetch inbound packet, Write →
// accept outbound packet) and exposes Push/Pop helpers for the
// interceptor side.
type packetPipe struct {
	inbound  chan []byte // packets from interceptor heading into the stack
	outbound chan []byte // packets the stack emits back to the app

	done     chan struct{}
	doneOnce sync.Once

	// Diagnostic counters. inboundDropped: pushed but queue full.
	// outboundDropped: written by stack but queue full (means the
	// outbound writer can't keep up with TUN.Write).
	inboundDropped  atomic.Int64
	outboundDropped atomic.Int64
	outboundWritten atomic.Int64
}

func newPacketPipe() *packetPipe {
	return &packetPipe{
		inbound:  make(chan []byte, packetQueueDepth),
		outbound: make(chan []byte, packetQueueDepth),
		done:     make(chan struct{}),
	}
}

// Read is called by the gVisor iobased endpoint to fetch the next
// inbound IP packet. Blocks until a packet is available or the pipe
// is closed.
func (p *packetPipe) Read(buf []byte) (int, error) {
	select {
	case pkt := <-p.inbound:
		return copy(buf, pkt), nil
	case <-p.done:
		return 0, io.EOF
	}
}

// Write is called by the stack when it emits an outbound IP packet.
// Never blocks — drops on overflow. Returning len(buf) preserves the
// io.Writer contract even on drop because packet loss is a normal
// condition in network stacks (TCP retransmits cover it).
func (p *packetPipe) Write(buf []byte) (int, error) {
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
			logf("packetPipe: outbound write #%d (size=%d)", c, len(buf))
		}
	case <-p.done:
	default:
		// queue full; drop.
		c := p.outboundDropped.Add(1)
		if c <= 3 {
			logf("packetPipe: outbound DROPPED #%d (queue full, size=%d)", c, len(buf))
		}
	}
	return len(buf), nil
}

// Push enqueues an inbound packet from the interceptor. Thread-safe,
// never panics on teardown. On overflow the packet is dropped
// silently.
func (p *packetPipe) Push(pkt []byte) {
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
		// queue full; drop.
		c := p.inboundDropped.Add(1)
		if c <= 3 {
			logf("packetPipe: inbound DROPPED #%d (queue full, size=%d)", c, len(pkt))
		}
	}
}

// Pop returns the next outbound packet produced by the stack, blocking
// until one is available or the pipe is closed. Returns nil on close.
func (p *packetPipe) Pop() []byte {
	select {
	case pkt := <-p.outbound:
		return pkt
	case <-p.done:
		return nil
	}
}

// Close unblocks every pending Read/Pop and causes every subsequent
// Push/Write to drop silently. Safe to call multiple times.
func (p *packetPipe) Close() {
	p.doneOnce.Do(func() { close(p.done) })
}
