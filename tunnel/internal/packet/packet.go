package packet

import (
	"encoding/binary"
	"net"
	"strings"

	"github.com/miekg/dns"
)

// ResponseType determines how blocked domains are responded to.
type ResponseType int

const (
	ResponseCustomIP ResponseType = iota // 0.0.0.0
	ResponseNXDomain                     // NXDOMAIN
	ResponseRefused                      // REFUSED
)

// ParseResponseType converts a string to ResponseType.
func ParseResponseType(s string) ResponseType {
	switch s {
	case "NXDOMAIN":
		return ResponseNXDomain
	case "REFUSED":
		return ResponseRefused
	default:
		return ResponseCustomIP
	}
}

const (
	IPv4HeaderSize = 20
	IPv6HeaderSize = 40
	UDPHeaderSize  = 8

	ipv4HeaderSize = IPv4HeaderSize
	ipv6HeaderSize = IPv6HeaderSize
	udpHeaderSize  = UDPHeaderSize
)

// DNSQueryInfo holds parsed DNS query information from a raw TUN packet.
type DNSQueryInfo struct {
	SourceIP      net.IP
	DestIP        net.IP
	SourcePort    uint16
	DestPort      uint16
	RawDNSPayload []byte
	Domain        string
	QueryType     uint16
	IsIPv6        bool
}

// ParseTUNPacket parses a raw IP packet from the TUN device and extracts DNS query info.
func ParseTUNPacket(packet []byte, length int) *DNSQueryInfo {
	if length < ipv4HeaderSize {
		return nil
	}

	version := packet[0] >> 4
	var info *DNSQueryInfo

	switch version {
	case 4:
		info = parseIPv4Packet(packet, length)
	case 6:
		info = parseIPv6Packet(packet, length)
	default:
		return nil
	}

	if info == nil {
		return nil
	}

	var msg dns.Msg
	if err := msg.Unpack(info.RawDNSPayload); err != nil {
		return nil
	}

	if len(msg.Question) == 0 {
		return nil
	}

	q := msg.Question[0]
	info.Domain = strings.TrimSuffix(q.Name, ".")
	info.QueryType = q.Qtype

	return info
}

func parseIPv4Packet(packet []byte, length int) *DNSQueryInfo {
	if length < ipv4HeaderSize+udpHeaderSize {
		return nil
	}

	if packet[9] != 17 {
		return nil
	}

	ihl := int(packet[0]&0x0F) * 4
	if length < ihl+udpHeaderSize {
		return nil
	}

	sourceIP := make(net.IP, 4)
	copy(sourceIP, packet[12:16])
	destIP := make(net.IP, 4)
	copy(destIP, packet[16:20])

	udpStart := ihl
	sourcePort := binary.BigEndian.Uint16(packet[udpStart : udpStart+2])
	destPort := binary.BigEndian.Uint16(packet[udpStart+2 : udpStart+4])

	dnsStart := udpStart + udpHeaderSize
	if length <= dnsStart {
		return nil
	}

	dnsPayload := make([]byte, length-dnsStart)
	copy(dnsPayload, packet[dnsStart:length])

	return &DNSQueryInfo{
		SourceIP:      sourceIP,
		DestIP:        destIP,
		SourcePort:    sourcePort,
		DestPort:      destPort,
		RawDNSPayload: dnsPayload,
		IsIPv6:        false,
	}
}

func parseIPv6Packet(packet []byte, length int) *DNSQueryInfo {
	if length < ipv6HeaderSize+udpHeaderSize {
		return nil
	}

	if packet[6] != 17 {
		return nil
	}

	sourceIP := make(net.IP, 16)
	copy(sourceIP, packet[8:24])
	destIP := make(net.IP, 16)
	copy(destIP, packet[24:40])

	udpStart := ipv6HeaderSize
	sourcePort := binary.BigEndian.Uint16(packet[udpStart : udpStart+2])
	destPort := binary.BigEndian.Uint16(packet[udpStart+2 : udpStart+4])

	dnsStart := udpStart + udpHeaderSize
	if length <= dnsStart {
		return nil
	}

	dnsPayload := make([]byte, length-dnsStart)
	copy(dnsPayload, packet[dnsStart:length])

	return &DNSQueryInfo{
		SourceIP:      sourceIP,
		DestIP:        destIP,
		SourcePort:    sourcePort,
		DestPort:      destPort,
		RawDNSPayload: dnsPayload,
		IsIPv6:        true,
	}
}

// BuildBlockedResponse builds a DNS response that returns 0.0.0.0 for a blocked domain.
func BuildBlockedResponse(queryInfo *DNSQueryInfo) []byte {
	var msg dns.Msg
	msg.Unpack(queryInfo.RawDNSPayload)

	resp := new(dns.Msg)
	resp.SetReply(&msg)
	resp.RecursionAvailable = true

	if len(msg.Question) > 0 {
		q := msg.Question[0]
		switch q.Qtype {
		case dns.TypeA:
			resp.Answer = append(resp.Answer, &dns.A{
				Hdr: dns.RR_Header{Name: q.Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 300},
				A:   net.IPv4zero,
			})
		case dns.TypeAAAA:
			resp.Answer = append(resp.Answer, &dns.AAAA{
				Hdr:  dns.RR_Header{Name: q.Name, Rrtype: dns.TypeAAAA, Class: dns.ClassINET, Ttl: 300},
				AAAA: net.IPv6zero,
			})
		}
	}

	dnsResp, _ := resp.Pack()
	return buildIPUDPPacket(queryInfo, dnsResp)
}

// BuildNXDomainResponse builds a DNS NXDOMAIN response.
func BuildNXDomainResponse(queryInfo *DNSQueryInfo) []byte {
	var msg dns.Msg
	msg.Unpack(queryInfo.RawDNSPayload)

	resp := new(dns.Msg)
	resp.SetRcode(&msg, dns.RcodeNameError)
	resp.RecursionAvailable = true

	dnsResp, _ := resp.Pack()
	return buildIPUDPPacket(queryInfo, dnsResp)
}

// BuildRefusedResponse builds a DNS REFUSED response.
func BuildRefusedResponse(queryInfo *DNSQueryInfo) []byte {
	var msg dns.Msg
	msg.Unpack(queryInfo.RawDNSPayload)

	resp := new(dns.Msg)
	resp.SetRcode(&msg, dns.RcodeRefused)
	resp.RecursionAvailable = true

	dnsResp, _ := resp.Pack()
	return buildIPUDPPacket(queryInfo, dnsResp)
}

// BuildServfailResponse builds a DNS SERVFAIL response.
func BuildServfailResponse(queryInfo *DNSQueryInfo) []byte {
	var msg dns.Msg
	msg.Unpack(queryInfo.RawDNSPayload)

	resp := new(dns.Msg)
	resp.SetRcode(&msg, dns.RcodeServerFailure)
	resp.RecursionAvailable = true

	dnsResp, _ := resp.Pack()
	return buildIPUDPPacket(queryInfo, dnsResp)
}

// BuildRedirectResponse builds a DNS response that redirects to a specific IPv4 address.
func BuildRedirectResponse(queryInfo *DNSQueryInfo, ip net.IP) []byte {
	var msg dns.Msg
	msg.Unpack(queryInfo.RawDNSPayload)

	resp := new(dns.Msg)
	resp.SetReply(&msg)
	resp.RecursionAvailable = true

	if len(msg.Question) > 0 {
		q := msg.Question[0]
		if q.Qtype == dns.TypeA {
			resp.Answer = append(resp.Answer, &dns.A{
				Hdr: dns.RR_Header{Name: q.Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 300},
				A:   ip.To4(),
			})
		}
	}

	dnsResp, _ := resp.Pack()
	return buildIPUDPPacket(queryInfo, dnsResp)
}

// BuildForwardedResponse wraps a raw DNS response in IP+UDP headers.
func BuildForwardedResponse(queryInfo *DNSQueryInfo, dnsResp []byte) []byte {
	return buildIPUDPPacket(queryInfo, dnsResp)
}

func buildIPUDPPacket(queryInfo *DNSQueryInfo, payload []byte) []byte {
	if queryInfo.IsIPv6 {
		return BuildIPv6UDPPacket(queryInfo.DestIP, queryInfo.SourceIP, queryInfo.DestPort, queryInfo.SourcePort, payload)
	}
	return BuildIPv4UDPPacket(queryInfo.DestIP, queryInfo.SourceIP, queryInfo.DestPort, queryInfo.SourcePort, payload)
}

// BuildIPv4UDPPacket constructs an IPv4 UDP packet.
func BuildIPv4UDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := udpHeaderSize + len(payload)
	totalLen := ipv4HeaderSize + udpLen
	packet := make([]byte, totalLen)

	// IPv4 header
	packet[0] = 0x45
	binary.BigEndian.PutUint16(packet[2:4], uint16(totalLen))
	packet[8] = 64
	packet[9] = 17
	copy(packet[12:16], srcIP.To4())
	copy(packet[16:20], dstIP.To4())

	csum := calculateChecksum(packet[:ipv4HeaderSize])
	binary.BigEndian.PutUint16(packet[10:12], csum)

	udpOffset := ipv4HeaderSize
	binary.BigEndian.PutUint16(packet[udpOffset:udpOffset+2], srcPort)
	binary.BigEndian.PutUint16(packet[udpOffset+2:udpOffset+4], dstPort)
	binary.BigEndian.PutUint16(packet[udpOffset+4:udpOffset+6], uint16(udpLen))

	copy(packet[udpOffset+udpHeaderSize:], payload)
	return packet
}

// BuildIPv6UDPPacket constructs an IPv6 UDP packet.
func BuildIPv6UDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := udpHeaderSize + len(payload)
	totalLen := ipv6HeaderSize + udpLen
	packet := make([]byte, totalLen)

	packet[0] = 0x60
	binary.BigEndian.PutUint16(packet[4:6], uint16(udpLen))
	packet[6] = 17
	packet[7] = 64
	copy(packet[8:24], srcIP.To16())
	copy(packet[24:40], dstIP.To16())

	udpOffset := ipv6HeaderSize
	binary.BigEndian.PutUint16(packet[udpOffset:udpOffset+2], srcPort)
	binary.BigEndian.PutUint16(packet[udpOffset+2:udpOffset+4], dstPort)
	binary.BigEndian.PutUint16(packet[udpOffset+4:udpOffset+6], uint16(udpLen))

	csum := calculateUDPIPv6Checksum(srcIP.To16(), dstIP.To16(), packet, udpOffset, udpLen)
	binary.BigEndian.PutUint16(packet[udpOffset+6:udpOffset+8], csum)

	copy(packet[udpOffset+udpHeaderSize:], payload)
	return packet
}

func calculateChecksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(data); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 != 0 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum >> 16) + (sum & 0xFFFF)
	}
	return ^uint16(sum)
}

func calculateUDPIPv6Checksum(srcIP, dstIP []byte, packet []byte, udpOffset, udpLen int) uint16 {
	var sum uint32

	for i := 0; i < 16; i += 2 {
		sum += uint32(srcIP[i])<<8 | uint32(srcIP[i+1])
	}
	for i := 0; i < 16; i += 2 {
		sum += uint32(dstIP[i])<<8 | uint32(dstIP[i+1])
	}
	sum += uint32(udpLen)
	sum += 17

	saved := binary.BigEndian.Uint16(packet[udpOffset+6 : udpOffset+8])
	binary.BigEndian.PutUint16(packet[udpOffset+6:udpOffset+8], 0)
	for i := udpOffset; i+1 < udpOffset+udpLen; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(packet[i : i+2]))
	}
	if udpLen%2 != 0 {
		sum += uint32(packet[udpOffset+udpLen-1]) << 8
	}
	binary.BigEndian.PutUint16(packet[udpOffset+6:udpOffset+8], saved)

	for sum > 0xFFFF {
		sum = (sum >> 16) + (sum & 0xFFFF)
	}
	result := ^uint16(sum)
	if result == 0 {
		result = 0xFFFF
	}
	return result
}
