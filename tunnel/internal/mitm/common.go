package mitm

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

const flowDialTimeout = 10 * time.Second

func logf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "[BlockAds/Go] "+format+"\n", args...)
}

func protectedControl(protectFn func(fd int) bool) func(network, address string, c syscall.RawConn) error {
	if protectFn == nil {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) {
			protectFn(int(fd))
		})
	}
}

func bidiCopyFlow(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		io.Copy(b, a)
		if cw, ok := b.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		io.Copy(a, b)
		if cw, ok := a.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()

	wg.Wait()
}

// FlowID identifies a network connection tuple.
type FlowID struct {
	clientIP   net.IP
	clientPort uint16
	serverIP   net.IP
	serverPort uint16
}

type flowID = FlowID

func (f FlowID) ClientIP() net.IP   { return f.clientIP }
func (f FlowID) ClientPort() uint16 { return f.clientPort }
func (f FlowID) ServerIP() net.IP   { return f.serverIP }
func (f FlowID) ServerPort() uint16 { return f.serverPort }

// UIDResolver maps connection flows to Android UID.
type UIDResolver interface {
	ResolveUID(protocol int, srcIP string, srcPort int, destIP string, destPort int) int
}

// TcpFlowHandler handles a TCP connection in userspace stack.
type TcpFlowHandler func(conn adapter.TCPConn)

// UdpFlowHandler handles a UDP packet flow in userspace stack.
type UdpFlowHandler func(conn adapter.UDPConn)

const (
	ProtocolTCP = 6
	ProtocolUDP = 17
	UIDUnknown  = -1
	UIDRoot     = 0
)

func resolveFlowUID(uidr UIDResolver, protocol int, flow flowID) int {
	if uidr == nil {
		return UIDUnknown
	}
	return uidr.ResolveUID(protocol, flow.clientIP.String(), int(flow.clientPort), flow.serverIP.String(), int(flow.serverPort))
}

func tcpFlowID(conn adapter.TCPConn) flowID {
	var f flowID
	if addr, ok := conn.LocalAddr().(*net.TCPAddr); ok {
		f.clientIP = addr.IP
		f.clientPort = uint16(addr.Port)
	}
	if addr, ok := conn.RemoteAddr().(*net.TCPAddr); ok {
		f.serverIP = addr.IP
		f.serverPort = uint16(addr.Port)
	}
	return f
}

func udpFlowID(conn adapter.UDPConn) flowID {
	var f flowID
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		f.clientIP = addr.IP
		f.clientPort = uint16(addr.Port)
	}
	if addr, ok := conn.RemoteAddr().(*net.UDPAddr); ok {
		f.serverIP = addr.IP
		f.serverPort = uint16(addr.Port)
	}
	return f
}

// AdBlockChecker is the interface the MITM handler uses to query the ad-block engine.
type AdBlockChecker interface {
	IsDomainBlocked(host string) bool
	LookupIP(host string) (net.IP, error)
	LogConnection(flow FlowID, protocol int)
}

type adBlockChecker = AdBlockChecker

// requestAcceptsHTML returns true when the request's Accept header
// explicitly includes text/html — i.e., the client is requesting an
// HTML document, not a subresource. Used to decide when to strip
// Accept-Encoding so responses arrive uncompressed for injection.
func requestAcceptsHTML(req *http.Request) bool {
	accept := req.Header.Get("Accept")
	return strings.Contains(strings.ToLower(accept), "text/html")
}

// wrapResponseForInjection prepares an HTML response for in-stream
// <link> injection. It transparently decompresses gzip/deflate bodies
// so the injector can find <head in plaintext, strips
// Content-Security-Policy (which would otherwise block the injected
// <link href="https://local.pwhs.app/...">), and clears framing
// headers so Go re-emits the modified body as chunked plaintext. If
// the body uses an encoding we cannot decode (brotli, compress, or
// anything else), the function returns without modifying the response
// and injection is skipped — the page still renders correctly, just
// without cosmetic filtering.
func wrapResponseForInjection(resp *http.Response) {
	encoding := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))

	var bodyReader io.Reader
	switch encoding {
	case "", "identity":
		bodyReader = resp.Body
	case "gzip":
		gr, err := gzip.NewReader(resp.Body)
		if err != nil {
			return
		}
		bodyReader = gr
	case "deflate":
		// HTTP Content-Encoding: deflate is historically ambiguous: some
		// servers send zlib-wrapped (RFC 1950), others send raw DEFLATE
		// (RFC 1951). Try zlib first (most servers), fall back to raw
		// flate. Buffer the body so we can re-read on fallback.
		raw, err := io.ReadAll(resp.Body)
		if err != nil {
			return
		}
		if zr, err := zlib.NewReader(bytes.NewReader(raw)); err == nil {
			bodyReader = zr
		} else {
			bodyReader = flate.NewReader(bytes.NewReader(raw))
		}
	default:
		return
	}

	resp.Body = io.NopCloser(NewInjectingReader(bodyReader))
	resp.ContentLength = -1
	resp.Header.Del("Content-Length")
	resp.Header.Del("Content-Encoding")
	resp.Header.Del("Transfer-Encoding")
	resp.Header.Del("Content-Security-Policy")
	resp.Header.Del("Content-Security-Policy-Report-Only")
	resp.TransferEncoding = nil
	resp.Uncompressed = true
}

// isLoopbackOrInternal returns true if the hostname is a literal
// loopback or private/internal IP address. Prevents the MITM handler
// from intercepting LAN services (router admin UIs, local printers)
// that typically have self-signed or no TLS certs.
func isLoopbackOrInternal(hostname string) bool {
	lower := strings.ToLower(hostname)
	if lower == "localhost" || lower == "0.0.0.0" || lower == "::" {
		return true
	}

	ip := net.ParseIP(hostname)
	if ip == nil {
		return false
	}

	return ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || isPrivateIP(ip)
}

// isPrivateIP checks if an IP is in RFC 1918 private ranges.
func isPrivateIP(ip net.IP) bool {
	privateRanges := []string{
		"10.0.0.0/8",
		"172.16.0.0/12",
		"192.168.0.0/16",
	}
	for _, cidrStr := range privateRanges {
		_, cidr, _ := net.ParseCIDR(cidrStr)
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}
