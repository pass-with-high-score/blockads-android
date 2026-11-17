package httpproxy

import (
	"bufio"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
)

const connectTimeout = 10 * time.Second

// Client handles outbound TCP tunneling via an HTTP/HTTPS CONNECT proxy.
type Client struct {
	config     *Config
	serverAddr string
	protectFn  func(fd int) bool
}

var _ proxy.Client = (*Client)(nil)

// NewClient creates an initialized HTTP/HTTPS proxy client.
func NewClient(cfg *Config, protectFn func(fd int) bool) (*Client, error) {
	if cfg == nil || cfg.Server == "" || cfg.Port <= 0 {
		return nil, fmt.Errorf("invalid http proxy config")
	}
	return &Client{
		config:     cfg,
		serverAddr: net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.Port)),
		protectFn:  protectFn,
	}, nil
}

// Protocol returns "https" when TLS is enabled, otherwise "http".
func (c *Client) Protocol() string {
	if c.config.TLS {
		return "https"
	}
	return "http"
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

// DialTCP establishes a CONNECT tunnel to targetIP:targetPort through the HTTP/HTTPS proxy.
func (c *Client) DialTCP(targetIP net.IP, targetPort int) (net.Conn, error) {
	dialer := proxy.NewProtectedDialer(c.protectFn, connectTimeout)
	conn, err := dialer.Dial("tcp", c.serverAddr)
	if err != nil {
		return nil, fmt.Errorf("dial http proxy %s failed: %w", c.serverAddr, err)
	}

	if c.config.TLS {
		tlsConn := tls.Client(conn, &tls.Config{
			ServerName:         c.config.SNI,
			InsecureSkipVerify: c.config.AllowInsecure,
		})
		_ = tlsConn.SetDeadline(time.Now().Add(connectTimeout))
		if err := tlsConn.Handshake(); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("http proxy tls handshake failed: %w", err)
		}
		conn = tlsConn
	}

	_ = conn.SetDeadline(time.Now().Add(connectTimeout))
	target := net.JoinHostPort(targetIP.String(), strconv.Itoa(targetPort))

	var reqBuf strings.Builder
	fmt.Fprintf(&reqBuf, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n", target, target)
	if c.config.Username != "" || c.config.Password != "" {
		token := base64.StdEncoding.EncodeToString([]byte(c.config.Username + ":" + c.config.Password))
		fmt.Fprintf(&reqBuf, "Proxy-Authorization: Basic %s\r\n", token)
	}
	reqBuf.WriteString("\r\n")

	if _, err := conn.Write([]byte(reqBuf.String())); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("http proxy CONNECT write failed: %w", err)
	}

	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("http proxy CONNECT response failed: %w", err)
	}
	_ = resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		_ = conn.Close()
		return nil, fmt.Errorf("http proxy CONNECT rejected: %s", resp.Status)
	}

	_ = conn.SetDeadline(time.Time{})
	if br.Buffered() > 0 {
		return &bufferedConn{Conn: conn, br: br}, nil
	}
	return conn, nil
}

// bufferedConn drains any bytes already read into bufio.Reader during HTTP header parsing.
type bufferedConn struct {
	net.Conn
	br *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) {
	if b.br != nil {
		if b.br.Buffered() > 0 {
			return b.br.Read(p)
		}
		b.br = nil
	}
	return b.Conn.Read(p)
}
