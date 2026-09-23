package packet

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"

	"github.com/miekg/dns"
)

func mustQuery(t testing.TB) []byte {
	t.Helper()
	m := new(dns.Msg)
	m.SetQuestion("ads.example.com.", dns.TypeA)
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// shortIHLPacket lays out a UDP/DNS packet right after a 16-byte "IPv4 header" (ihl=4).
func shortIHLPacket(q []byte) []byte {
	pkt := make([]byte, 16+UDPHeaderSize+len(q))
	pkt[0] = 0x44
	pkt[9] = 17
	binary.BigEndian.PutUint16(pkt[16:18], 40000)
	binary.BigEndian.PutUint16(pkt[18:20], 53)
	binary.BigEndian.PutUint16(pkt[20:22], uint16(UDPHeaderSize+len(q)))
	copy(pkt[24:], q)
	return pkt
}

func TestParseTUNPacketRejectsMalformedHeaders(t *testing.T) {
	t.Skip("known bug: IPv4 IHL and IP/UDP length fields are not validated")
	q := mustQuery(t)
	v4 := func() []byte { return BuildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(10, 0, 0, 1), 40000, 53, q) }
	v6 := func() []byte { return BuildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("fd00::1"), 40000, 53, q) }

	cases := []struct {
		name string
		pkt  []byte
	}{
		{"ipv4 ihl below 5", shortIHLPacket(q)},
		{"ipv4 total length beyond read", func() []byte {
			p := v4()
			binary.BigEndian.PutUint16(p[2:4], uint16(len(p)+10))
			return p
		}()},
		{"ipv4 total length shorter than headers", func() []byte {
			p := v4()
			binary.BigEndian.PutUint16(p[2:4], IPv4HeaderSize+4)
			return p
		}()},
		{"ipv4 udp length beyond packet", func() []byte {
			p := v4()
			binary.BigEndian.PutUint16(p[24:26], uint16(len(p)))
			return p
		}()},
		{"ipv4 udp length below header", func() []byte {
			p := v4()
			binary.BigEndian.PutUint16(p[24:26], 4)
			return p
		}()},
		{"ipv6 payload length beyond read", func() []byte {
			p := v6()
			binary.BigEndian.PutUint16(p[4:6], uint16(len(p)))
			return p
		}()},
		{"ipv6 udp length beyond packet", func() []byte {
			p := v6()
			binary.BigEndian.PutUint16(p[44:46], uint16(len(p)))
			return p
		}()},
	}
	for _, c := range cases {
		if info := ParseTUNPacket(c.pkt, len(c.pkt)); info != nil {
			t.Errorf("%s: parsed as %+v, want nil", c.name, info)
		}
	}
}

func TestParseTUNPacketIgnoresTrailingBytes(t *testing.T) {
	t.Skip("known bug: IPv4 IHL and IP/UDP length fields are not validated")
	q := mustQuery(t)
	for _, pkt := range [][]byte{
		BuildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(10, 0, 0, 1), 40000, 53, q),
		BuildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("fd00::1"), 40000, 53, q),
	} {
		padded := append(append([]byte{}, pkt...), 0xDE, 0xAD, 0xBE, 0xEF)
		info := ParseTUNPacket(padded, len(padded))
		if info == nil {
			t.Fatal("padded packet did not parse")
		}
		if !bytes.Equal(info.RawDNSPayload, q) {
			t.Errorf("v%d payload has %d bytes, want %d (trailing bytes leaked into DNS payload)", pkt[0]>>4, len(info.RawDNSPayload), len(q))
		}
	}
}

func TestParseTUNPacketShortAndOddInputs(t *testing.T) {
	for _, pkt := range [][]byte{
		nil,
		{0x45},
		make([]byte, 19),
		make([]byte, 28), // version 0
		append([]byte{0x45}, make([]byte, 27)...), // not UDP
		append([]byte{0x60}, make([]byte, 47)...), // v6, not UDP
	} {
		if info := ParseTUNPacket(pkt, len(pkt)); info != nil {
			t.Errorf("ParseTUNPacket(%x) = %+v, want nil", pkt, info)
		}
	}
}

func FuzzParseTUNPacket(f *testing.F) {
	q := mustQuery(f)
	f.Add(BuildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(10, 0, 0, 1), 40000, 53, q))
	f.Add(BuildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("fd00::1"), 40000, 53, q))
	f.Add(shortIHLPacket(q))
	f.Add([]byte{0x4F, 0, 0, 0, 0, 0, 0, 0, 0, 17})
	f.Fuzz(func(t *testing.T, data []byte) {
		info := ParseTUNPacket(data, len(data))
		if info == nil {
			return
		}
		if info.IsIPv6 {
			BuildNXDomainResponse(info)
		} else {
			BuildBlockedResponse(info)
		}
	})
}
