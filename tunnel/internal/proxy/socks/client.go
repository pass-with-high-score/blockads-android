package socks

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
)

const handshakeTimeout = 10 * time.Second

// Client handles outbound connections through a SOCKS4/4a/5 proxy server.
type Client struct {
	config     *Config
	serverAddr string
	protectFn  func(fd int) bool
}

var _ proxy.Client = (*Client)(nil)

// NewClient creates an initialized SOCKS proxy client.
func NewClient(cfg *Config, protectFn func(fd int) bool) (*Client, error) {
	if cfg == nil || cfg.Server == "" || cfg.Port <= 0 {
		return nil, fmt.Errorf("invalid socks config")
	}
	return &Client{
		config:     cfg,
		serverAddr: net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.Port)),
		protectFn:  protectFn,
	}, nil
}

// Protocol returns "socks5" or "socks4".
func (c *Client) Protocol() string {
	if c.config.Version == "socks4" || c.config.Version == "socks4a" {
		return "socks4"
	}
	return "socks5"
}

// ServerAddress returns host:port.
func (c *Client) ServerAddress() string {
	return c.serverAddr
}

// Tag returns profile tag or name.
func (c *Client) Tag() string {
	return c.config.Tag
}

// SetProtectFn updates the socket protection callback.
func (c *Client) SetProtectFn(fn func(fd int) bool) {
	c.protectFn = fn
}

// DialTCP establishes a TCP connection to targetIP:targetPort via the SOCKS proxy.
func (c *Client) DialTCP(targetIP net.IP, targetPort int) (net.Conn, error) {
	dialer := proxy.NewProtectedDialer(c.protectFn, handshakeTimeout)
	conn, err := dialer.Dial("tcp", c.serverAddr)
	if err != nil {
		return nil, fmt.Errorf("dial socks server %s failed: %w", c.serverAddr, err)
	}

	_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	if c.config.Version == "socks4" || c.config.Version == "socks4a" {
		err = c.handshakeSOCKS4(conn, targetIP, targetPort)
	} else {
		err = c.handshakeSOCKS5(conn, targetIP, targetPort)
	}
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func (c *Client) handshakeSOCKS5(conn net.Conn, targetIP net.IP, targetPort int) error {
	hasAuth := c.config.Username != "" || c.config.Password != ""
	if hasAuth {
		if _, err := conn.Write([]byte{0x05, 0x02, 0x00, 0x02}); err != nil {
			return fmt.Errorf("socks5 greeting write: %w", err)
		}
	} else {
		if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
			return fmt.Errorf("socks5 greeting write: %w", err)
		}
	}

	var methodResp [2]byte
	if _, err := io.ReadFull(conn, methodResp[:]); err != nil {
		return fmt.Errorf("socks5 greeting read: %w", err)
	}
	if methodResp[0] != 0x05 {
		return fmt.Errorf("socks5 unexpected version %d", methodResp[0])
	}

	switch methodResp[1] {
	case 0x00:
		// No authentication required.
	case 0x02:
		if !hasAuth {
			return fmt.Errorf("socks5 server requires authentication")
		}
		if err := c.authUserPass(conn); err != nil {
			return err
		}
	default:
		return fmt.Errorf("socks5 no acceptable auth method (server chose 0x%02x)", methodResp[1])
	}

	req := make([]byte, 0, 22)
	req = append(req, 0x05, 0x01, 0x00) // VER, CMD=CONNECT, RSV
	if ip4 := targetIP.To4(); ip4 != nil {
		req = append(req, 0x01)
		req = append(req, ip4...)
	} else if ip16 := targetIP.To16(); ip16 != nil {
		req = append(req, 0x04)
		req = append(req, ip16...)
	} else {
		return fmt.Errorf("socks5 invalid target IP: %v", targetIP)
	}
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], uint16(targetPort))
	req = append(req, portBuf[:]...)

	if _, err := conn.Write(req); err != nil {
		return fmt.Errorf("socks5 connect write: %w", err)
	}

	var hdr [4]byte
	if _, err := io.ReadFull(conn, hdr[:]); err != nil {
		return fmt.Errorf("socks5 connect reply read: %w", err)
	}
	if hdr[0] != 0x05 || hdr[1] != 0x00 {
		return fmt.Errorf("socks5 connect rejected: reply=0x%02x", hdr[1])
	}

	// Drain bound address so stream starts cleanly at application bytes.
	var addrLen int
	switch hdr[3] {
	case 0x01:
		addrLen = 4
	case 0x04:
		addrLen = 16
	case 0x03:
		var lenBuf [1]byte
		if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
			return fmt.Errorf("socks5 reply domain len read: %w", err)
		}
		addrLen = int(lenBuf[0])
	default:
		return fmt.Errorf("socks5 unknown reply atyp 0x%02x", hdr[3])
	}
	discard := make([]byte, addrLen+2)
	if _, err := io.ReadFull(conn, discard); err != nil {
		return fmt.Errorf("socks5 reply addr read: %w", err)
	}
	return nil
}

func (c *Client) authUserPass(conn net.Conn) error {
	if len(c.config.Username) > 255 || len(c.config.Password) > 255 {
		return fmt.Errorf("socks5 username or password exceeds 255 bytes")
	}
	buf := make([]byte, 0, 3+len(c.config.Username)+len(c.config.Password))
	buf = append(buf, 0x01, byte(len(c.config.Username)))
	buf = append(buf, c.config.Username...)
	buf = append(buf, byte(len(c.config.Password)))
	buf = append(buf, c.config.Password...)

	if _, err := conn.Write(buf); err != nil {
		return fmt.Errorf("socks5 auth write: %w", err)
	}
	var resp [2]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		return fmt.Errorf("socks5 auth read: %w", err)
	}
	if resp[1] != 0x00 {
		return fmt.Errorf("socks5 authentication failed (status=0x%02x)", resp[1])
	}
	return nil
}

func (c *Client) handshakeSOCKS4(conn net.Conn, targetIP net.IP, targetPort int) error {
	ip4 := targetIP.To4()
	if ip4 == nil {
		return fmt.Errorf("socks4 only supports IPv4 target addresses")
	}
	req := make([]byte, 0, 9+len(c.config.Username))
	req = append(req, 0x04, 0x01)
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], uint16(targetPort))
	req = append(req, portBuf[:]...)
	req = append(req, ip4...)
	req = append(req, c.config.Username...)
	req = append(req, 0x00)

	if _, err := conn.Write(req); err != nil {
		return fmt.Errorf("socks4 connect write: %w", err)
	}
	var resp [8]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		return fmt.Errorf("socks4 connect reply read: %w", err)
	}
	if resp[1] != 0x5A {
		return fmt.Errorf("socks4 connect rejected (status=0x%02x)", resp[1])
	}
	return nil
}
