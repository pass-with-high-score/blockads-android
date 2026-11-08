package tailscale

import (
	"bufio"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"syscall"
	"time"

	"golang.org/x/crypto/nacl/box"
)

const (
	// DERPMagic is the 8-byte magic prefix inside frameServerKey ("DERP🔑").
	DERPMagic = "DERP\xf0\x9f\x94\x91"

	derpFrameServerKey     byte = 0x01
	derpFrameClientInfo    byte = 0x02
	derpFrameServerInfo    byte = 0x03
	derpFrameSendPacket    byte = 0x04
	derpFrameRecvPacket    byte = 0x05
	derpFrameKeepAlive     byte = 0x06
	derpFrameNotePreferred byte = 0x07
	derpFramePeerGone      byte = 0x08
	derpFramePeerPresent   byte = 0x09
	derpFramePing          byte = 0x12
	derpFramePong          byte = 0x13
	derpFrameHealth        byte = 0x14

	derpMaxPacketSize = 64 * 1024
)

type derpClientInfo struct {
	Version     int  `json:"Version"`
	CanAckPings bool `json:"CanAckPings"`
}

// DERPRecvFunc is invoked when a packet arrives from a DERP server.
type DERPRecvFunc func(regionID int, srcNodeKey [32]byte, packet []byte)

// DERPClient manages a single persistent connection to a Tailscale DERP relay server.
type DERPClient struct {
	regionID  int
	node      *DERPNode
	selfKey   *KeyPair
	protectFn func(fd int) bool
	onRecv    DERPRecvFunc
	insecure  bool
	plainHTTP bool

	writeMu   sync.Mutex
	conn      net.Conn
	bw        *bufio.Writer
	serverKey [32]byte
	closed    chan struct{}
	closeOnce sync.Once
}

// DialDERPNode connects to a DERPNode, completes the DERP v2 cryptographic handshake,
// and starts the packet read loop.
func DialDERPNode(
	regionID int,
	node *DERPNode,
	selfKey *KeyPair,
	preferred bool,
	protectFn func(fd int) bool,
	onRecv DERPRecvFunc,
	insecure bool,
	plainHTTP bool,
) (*DERPClient, error) {
	if node == nil || selfKey == nil {
		return nil, errors.New("derp: nil node or key")
	}

	port := node.Port
	if port <= 0 {
		port = node.DERPPort
	}
	if port <= 0 {
		if plainHTTP {
			port = 80
		} else {
			port = 443
		}
	}

	dialHost := node.IPv4
	if dialHost == "" {
		dialHost = node.HostName
	}
	targetAddr := net.JoinHostPort(dialHost, strconv.Itoa(port))

	dialer := &net.Dialer{Timeout: 8 * time.Second}
	if protectFn != nil {
		dialer.Control = func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				protectFn(int(fd))
			})
		}
	}

	rawConn, err := dialer.Dial("tcp", targetAddr)
	if err != nil && dialHost != node.HostName && node.HostName != "" {
		targetAddr = net.JoinHostPort(node.HostName, strconv.Itoa(port))
		rawConn, err = dialer.Dial("tcp", targetAddr)
	}
	if err != nil {
		return nil, fmt.Errorf("derp dial %s: %w", targetAddr, err)
	}

	var netConn net.Conn = rawConn
	if !plainHTTP {
		sni := node.HostName
		if sni == "" {
			sni = dialHost
		}
		tlsConn := tls.Client(rawConn, &tls.Config{
			ServerName:         sni,
			InsecureSkipVerify: insecure,
			NextProtos:         []string{"http/1.1"},
		})
		_ = tlsConn.SetDeadline(time.Now().Add(8 * time.Second))
		if err := tlsConn.Handshake(); err != nil {
			rawConn.Close()
			return nil, fmt.Errorf("derp tls handshake %s: %w", sni, err)
		}
		netConn = tlsConn
	}

	_ = netConn.SetDeadline(time.Now().Add(10 * time.Second))
	hostHeader := node.HostName
	if hostHeader == "" {
		hostHeader = dialHost
	}
	reqStr := fmt.Sprintf(
		"GET /derp HTTP/1.1\r\n"+
			"Host: %s\r\n"+
			"Connection: Upgrade\r\n"+
			"Upgrade: DERP\r\n\r\n",
		hostHeader,
	)
	if _, err := io.WriteString(netConn, reqStr); err != nil {
		netConn.Close()
		return nil, fmt.Errorf("derp write upgrade: %w", err)
	}

	br := bufio.NewReaderSize(netConn, 64*1024)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		netConn.Close()
		return nil, fmt.Errorf("derp read upgrade response: %w", err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		netConn.Close()
		return nil, fmt.Errorf("derp unexpected HTTP status %d", resp.StatusCode)
	}

	bw := bufio.NewWriterSize(netConn, 64*1024)
	c := &DERPClient{
		regionID:  regionID,
		node:      node,
		selfKey:   selfKey,
		protectFn: protectFn,
		onRecv:    onRecv,
		insecure:  insecure,
		plainHTTP: plainHTTP,
		conn:      &bufferedConn{r: br, Conn: netConn},
		bw:        bw,
		closed:    make(chan struct{}),
	}

	if err := c.handshake(br, preferred); err != nil {
		netConn.Close()
		return nil, err
	}

	_ = netConn.SetDeadline(time.Time{})
	logf("[Tailscale/DERP] Connected to region %d (%s)", regionID, hostHeader)

	go c.readLoop(br)
	go c.keepAliveLoop()
	return c, nil
}

func (c *DERPClient) handshake(br *bufio.Reader, preferred bool) error {
	// 1. Read frameServerKey
	ft, flen, err := readDERPFrameHeader(br)
	if err != nil {
		return fmt.Errorf("derp read server key header: %w", err)
	}
	if ft != derpFrameServerKey || flen < 40 {
		return fmt.Errorf("derp unexpected first frame type=%d len=%d", ft, flen)
	}
	buf := make([]byte, flen)
	if _, err := io.ReadFull(br, buf); err != nil {
		return fmt.Errorf("derp read server key payload: %w", err)
	}
	if string(buf[:8]) != DERPMagic {
		return errors.New("derp invalid server key magic")
	}
	copy(c.serverKey[:], buf[8:40])

	// 2. Send frameClientInfo
	infoBytes, _ := json.Marshal(derpClientInfo{Version: 2, CanAckPings: true})
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return err
	}
	sealed := box.Seal(nil, infoBytes, &nonce, &c.serverKey, &c.selfKey.Private)
	clientPayload := make([]byte, 32+24+len(sealed))
	copy(clientPayload[0:32], c.selfKey.Public[:])
	copy(clientPayload[32:56], nonce[:])
	copy(clientPayload[56:], sealed)

	if err := c.writeFrameLocked(derpFrameClientInfo, clientPayload); err != nil {
		return fmt.Errorf("derp write client info: %w", err)
	}

	// 3. Read frameServerInfo
	ft, flen, err = readDERPFrameHeader(br)
	if err != nil {
		return fmt.Errorf("derp read server info header: %w", err)
	}
	if ft != derpFrameServerInfo || flen > 64*1024 {
		return fmt.Errorf("derp unexpected server info frame=%d len=%d", ft, flen)
	}
	if flen > 0 {
		if _, err := io.CopyN(io.Discard, br, int64(flen)); err != nil {
			return fmt.Errorf("derp read server info body: %w", err)
		}
	}

	if preferred {
		_ = c.NotePreferred(true)
	}
	return nil
}

// NotePreferred informs the DERP server whether this region is our home DERP.
func (c *DERPClient) NotePreferred(preferred bool) error {
	var b byte
	if preferred {
		b = 1
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return c.writeFrameLocked(derpFrameNotePreferred, []byte{b})
}

// SendPacket relays an encrypted WireGuard or Disco packet to dstNodeKey via this DERP server.
func (c *DERPClient) SendPacket(dstNodeKey [32]byte, pkt []byte) error {
	if len(pkt) == 0 || len(pkt) > derpMaxPacketSize {
		return errors.New("derp: invalid packet size")
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	var hdr [5]byte
	hdr[0] = derpFrameSendPacket
	binary.BigEndian.PutUint32(hdr[1:5], uint32(32+len(pkt)))
	_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.bw.Write(hdr[:]); err != nil {
		return err
	}
	if _, err := c.bw.Write(dstNodeKey[:]); err != nil {
		return err
	}
	if _, err := c.bw.Write(pkt); err != nil {
		return err
	}
	return c.bw.Flush()
}

func (c *DERPClient) writeFrameLocked(frameType byte, payload []byte) error {
	var hdr [5]byte
	hdr[0] = frameType
	binary.BigEndian.PutUint32(hdr[1:5], uint32(len(payload)))
	_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.bw.Write(hdr[:]); err != nil {
		return err
	}
	if len(payload) > 0 {
		if _, err := c.bw.Write(payload); err != nil {
			return err
		}
	}
	return c.bw.Flush()
}

func (c *DERPClient) readLoop(br *bufio.Reader) {
	defer c.Close()
	for {
		select {
		case <-c.closed:
			return
		default:
		}
		ft, flen, err := readDERPFrameHeader(br)
		if err != nil {
			return
		}
		if flen > derpMaxPacketSize+64 {
			return
		}
		switch ft {
		case derpFrameRecvPacket:
			if flen < 32 {
				if _, err := io.CopyN(io.Discard, br, int64(flen)); err != nil {
					return
				}
				continue
			}
			var srcKey [32]byte
			if _, err := io.ReadFull(br, srcKey[:]); err != nil {
				return
			}
			pktLen := int(flen - 32)
			pkt := make([]byte, pktLen)
			if _, err := io.ReadFull(br, pkt); err != nil {
				return
			}
			if c.onRecv != nil {
				c.onRecv(c.regionID, srcKey, pkt)
			}

		case derpFramePing:
			pingData := make([]byte, flen)
			if _, err := io.ReadFull(br, pingData); err != nil {
				return
			}
			if len(pingData) == 8 {
				c.writeMu.Lock()
				_ = c.writeFrameLocked(derpFramePong, pingData)
				c.writeMu.Unlock()
			}

		default:
			if flen > 0 {
				if _, err := io.CopyN(io.Discard, br, int64(flen)); err != nil {
					return
				}
			}
		}
	}
}

func (c *DERPClient) keepAliveLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-ticker.C:
			c.writeMu.Lock()
			err := c.writeFrameLocked(derpFrameKeepAlive, nil)
			c.writeMu.Unlock()
			if err != nil {
				c.Close()
				return
			}
		}
	}
}

// IsClosed reports whether this DERP client connection has been closed.
func (c *DERPClient) IsClosed() bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

// Close closes the DERP connection.
func (c *DERPClient) Close() {
	c.closeOnce.Do(func() {
		close(c.closed)
		if c.conn != nil {
			_ = c.conn.Close()
		}
	})
}

func readDERPFrameHeader(r io.Reader) (byte, uint32, error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, 0, err
	}
	return hdr[0], binary.BigEndian.Uint32(hdr[1:5]), nil
}
