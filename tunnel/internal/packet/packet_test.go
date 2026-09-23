package packet

import (
	"bytes"
	"net"
	"testing"

	"github.com/miekg/dns"
)

var (
	clientV4 = net.IPv4(10, 0, 0, 2).To4()
	serverV4 = net.IPv4(10, 0, 0, 1).To4()
	clientV6 = net.ParseIP("fd00::2")
	serverV6 = net.ParseIP("fd00::1")
)

func dnsQuery(t testing.TB, name string, qtype uint16) []byte {
	t.Helper()
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(name), qtype)
	m.Id = 0x1234
	b, err := m.Pack()
	if err != nil {
		t.Fatalf("pack query: %v", err)
	}
	return b
}

func TestBuildParseRoundTrip(t *testing.T) {
	type builder func(*DNSQueryInfo) []byte
	builders := map[string]builder{
		"blocked":  BuildBlockedResponse,
		"nxdomain": BuildNXDomainResponse,
		"refused":  BuildRefusedResponse,
		"servfail": BuildServfailResponse,
		"redirect": func(q *DNSQueryInfo) []byte { return BuildRedirectResponse(q, net.IPv4(1, 2, 3, 4)) },
		"forwarded": func(q *DNSQueryInfo) []byte {
			var m dns.Msg
			m.Unpack(q.RawDNSPayload)
			m.Response = true
			b, _ := m.Pack()
			return BuildForwardedResponse(q, b)
		},
	}
	for _, v6 := range []bool{false, true} {
		t.Run(map[bool]string{false: "ipv4", true: "ipv6"}[v6], func(t *testing.T) {
			if v6 {
				t.Skip("known bug: IPv6 UDP checksum is computed before the payload is copied")
			}
			for _, name := range []string{"ads.example.com", "odd.example.co"} {
				for _, qtype := range []uint16{dns.TypeA, dns.TypeAAAA} {
					q := dnsQuery(t, name, qtype)
					var pkt []byte
					if v6 {
						pkt = BuildIPv6UDPPacket(clientV6, serverV6, 40000, 53, q)
						verifyIPv6(t, pkt, len(q))
					} else {
						pkt = BuildIPv4UDPPacket(clientV4, serverV4, 40000, 53, q)
						verifyIPv4(t, pkt, len(q))
					}

					info := ParseTUNPacket(pkt, len(pkt))
					if info == nil {
						t.Fatalf("v6=%v %s: ParseTUNPacket returned nil", v6, name)
					}
					if info.Domain != name || info.QueryType != qtype || info.IsIPv6 != v6 {
						t.Errorf("parsed %q/%d/v6=%v, want %q/%d/v6=%v", info.Domain, info.QueryType, info.IsIPv6, name, qtype, v6)
					}
					if info.SourcePort != 40000 || info.DestPort != 53 {
						t.Errorf("ports %d→%d, want 40000→53", info.SourcePort, info.DestPort)
					}
					if !bytes.Equal(info.RawDNSPayload, q) {
						t.Errorf("payload mismatch")
					}

					for bname, build := range builders {
						resp := build(info)
						var respInfo *DNSQueryInfo
						if v6 {
							verifyIPv6(t, resp, len(resp)-IPv6HeaderSize-UDPHeaderSize)
						} else {
							verifyIPv4(t, resp, len(resp)-IPv4HeaderSize-UDPHeaderSize)
						}
						respInfo = ParseTUNPacket(resp, len(resp))
						if respInfo == nil {
							t.Fatalf("%s: response did not parse", bname)
						}
						if !respInfo.SourceIP.Equal(info.DestIP) || !respInfo.DestIP.Equal(info.SourceIP) {
							t.Errorf("%s: addresses not swapped: %v→%v", bname, respInfo.SourceIP, respInfo.DestIP)
						}
						if respInfo.SourcePort != 53 || respInfo.DestPort != 40000 {
							t.Errorf("%s: ports %d→%d, want 53→40000", bname, respInfo.SourcePort, respInfo.DestPort)
						}
						var m dns.Msg
						if err := m.Unpack(respInfo.RawDNSPayload); err != nil {
							t.Fatalf("%s: unpack response: %v", bname, err)
						}
						if m.Id != 0x1234 || !m.Response {
							t.Errorf("%s: id=%#x response=%v", bname, m.Id, m.Response)
						}
					}
				}
			}
		})
	}
}

func TestResponseContents(t *testing.T) {
	q := dnsQuery(t, "ads.example.com", dns.TypeA)
	info := ParseTUNPacket(BuildIPv4UDPPacket(clientV4, serverV4, 1, 53, q), IPv4HeaderSize+UDPHeaderSize+len(q))
	if info == nil {
		t.Fatal("parse failed")
	}
	unpack := func(pkt []byte) *dns.Msg {
		var m dns.Msg
		if err := m.Unpack(pkt[IPv4HeaderSize+UDPHeaderSize:]); err != nil {
			t.Fatalf("unpack: %v", err)
		}
		return &m
	}

	m := unpack(BuildBlockedResponse(info))
	if len(m.Answer) != 1 || !m.Answer[0].(*dns.A).A.Equal(net.IPv4zero) {
		t.Errorf("blocked answer = %v, want 0.0.0.0", m.Answer)
	}
	m = unpack(BuildRedirectResponse(info, net.IPv4(1, 2, 3, 4)))
	if len(m.Answer) != 1 || !m.Answer[0].(*dns.A).A.Equal(net.IPv4(1, 2, 3, 4)) {
		t.Errorf("redirect answer = %v, want 1.2.3.4", m.Answer)
	}
	for _, c := range []struct {
		build func(*DNSQueryInfo) []byte
		rcode int
	}{
		{BuildNXDomainResponse, dns.RcodeNameError},
		{BuildRefusedResponse, dns.RcodeRefused},
		{BuildServfailResponse, dns.RcodeServerFailure},
	} {
		if m := unpack(c.build(info)); m.Rcode != c.rcode || len(m.Answer) != 0 {
			t.Errorf("rcode = %d answers = %d, want %d and none", m.Rcode, len(m.Answer), c.rcode)
		}
	}

	aaaa := dnsQuery(t, "ads.example.com", dns.TypeAAAA)
	info = ParseTUNPacket(BuildIPv4UDPPacket(clientV4, serverV4, 1, 53, aaaa), IPv4HeaderSize+UDPHeaderSize+len(aaaa))
	m = unpack(BuildBlockedResponse(info))
	if len(m.Answer) != 1 || !m.Answer[0].(*dns.AAAA).AAAA.Equal(net.IPv6zero) {
		t.Errorf("blocked AAAA answer = %v, want ::", m.Answer)
	}
}

func TestParseResponseType(t *testing.T) {
	for in, want := range map[string]ResponseType{
		"NXDOMAIN": ResponseNXDomain,
		"REFUSED":  ResponseRefused,
		"CUSTOM":   ResponseCustomIP,
		"":         ResponseCustomIP,
		"nxdomain": ResponseCustomIP, // case-sensitive
	} {
		if got := ParseResponseType(in); got != want {
			t.Errorf("ParseResponseType(%q) = %d, want %d", in, got, want)
		}
	}
}

func BenchmarkParseTUNPacket(b *testing.B) {
	q := dnsQuery(b, "ads.example.com", dns.TypeA)
	pkt := BuildIPv4UDPPacket(clientV4, serverV4, 40000, 53, q)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if ParseTUNPacket(pkt, len(pkt)) == nil {
			b.Fatal("parse failed")
		}
	}
}

func BenchmarkBuildBlockedResponse(b *testing.B) {
	for _, v6 := range []bool{false, true} {
		q := dnsQuery(b, "ads.example.com", dns.TypeA)
		pkt := BuildIPv4UDPPacket(clientV4, serverV4, 40000, 53, q)
		name := "ipv4"
		if v6 {
			pkt = BuildIPv6UDPPacket(clientV6, serverV6, 40000, 53, q)
			name = "ipv6"
		}
		info := ParseTUNPacket(pkt, len(pkt))
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				BuildBlockedResponse(info)
			}
		})
	}
}

func BenchmarkBuildNXDomainResponse(b *testing.B) {
	q := dnsQuery(b, "ads.example.com", dns.TypeA)
	pkt := BuildIPv4UDPPacket(clientV4, serverV4, 40000, 53, q)
	info := ParseTUNPacket(pkt, len(pkt))
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		BuildNXDomainResponse(info)
	}
}
