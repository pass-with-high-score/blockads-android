package tunnel

import (
	"encoding/binary"
	"net"
)

type packetAction int

const (
	packetActionForward packetAction = iota
	packetActionReset
	packetActionDrop
)

// classifyNonDNSPacket inspects a non-DNS packet from the TUN device to determine
// whether it should be rejected with a TCP RST, silently dropped, or forwarded
// to the active outbound adapter (e.g. WireGuard).
func classifyNonDNSPacket(packet []byte, length int, isDoHBlocking bool) packetAction {
	if length < ipv4HeaderSize {
		return packetActionForward
	}

	version := packet[0] >> 4
	var proto byte
	var dstIP net.IP
	var dstPort uint16

	switch version {
	case 4:
		if length < ipv4HeaderSize+4 { // At least IP header + ports
			return packetActionForward
		}
		ihl := int(packet[0]&0x0F) * 4
		if length < ihl+4 {
			return packetActionForward
		}
		proto = packet[9]
		dstIP = net.IP(packet[16:20])
		dstPort = binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])

	case 6:
		if length < ipv6HeaderSize+4 {
			return packetActionForward
		}
		proto = packet[6]
		dstIP = net.IP(packet[24:40])
		dstPort = binary.BigEndian.Uint16(packet[ipv6HeaderSize+2 : ipv6HeaderSize+4])

	default:
		return packetActionForward
	}

	// 1. Destination is NULL IP (0.0.0.0 or ::) or loopback (127.0.0.0/8 or ::1).
	// These are dummy IPs returned for blocked ad domains. In WireGuard mode,
	// routing packets to 0.0.0.0 into WireGuard causes the remote server to silently
	// drop them, making the browser hang for 8s per domain. Sending an immediate TCP RST
	// makes browser fetches fail instantly (ECONNREFUSED) with 0 latency.
	if isNullOrLoopbackIP(dstIP) {
		if proto == 6 {
			return packetActionReset
		}
		return packetActionDrop
	}

	// 2. Destination is our virtual synthetic DNS IP (100.64.100.1 or fd00::1) on non-DNS port
	if isVirtualDnsIP(dstIP) {
		if proto == 6 {
			return packetActionReset
		}
		return packetActionDrop
	}

	// 3. Destination port 853 (DNS over TLS / DoT).
	// Resetting TCP port 853 forces Android Private DNS to fail fast and fall back to
	// standard port 53 UDP DNS, where BlockAds ad-blocking rules are enforced.
	if dstPort == 853 {
		if proto == 6 {
			return packetActionReset
		}
		return packetActionDrop
	}

	// 4. DNS over TCP (port 53, proto == 6).
	// Resetting forces clients to fall back to UDP port 53.
	if dstPort == 53 && proto == 6 {
		return packetActionReset
	}

	// 5. Hardcoded DoH Direct-IP (port 443) or browser QUIC to known public DoH IPs.
	if isDoHBlocking && isKnownPublicDoHIP(dstIP) && dstPort == 443 {
		if proto == 6 {
			return packetActionReset
		}
		return packetActionDrop
	}

	return packetActionForward
}

// classifyPacketFirewall checks whether a raw IPv4/IPv6 TCP or UDP packet belongs
// to a firewalled app in packet-routed modes (e.g. WireGuard).
func (e *Engine) classifyPacketFirewall(packet []byte, length int) packetAction {
	if !e.firewallEnabled.Load() || e.firewallChecker == nil || length < ipv4HeaderSize {
		return packetActionForward
	}

	version := packet[0] >> 4
	var proto byte
	var srcIP, dstIP net.IP
	var srcPort, dstPort uint16

	switch version {
	case 4:
		ihl := int(packet[0]&0x0F) * 4
		if length < ihl+4 {
			return packetActionForward
		}
		proto = packet[9]
		if proto != ProtocolTCP && proto != ProtocolUDP {
			return packetActionForward
		}
		srcIP = net.IP(packet[12:16])
		dstIP = net.IP(packet[16:20])
		srcPort = binary.BigEndian.Uint16(packet[ihl : ihl+2])
		dstPort = binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])
	case 6:
		if length < ipv6HeaderSize+4 {
			return packetActionForward
		}
		proto = packet[6]
		if proto != ProtocolTCP && proto != ProtocolUDP {
			return packetActionForward
		}
		srcIP = net.IP(packet[8:24])
		dstIP = net.IP(packet[24:40])
		srcPort = binary.BigEndian.Uint16(packet[ipv6HeaderSize : ipv6HeaderSize+2])
		dstPort = binary.BigEndian.Uint16(packet[ipv6HeaderSize+2 : ipv6HeaderSize+4])
	default:
		return packetActionForward
	}

	flow := flowID{
		appIP:      srcIP,
		appPort:    int(srcPort),
		serverIP:   dstIP,
		serverPort: int(dstPort),
	}
	if e.isFlowFirewalled(flow, int(proto)) {
		if proto == ProtocolTCP {
			return packetActionReset
		}
		return packetActionDrop
	}
	return packetActionForward
}
