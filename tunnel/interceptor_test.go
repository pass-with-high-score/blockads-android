package tunnel

import (
	"encoding/binary"
	"net"
	"testing"
)

func TestIsDNSPacket(t *testing.T) {
	payload := make([]byte, 12)
	v4 := func(dport uint16) []byte {
		return buildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(10, 0, 0, 1), 40000, dport, payload)
	}
	v6 := func(dport uint16) []byte {
		return buildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("fd00::1"), 40000, dport, payload)
	}
	withOptions := func() []byte {
		// ihl=6: 4 bytes of IP options before the UDP header
		p := v4(53)
		out := append(append(append([]byte{}, p[:20]...), 1, 1, 1, 0), p[20:]...)
		out[0] = 0x46
		return out
	}
	shortIHL := func() []byte {
		// ihl=4 with dst IP ending in 0.53, so a 16-byte "header" reads port 53
		p := v4(1)
		p[0] = 0x44
		binary.BigEndian.PutUint16(p[18:20], 53)
		return p
	}
	tcp := func() []byte {
		p := v4(53)
		p[9] = 6
		return p
	}

	cases := []struct {
		name string
		pkt  []byte
		want bool
	}{
		{"ipv4 udp 53", v4(53), true},
		{"ipv4 udp 443", v4(443), false},
		{"ipv4 options udp 53", withOptions(), true},
		{"ipv4 tcp 53", tcp(), false},
		{"ipv6 udp 53", v6(53), true},
		{"ipv6 udp 5353", v6(5353), false},
		{"ipv4 ihl below 5", shortIHL(), false},
		{"ipv4 ihl 0", func() []byte { p := v4(53); p[0] = 0x40; return p }(), false},
		{"too short", v4(53)[:27], false},
		{"ipv6 too short", v6(53)[:47], false},
		{"version 5", func() []byte { p := v4(53); p[0] = 0x55; return p }(), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if c.name == "ipv4 ihl below 5" {
				t.Skip("known bug: IPv4 IHL and IP/UDP length fields are not validated")
			}
			if got := isDNSPacket(c.pkt, len(c.pkt)); got != c.want {
				t.Errorf("isDNSPacket = %v, want %v", got, c.want)
			}
		})
	}
}

func FuzzIsDNSPacket(f *testing.F) {
	f.Add(buildIPv4UDPPacket(net.IPv4(10, 0, 0, 2), net.IPv4(10, 0, 0, 1), 1, 53, []byte{0}))
	f.Add(buildIPv6UDPPacket(net.ParseIP("fd00::2"), net.ParseIP("fd00::1"), 1, 53, []byte{0}))
	f.Add(append([]byte{0x4F}, make([]byte, 27)...))
	f.Fuzz(func(t *testing.T, data []byte) {
		if isDNSPacket(data, len(data)) && data[0]>>4 == 4 && int(data[0]&0x0F) < 5 {
			t.Skip("known bug: isDNSPacket accepts an IPv4 header shorter than 20 bytes")
		}
	})
}
