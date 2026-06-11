package tunnel

import (
	"encoding/binary"
	"os"
	"sync"
	"sync/atomic"
)

// ─────────────────────────────────────────────────────────────────────────────
// DnsInterceptor — Sits in front of the Router and intercepts DNS packets.
//
// Pipeline:
//   [TUN FD]
//     → DnsInterceptor.Run()
//       → Port 53?  YES → handleDNSQuery() (adblock + resolve + write TUN)
//                    NO  → Router.RoutePacket()
//
// This is the extracted packet loop from the original engine.go, now modular.
// ─────────────────────────────────────────────────────────────────────────────

// DnsInterceptor reads packets from TUN and separates DNS traffic from
// non-DNS traffic. DNS queries are handled by the adblock engine; everything
// else is dispatched to the Router's active OutboundAdapter.
type DnsInterceptor struct {
	engine  *Engine
	router  *Router
	tunFile *os.File

	mu      sync.Mutex
	running bool

	// Stats (shared with Engine)
	totalQueries   *atomic.Int64
	blockedQueries *atomic.Int64

	// Diagnostic counters for the stack-bridge hot path. Only the
	// first few hits are logged; counts persist for later inspection.
	stackPacketsPushed atomic.Int64
	stackPipeNilDrops  atomic.Int64
}

// NewDnsInterceptor creates a new DnsInterceptor.
func NewDnsInterceptor(engine *Engine, router *Router) *DnsInterceptor {
	return &DnsInterceptor{
		engine:         engine,
		router:         router,
		totalQueries:   &engine.totalQueries,
		blockedQueries: &engine.blockedQueries,
	}
}

// Run starts reading packets from the TUN file descriptor.
// DNS queries (dest port 53) go to the adblock engine.
// All other packets go to the Router for outbound dispatch.
// This method blocks until Stop() is called or a read error occurs.
func (i *DnsInterceptor) Run(tunFile *os.File) {
	i.mu.Lock()
	i.tunFile = tunFile
	i.running = true
	i.mu.Unlock()

	// Also give the router access to TUN for writing responses
	i.router.SetTunFile(tunFile)

	logf("DnsInterceptor: started, reading from TUN")

	buf := make([]byte, 32767) // MAX_PACKET_SIZE
	for i.IsRunning() {
		n, err := tunFile.Read(buf)
		if err != nil {
			if i.IsRunning() {
				logf("DnsInterceptor: TUN read error: %v", err)
			}
			break
		}
		if n <= 0 {
			continue
		}

		// Classify the packet: DNS (port 53) or non-DNS?
		if isDNSPacket(buf, n) {
			// DNS path → parse and handle via adblock engine
			queryInfo := ParseTUNPacket(buf, n)
			if queryInfo != nil {
				go i.engine.handleDNSQuery(queryInfo)
			}
		} else if i.engine.IsUsingTcpStack() {
			// Hand non-DNS packets to the userspace TCP/IP stack
			// instead of the legacy Router. atomic.Pointer load avoids
			// a data race with Stop() which clears the pipe under
			// e.mu; the pipe's own Close is panic-free so a stale
			// pointer + Push is safe.
			pipe := i.engine.tcpStackPipe.Load()
			if pipe != nil {
				pipe.Push(buf[:n])
				if c := i.stackPacketsPushed.Add(1); c <= 10 {
					logf("DnsInterceptor: pushed packet #%d to stack (size=%d, version=%d)", c, n, buf[0]>>4)
				}
			} else {
				if c := i.stackPipeNilDrops.Add(1); c <= 5 {
					logf("DnsInterceptor: stack flag on but pipe nil (drop #%d, size=%d)", c, n)
				}
			}
		} else {
			// Non-DNS path → route to active outbound adapter
			// Make a copy because buf will be reused on next iteration
			pkt := make([]byte, n)
			copy(pkt, buf[:n])
			i.router.RoutePacket(pkt, n)
		}
	}

	logf("DnsInterceptor: stopped")
}

// Stop signals the interceptor to stop reading.
func (i *DnsInterceptor) Stop() {
	i.mu.Lock()
	defer i.mu.Unlock()
	i.running = false
}

// IsRunning returns whether the interceptor is active.
func (i *DnsInterceptor) IsRunning() bool {
	i.mu.Lock()
	defer i.mu.Unlock()
	return i.running
}

// ─────────────────────────────────────────────────────────────────────────────
// isDNSPacket — Fast check: is this a UDP packet with destination port 53?
// ─────────────────────────────────────────────────────────────────────────────

// isUDP443Packet reports whether the packet is UDP to destination port 443
// (QUIC / HTTP-3). Uses the same fast IP-header parsing as isDNSPacket.
func isUDP443Packet(packet []byte, length int) bool {
	if length < ipv4HeaderSize+udpHeaderSize {
		return false
	}
	version := packet[0] >> 4
	switch version {
	case 4:
		if packet[9] != 17 {
			return false
		}
		ihl := int(packet[0]&0x0F) * 4
		if length < ihl+udpHeaderSize {
			return false
		}
		destPort := binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])
		return destPort == 443
	case 6:
		if length < ipv6HeaderSize+udpHeaderSize {
			return false
		}
		if packet[6] != 17 {
			return false
		}
		destPort := binary.BigEndian.Uint16(packet[ipv6HeaderSize+2 : ipv6HeaderSize+4])
		return destPort == 443
	default:
		return false
	}
}

func isDNSPacket(packet []byte, length int) bool {
	if length < ipv4HeaderSize+udpHeaderSize {
		return false
	}

	version := packet[0] >> 4

	switch version {
	case 4:
		// IPv4: check protocol is UDP (17)
		if packet[9] != 17 {
			return false
		}
		ihl := int(packet[0]&0x0F) * 4
		if length < ihl+udpHeaderSize {
			return false
		}
		destPort := binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])
		return destPort == 53

	case 6:
		// IPv6: check next header is UDP (17)
		if length < ipv6HeaderSize+udpHeaderSize {
			return false
		}
		if packet[6] != 17 {
			return false
		}
		destPort := binary.BigEndian.Uint16(packet[ipv6HeaderSize+2 : ipv6HeaderSize+4])
		return destPort == 53

	default:
		return false
	}
}