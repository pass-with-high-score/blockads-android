package ssh

import (
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
	gossh "golang.org/x/crypto/ssh"
)

const sshDialTimeout = 10 * time.Second

// Client handles outbound TCP tunneling over an SSH2 connection (direct-tcpip).
type Client struct {
	config     *Config
	serverAddr string
	protectFn  func(fd int) bool
	auth       []gossh.AuthMethod

	mu     sync.Mutex
	client *gossh.Client
}

var _ proxy.Client = (*Client)(nil)

// NewClient creates an initialized SSH tunnel client.
func NewClient(cfg *Config, protectFn func(fd int) bool) (*Client, error) {
	if cfg == nil || cfg.Server == "" || cfg.Port <= 0 || cfg.Username == "" {
		return nil, fmt.Errorf("invalid ssh config")
	}

	var auth []gossh.AuthMethod
	if cfg.PrivateKey != "" {
		var signer gossh.Signer
		var err error
		if cfg.Passphrase != "" {
			signer, err = gossh.ParsePrivateKeyWithPassphrase([]byte(cfg.PrivateKey), []byte(cfg.Passphrase))
		} else {
			signer, err = gossh.ParsePrivateKey([]byte(cfg.PrivateKey))
		}
		if err != nil {
			return nil, fmt.Errorf("parse ssh private key failed: %w", err)
		}
		auth = append(auth, gossh.PublicKeys(signer))
	}
	if cfg.Password != "" {
		auth = append(auth, gossh.Password(cfg.Password))
	}
	if len(auth) == 0 {
		return nil, fmt.Errorf("invalid ssh config: no auth method provided")
	}

	return &Client{
		config:     cfg,
		serverAddr: net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.Port)),
		protectFn:  protectFn,
		auth:       auth,
	}, nil
}

// Protocol returns "ssh".
func (c *Client) Protocol() string {
	return "ssh"
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

// Close closes any active SSH session.
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.client != nil {
		err := c.client.Close()
		c.client = nil
		return err
	}
	return nil
}

// DialTCP opens a direct-tcpip channel to targetIP:targetPort over SSH.
func (c *Client) DialTCP(targetIP net.IP, targetPort int) (net.Conn, error) {
	dst := net.JoinHostPort(targetIP.String(), strconv.Itoa(targetPort))

	sshClient, err := c.getOrDialClient()
	if err != nil {
		return nil, err
	}

	conn, err := sshClient.Dial("tcp", dst)
	if err == nil {
		return conn, nil
	}

	// Cached SSH session may have dropped; reset once and retry.
	c.resetClient(sshClient)
	sshClient, err = c.getOrDialClient()
	if err != nil {
		return nil, err
	}
	return sshClient.Dial("tcp", dst)
}

func (c *Client) getOrDialClient() (*gossh.Client, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.client != nil {
		return c.client, nil
	}

	dialer := proxy.NewProtectedDialer(c.protectFn, sshDialTimeout)
	rawConn, err := dialer.Dial("tcp", c.serverAddr)
	if err != nil {
		return nil, fmt.Errorf("dial ssh server %s failed: %w", c.serverAddr, err)
	}

	sshCfg := &gossh.ClientConfig{
		User:            c.config.Username,
		Auth:            c.auth,
		HostKeyCallback: gossh.InsecureIgnoreHostKey(),
		Timeout:         sshDialTimeout,
	}

	sshConn, chans, reqs, err := gossh.NewClientConn(rawConn, c.serverAddr, sshCfg)
	if err != nil {
		_ = rawConn.Close()
		return nil, fmt.Errorf("ssh handshake %s failed: %w", c.serverAddr, err)
	}

	c.client = gossh.NewClient(sshConn, chans, reqs)
	return c.client, nil
}

func (c *Client) resetClient(expected *gossh.Client) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.client == expected && c.client != nil {
		_ = c.client.Close()
		c.client = nil
	}
}
