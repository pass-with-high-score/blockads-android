package tailscale

import (
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"
	"hash"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	"golang.org/x/crypto/blake2s"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	protocolName          = "Noise_IK_25519_ChaChaPoly_BLAKE2s"
	protocolVersionPrefix = "Tailscale Control Protocol v"
	msgTypeInitiation     = 1
	msgTypeResponse       = 2
	msgTypeError          = 3
	msgTypeRecord         = 4
	initiationHeaderLen   = 5
	recordHeaderLen       = 3
)

func newBLAKE2s() hash.Hash {
	h, err := blake2s.New256(nil)
	if err != nil {
		panic(err)
	}
	return h
}

// NoiseIKSession manages the Tailscale TS2021 Noise IK client session.
type NoiseIKSession struct {
	mu            sync.Mutex
	h             [32]byte
	ck            [32]byte
	s             *KeyPair
	e             *KeyPair
	rs            [32]byte
	txCipher      cipher.AEAD
	rxCipher      cipher.AEAD
	txNonce       uint64
	rxNonce       uint64
	handshakeDone bool
}

// NewNoiseIKClient creates a new client session with static key s and responder public key rs.
func NewNoiseIKClient(version uint16, s *KeyPair, rs [32]byte) (*NoiseIKSession, error) {
	e, err := NewKeyPair()
	if err != nil {
		return nil, fmt.Errorf("generate ephemeral key: %w", err)
	}

	sess := &NoiseIKSession{
		s:  s,
		e:  e,
		rs: rs,
	}

	sess.h = blake2s.Sum256([]byte(protocolName))
	sess.ck = sess.h

	prologue := append([]byte(protocolVersionPrefix), strconv.Itoa(int(version))...)
	sess.mixHash(prologue)
	sess.mixHash(rs[:])

	return sess, nil
}

func (s *NoiseIKSession) mixHash(data []byte) {
	h := newBLAKE2s()
	h.Write(s.h[:])
	h.Write(data)
	copy(s.h[:], h.Sum(nil))
}

func (s *NoiseIKSession) mixDH(priv *[32]byte, pub *[32]byte) (cipher.AEAD, error) {
	keyData, err := curve25519.X25519(priv[:], pub[:])
	if err != nil {
		return nil, fmt.Errorf("x25519: %w", err)
	}

	r := hkdf.New(newBLAKE2s, keyData, s.ck[:], nil)
	if _, err := io.ReadFull(r, s.ck[:]); err != nil {
		return nil, fmt.Errorf("extract ck: %w", err)
	}
	var k [32]byte
	if _, err := io.ReadFull(r, k[:]); err != nil {
		return nil, fmt.Errorf("extract k: %w", err)
	}
	return chacha20poly1305.New(k[:])
}

// CreateHandshakeMessage builds the 101-byte initiation message for TS2021.
func (s *NoiseIKSession) CreateHandshakeMessage(version uint16) ([]byte, error) {
	init := make([]byte, 101)
	binary.BigEndian.PutUint16(init[:2], version)
	init[2] = msgTypeInitiation
	binary.BigEndian.PutUint16(init[3:5], 96) // 32 + 48 + 16

	// -> e
	copy(init[5:37], s.e.Public[:])
	s.mixHash(s.e.Public[:])

	// -> es
	c1, err := s.mixDH(&s.e.Private, &s.rs)
	if err != nil {
		return nil, fmt.Errorf("mixDH es: %w", err)
	}

	// -> s (encrypted machine public key)
	var zeroNonce [12]byte
	c1.Seal(init[37:37], zeroNonce[:], s.s.Public[:], s.h[:])
	s.mixHash(init[37:85])

	// -> ss
	c2, err := s.mixDH(&s.s.Private, &s.rs)
	if err != nil {
		return nil, fmt.Errorf("mixDH ss: %w", err)
	}

	// empty payload auth tag
	c2.Seal(init[85:85], zeroNonce[:], nil, s.h[:])
	s.mixHash(init[85:101])

	return init, nil
}

// ProcessHandshakeResponse consumes the 51-byte server response message.
func (s *NoiseIKSession) ProcessHandshakeResponse(resp []byte) error {
	if len(resp) < 51 {
		return fmt.Errorf("response message too short: %d < 51", len(resp))
	}
	if resp[0] != msgTypeResponse {
		if resp[0] == msgTypeError {
			msgLen := int(binary.BigEndian.Uint16(resp[1:3]))
			if len(resp) >= 3+msgLen {
				return fmt.Errorf("server error: %s", string(resp[3:3+msgLen]))
			}
			return fmt.Errorf("server error code %d", resp[0])
		}
		return fmt.Errorf("unexpected message type: %d", resp[0])
	}

	// <- e
	var re [32]byte
	copy(re[:], resp[3:35])
	s.mixHash(re[:])

	// <- ee
	if _, err := s.mixDH(&s.e.Private, &re); err != nil {
		return fmt.Errorf("mixDH ee: %w", err)
	}

	// <- se
	c, err := s.mixDH(&s.s.Private, &re)
	if err != nil {
		return fmt.Errorf("mixDH se: %w", err)
	}

	var zeroNonce [12]byte
	if _, err := c.Open(nil, zeroNonce[:], resp[35:51], s.h[:]); err != nil {
		return fmt.Errorf("decrypt server tag: %w", err)
	}
	s.mixHash(resp[35:51])

	// Split session keys
	r := hkdf.New(newBLAKE2s, nil, s.ck[:], nil)
	var k1, k2 [32]byte
	if _, err := io.ReadFull(r, k1[:]); err != nil {
		return fmt.Errorf("split k1: %w", err)
	}
	if _, err := io.ReadFull(r, k2[:]); err != nil {
		return fmt.Errorf("split k2: %w", err)
	}

	txCipher, err := chacha20poly1305.New(k1[:])
	if err != nil {
		return fmt.Errorf("new tx cipher: %w", err)
	}
	rxCipher, err := chacha20poly1305.New(k2[:])
	if err != nil {
		return fmt.Errorf("new rx cipher: %w", err)
	}

	s.txCipher = txCipher
	s.rxCipher = rxCipher
	s.txNonce = 0
	s.rxNonce = 0
	s.handshakeDone = true
	return nil
}

// EncryptRecord frames and encrypts a record packet (msgTypeRecord = 4).
func (s *NoiseIKSession) EncryptRecord(plaintext []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.handshakeDone {
		return nil, errors.New("handshake not complete")
	}

	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], s.txNonce)
	s.txNonce++

	cipherLen := len(plaintext) + chacha20poly1305.Overhead
	out := make([]byte, recordHeaderLen+cipherLen)
	out[0] = msgTypeRecord
	binary.BigEndian.PutUint16(out[1:3], uint16(cipherLen))

	s.txCipher.Seal(out[recordHeaderLen:recordHeaderLen], nonce[:], plaintext, nil)
	return out, nil
}

// DecryptRecord decrypts a record packet payload without the 3-byte header.
func (s *NoiseIKSession) DecryptRecord(ciphertext []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.handshakeDone {
		return nil, errors.New("handshake not complete")
	}

	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], s.rxNonce)
	s.rxNonce++

	return s.rxCipher.Open(nil, nonce[:], ciphertext, nil)
}

// WriteMsg writes an encrypted framed record to w.
func (s *NoiseIKSession) WriteMsg(w io.Writer, plaintext []byte) error {
	frame, err := s.EncryptRecord(plaintext)
	if err != nil {
		return err
	}
	_, err = w.Write(frame)
	return err
}

// ReadMsg reads an encrypted framed record from r.
func (s *NoiseIKSession) ReadMsg(r io.Reader) ([]byte, error) {
	var hdr [recordHeaderLen]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	if hdr[0] != msgTypeRecord {
		return nil, fmt.Errorf("unexpected record type: %d", hdr[0])
	}
	length := int(binary.BigEndian.Uint16(hdr[1:3]))
	cipherBuf := make([]byte, length)
	if _, err := io.ReadFull(r, cipherBuf); err != nil {
		return nil, err
	}
	return s.DecryptRecord(cipherBuf)
}

// NoiseConn wraps NoiseIKSession and underlying net.Conn into a secure net.Conn.
type NoiseConn struct {
	sess *NoiseIKSession
	conn net.Conn
	rBuf []byte
	rMu  sync.Mutex
}

// NewNoiseConn creates a NoiseConn net.Conn.
func NewNoiseConn(sess *NoiseIKSession, conn net.Conn) *NoiseConn {
	return &NoiseConn{sess: sess, conn: conn}
}

func (c *NoiseConn) Read(p []byte) (int, error) {
	c.rMu.Lock()
	defer c.rMu.Unlock()

	for len(c.rBuf) == 0 {
		dec, err := c.sess.ReadMsg(c.conn)
		if err != nil {
			return 0, err
		}
		c.rBuf = dec
	}

	n := copy(p, c.rBuf)
	c.rBuf = c.rBuf[n:]
	return n, nil
}

func (c *NoiseConn) Write(p []byte) (int, error) {
	if err := c.sess.WriteMsg(c.conn, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *NoiseConn) Close() error                       { return c.conn.Close() }
func (c *NoiseConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *NoiseConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }
func (c *NoiseConn) SetDeadline(t time.Time) error      { return c.conn.SetDeadline(t) }
func (c *NoiseConn) SetReadDeadline(t time.Time) error  { return c.conn.SetReadDeadline(t) }
func (c *NoiseConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }


