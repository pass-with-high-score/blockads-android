package ssh

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/binary"
	"io"
	"net"
	"testing"

	gossh "golang.org/x/crypto/ssh"
)

func TestFromJSON(t *testing.T) {
	cfg, err := FromJSON(`{"server":"1.2.3.4","username":"sshtunnel","password":"pwd"}`)
	if err != nil {
		t.Fatalf("FromJSON failed: %v", err)
	}
	if cfg.Port != 22 || cfg.Username != "sshtunnel" || cfg.Password != "pwd" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestSSHDialTCP(t *testing.T) {
	hostKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	hostSigner, err := gossh.NewSignerFromKey(hostKey)
	if err != nil {
		t.Fatal(err)
	}

	srvCfg := &gossh.ServerConfig{
		PasswordCallback: func(c gossh.ConnMetadata, pass []byte) (*gossh.Permissions, error) {
			if c.User() == "sshtunnel" && string(pass) == "secret" {
				return nil, nil
			}
			return nil, io.EOF
		},
	}
	srvCfg.AddHostKey(hostSigner)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		rawConn, err := ln.Accept()
		if err != nil {
			return
		}
		_, chans, reqs, err := gossh.NewServerConn(rawConn, srvCfg)
		if err != nil {
			return
		}
		go gossh.DiscardRequests(reqs)
		for newCh := range chans {
			if newCh.ChannelType() != "direct-tcpip" {
				_ = newCh.Reject(gossh.UnknownChannelType, "unsupported")
				continue
			}
			// Parse direct-tcpip extra data just to verify target port is encoded.
			extra := newCh.ExtraData()
			if len(extra) < 8 {
				_ = newCh.Reject(gossh.Prohibited, "bad payload")
				continue
			}
			hostLen := binary.BigEndian.Uint32(extra[:4])
			_ = hostLen
			ch, chReqs, err := newCh.Accept()
			if err != nil {
				continue
			}
			go gossh.DiscardRequests(chReqs)
			go func(c gossh.Channel) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(ch)
		}
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client, err := NewClient(&Config{
		Server:   "127.0.0.1",
		Port:     addr.Port,
		Username: "sshtunnel",
		Password: "secret",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	c, err := client.DialTCP(net.ParseIP("1.1.1.1"), 443)
	if err != nil {
		t.Fatalf("DialTCP failed: %v", err)
	}
	defer c.Close()

	if _, err := c.Write([]byte("echo")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "echo" {
		t.Fatalf("expected echo, got %q", buf)
	}
}
