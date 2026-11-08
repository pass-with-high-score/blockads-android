package tailscale

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"strings"

	"golang.org/x/crypto/nacl/box"
)

const (
	// DiscoMagic is the 6-byte magic header prefixing all Tailscale Disco packets ("TS💬").
	DiscoMagic = "TS\xf0\x9f\x92\xac"

	discoHeaderLen = 6 + 32 + 24 // Magic(6) + SenderDiscoPub(32) + Nonce(24)

	DiscoMsgPing        byte = 0x01
	DiscoMsgPong        byte = 0x02
	DiscoMsgCallMeMaybe byte = 0x03

	discoVersion byte = 0x00
)

// DiscoEnvelope holds a decrypted Disco control message.
type DiscoEnvelope struct {
	SenderDiscoPub [32]byte
	MsgType        byte
	TxID           [12]byte
	NodeKey        [32]byte
	HasNodeKey     bool
	PongSrc        netip.AddrPort
	MyNumber       []netip.AddrPort
}

// IsDiscoPacket reports whether pkt begins with the 6-byte Tailscale Disco magic header
// and is long enough to contain a valid NaCl box header + Poly1305 tag.
func IsDiscoPacket(pkt []byte) bool {
	return len(pkt) >= discoHeaderLen+box.Overhead+2 && string(pkt[:6]) == DiscoMagic
}

// ParseKeyHex parses a 32-byte key from a prefixed hex string (e.g. "nodekey:...", "discokey:...")
// or raw hex / base64 string.
func ParseKeyHex(raw, prefix string) ([32]byte, error) {
	var out [32]byte
	s := strings.TrimSpace(raw)
	if prefix != "" {
		s = strings.TrimPrefix(s, prefix)
	}
	for _, p := range []string{"nodekey:", "discokey:", "mkey:"} {
		s = strings.TrimPrefix(s, p)
	}
	if len(s) == 64 {
		b, err := hex.DecodeString(s)
		if err != nil {
			return out, fmt.Errorf("decode hex key: %w", err)
		}
		copy(out[:], b)
		return out, nil
	}
	if b, err := base64.StdEncoding.DecodeString(s); err == nil && len(b) == 32 {
		copy(out[:], b)
		return out, nil
	}
	return out, fmt.Errorf("invalid 32-byte key: %q", raw)
}

func sealDiscoPayload(selfDisco *KeyPair, peerDiscoPub [32]byte, payload []byte) ([]byte, error) {
	if selfDisco == nil {
		return nil, errors.New("nil self disco key")
	}
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	out := make([]byte, discoHeaderLen, discoHeaderLen+len(payload)+box.Overhead)
	copy(out[0:6], DiscoMagic)
	copy(out[6:38], selfDisco.Public[:])
	copy(out[38:62], nonce[:])
	out = box.Seal(out, payload, &nonce, &peerDiscoPub, &selfDisco.Private)
	return out, nil
}

// SealDiscoPing constructs and encrypts a Disco Ping message.
func SealDiscoPing(selfDisco *KeyPair, peerDiscoPub [32]byte, txID [12]byte, selfNodePub [32]byte) ([]byte, error) {
	payload := make([]byte, 2+12+32)
	payload[0] = DiscoMsgPing
	payload[1] = discoVersion
	copy(payload[2:14], txID[:])
	copy(payload[14:46], selfNodePub[:])
	return sealDiscoPayload(selfDisco, peerDiscoPub, payload)
}

// SealDiscoPong constructs and encrypts a Disco Pong message replying to txID with the observed source address.
func SealDiscoPong(selfDisco *KeyPair, peerDiscoPub [32]byte, txID [12]byte, src netip.AddrPort) ([]byte, error) {
	payload := make([]byte, 2+12+18)
	payload[0] = DiscoMsgPong
	payload[1] = discoVersion
	copy(payload[2:14], txID[:])
	ip16 := src.Addr().As16()
	copy(payload[14:30], ip16[:])
	binary.BigEndian.PutUint16(payload[30:32], src.Port())
	return sealDiscoPayload(selfDisco, peerDiscoPub, payload)
}

// SealDiscoCallMeMaybe constructs and encrypts a Disco CallMeMaybe message advertising our candidate UDP endpoints.
func SealDiscoCallMeMaybe(selfDisco *KeyPair, peerDiscoPub [32]byte, myAddrs []netip.AddrPort) ([]byte, error) {
	payload := make([]byte, 2+len(myAddrs)*18)
	payload[0] = DiscoMsgCallMeMaybe
	payload[1] = discoVersion
	off := 2
	for _, ap := range myAddrs {
		ip16 := ap.Addr().As16()
		copy(payload[off:off+16], ip16[:])
		binary.BigEndian.PutUint16(payload[off+16:off+18], ap.Port())
		off += 18
	}
	return sealDiscoPayload(selfDisco, peerDiscoPub, payload)
}

// OpenDiscoPacket decrypts and parses an incoming Disco packet.
func OpenDiscoPacket(selfDisco *KeyPair, pkt []byte) (*DiscoEnvelope, error) {
	if !IsDiscoPacket(pkt) {
		return nil, errors.New("not a disco packet")
	}
	if selfDisco == nil {
		return nil, errors.New("nil self disco key")
	}

	var senderPub [32]byte
	var nonce [24]byte
	copy(senderPub[:], pkt[6:38])
	copy(nonce[:], pkt[38:62])

	plain, ok := box.Open(nil, pkt[62:], &nonce, &senderPub, &selfDisco.Private)
	if !ok {
		return nil, errors.New("disco box decryption failed")
	}
	if len(plain) < 2 {
		return nil, errors.New("disco payload too short")
	}

	env := &DiscoEnvelope{
		SenderDiscoPub: senderPub,
		MsgType:        plain[0],
	}
	body := plain[2:]

	switch env.MsgType {
	case DiscoMsgPing:
		if len(body) < 12 {
			return nil, errors.New("disco ping too short")
		}
		copy(env.TxID[:], body[:12])
		if len(body) >= 44 {
			copy(env.NodeKey[:], body[12:44])
			env.HasNodeKey = true
		}
		return env, nil

	case DiscoMsgPong:
		if len(body) < 30 {
			return nil, errors.New("disco pong too short")
		}
		copy(env.TxID[:], body[:12])
		var ip16 [16]byte
		copy(ip16[:], body[12:28])
		addr := netip.AddrFrom16(ip16).Unmap()
		port := binary.BigEndian.Uint16(body[28:30])
		env.PongSrc = netip.AddrPortFrom(addr, port)
		return env, nil

	case DiscoMsgCallMeMaybe:
		if len(body)%18 != 0 {
			return nil, errors.New("disco call-me-maybe invalid length")
		}
		count := len(body) / 18
		env.MyNumber = make([]netip.AddrPort, 0, count)
		for i := 0; i < count; i++ {
			chunk := body[i*18 : (i+1)*18]
			var ip16 [16]byte
			copy(ip16[:], chunk[:16])
			addr := netip.AddrFrom16(ip16).Unmap()
			port := binary.BigEndian.Uint16(chunk[16:18])
			env.MyNumber = append(env.MyNumber, netip.AddrPortFrom(addr, port))
		}
		return env, nil

	default:
		return env, nil
	}
}
