package tunnel

import (
	"net"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

// ─────────────────────────────────────────────────────────────────────────────
// UID Resolver — maps a TCP/UDP 5-tuple to the owning Android app UID.
//
// On Android 10+ (API 29+), /proc/net/tcp is SELinux-blocked so the
// legacy parsing approach used by mitm_uid.go cannot work. The
// replacement is the official framework API
// ConnectivityManager.getConnectionOwnerUid(protocol, local, remote),
// which must be called from Kotlin. This file defines the Go side of
// the bridge; the Kotlin side implements the UIDResolver interface and
// is injected via Engine.SetUIDResolver.
//
// UID visibility is the architectural prerequisite for per-app MITM
// scoping — the capability AdGuard uses to MITM only browsers while
// leaving every other app untouched.
// ─────────────────────────────────────────────────────────────────────────────

// IP-protocol numbers as used by the Linux kernel and the Android API.
// Callers of UIDResolver pass one of these as the protocol argument.
const (
	ProtocolTCP = 6
	ProtocolUDP = 17
)

// UIDUnknown is returned by UIDResolver.ResolveUID when the owning UID
// cannot be determined (older Android versions, racing socket teardown,
// etc.). Callers MUST treat UIDUnknown conservatively — e.g., do not
// MITM when UID is unknown.
const UIDUnknown = -1

// UIDResolver maps a TCP/UDP 5-tuple to the owning app's UID.
// All string addresses are in canonical textual form (IPv4 dotted-quad
// or IPv6 bracketless), all ports are the raw 16-bit numbers.
//
// Implementations MUST be safe for concurrent use and MUST return
// quickly — the method is called on the hot path of every new
// connection. Implementations SHOULD return UIDUnknown rather than
// blocking or returning an error when the UID is not available.
type UIDResolver interface {
	// ResolveUID returns the Android UID owning the local endpoint of
	// the given flow, or UIDUnknown if it cannot be determined.
	// protocol is ProtocolTCP or ProtocolUDP.
	ResolveUID(protocol int, localIP string, localPort int, remoteIP string, remotePort int) int
}

// resolveFlowUID is a convenience helper that pulls the 5-tuple out of
// a tun2socks flow ID and calls the registered resolver. It hides the
// gVisor address-type conversion from handler code.
//
// NOTE on direction: tun2socks names the fields from the stack's point
// of view, so the "local" endpoint in TransportEndpointID is the
// server-side (original destination the app was trying to reach) and
// the "remote" endpoint is the client-side (the app's ephemeral
// socket). For the Android ConnectivityManager API we want the app's
// actual socket, which is the tun2socks "remote", looked up against
// the server it was connecting to (tun2socks "local").
func resolveFlowUID(r UIDResolver, protocol int, id flowID) int {
	if r == nil {
		return UIDUnknown
	}
	return r.ResolveUID(
		protocol,
		// App-side socket (source of the outbound connection).
		id.appIP.String(), id.appPort,
		// Server-side endpoint the app was contacting.
		id.serverIP.String(), id.serverPort,
	)
}

// flowID is a decoded 5-tuple in Go-native types, suitable for passing
// across the gomobile boundary and for calling the resolver. It is
// constructed from the gVisor TransportEndpointID carried by each
// tun2socks conn.
type flowID struct {
	appIP      net.IP
	appPort    int
	serverIP   net.IP
	serverPort int
}

// tcpFlowID extracts a flowID from a tun2socks TCPConn.
func tcpFlowID(c adapter.TCPConn) flowID {
	id := c.ID()
	return flowID{
		appIP:      net.IP(id.RemoteAddress.AsSlice()),
		appPort:    int(id.RemotePort),
		serverIP:   net.IP(id.LocalAddress.AsSlice()),
		serverPort: int(id.LocalPort),
	}
}

// udpFlowID extracts a flowID from a tun2socks UDPConn.
func udpFlowID(c adapter.UDPConn) flowID {
	id := c.ID()
	return flowID{
		appIP:      net.IP(id.RemoteAddress.AsSlice()),
		appPort:    int(id.RemotePort),
		serverIP:   net.IP(id.LocalAddress.AsSlice()),
		serverPort: int(id.LocalPort),
	}
}
