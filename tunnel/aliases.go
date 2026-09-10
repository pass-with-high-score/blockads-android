package tunnel

import (
	"github.com/nqmgaming/blockads-tunnel/internal/bloom"
	internaldns "github.com/nqmgaming/blockads-tunnel/internal/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/mitm"
	"github.com/nqmgaming/blockads-tunnel/internal/packet"
	"github.com/nqmgaming/blockads-tunnel/internal/safesearch"
	"github.com/nqmgaming/blockads-tunnel/internal/scriptlet"
	"github.com/nqmgaming/blockads-tunnel/internal/trie"
	"github.com/nqmgaming/blockads-tunnel/internal/wireguard"
)

// Re-export types from internal packages via type aliases so that existing
// Engine fields, methods, and call sites work seamlessly without modification.
type BloomFilter = bloom.BloomFilter
type BloomBuilder = bloom.BloomBuilder
type MmapTrie = trie.MmapTrie
type SafeSearch = safesearch.SafeSearch
type SafeSearchResult = safesearch.SafeSearchResult
type SafeSearchAction = safesearch.SafeSearchAction
type ScriptletRule = scriptlet.Rule
type scriptletStore = scriptlet.Store

type CertManager = mitm.CertManager
type MitmFilter = mitm.MitmFilter

type WgConfig = wireguard.WgConfig
type WgInterface = wireguard.WgInterface
type WgPeer = wireguard.WgPeer
type WgOutbound = wireguard.WgOutbound
type channelTUN = wireguard.ChannelTUN

type DNSProtocol = internaldns.DNSProtocol
type Resolver = internaldns.Resolver

type DNSQueryInfo = packet.DNSQueryInfo
type ResponseType = packet.ResponseType
type packetPipe = packet.PacketPipe

const (
	ActionNone     = safesearch.ActionNone
	ActionRedirect = safesearch.ActionRedirect

	ProtocolPlain = internaldns.ProtocolPlain
	ProtocolDoH   = internaldns.ProtocolDoH
	ProtocolDoT   = internaldns.ProtocolDoT
	ProtocolDoQ   = internaldns.ProtocolDoQ

	ResponseCustomIP = packet.ResponseCustomIP
	ResponseNXDomain = packet.ResponseNXDomain
	ResponseRefused  = packet.ResponseRefused

	ipv4HeaderSize = packet.IPv4HeaderSize
	ipv6HeaderSize = packet.IPv6HeaderSize
	udpHeaderSize  = packet.UDPHeaderSize

	LocalAssetHost = mitm.LocalAssetHost
	caCertFile     = mitm.CACertFile
)

var (
	NewBloomBuilder     = bloom.NewBloomBuilder
	LoadBloomFilter     = bloom.LoadBloomFilter
	LoadMmapTrie        = trie.LoadMmapTrie
	NewSafeSearch       = safesearch.NewSafeSearch
	buildScriptletStore = scriptlet.BuildStore
	parseScriptletRules = scriptlet.ParseRules
	scriptletRuntimeJS  = scriptlet.RuntimeJS
)

// CompileFilterList compiles a raw filter list into Trie and Bloom filter binary artifacts.
func CompileFilterList(inputPath, triePath, bloomPath string) (int, error) {
	return trie.CompileFilterList(inputPath, triePath, bloomPath)
}

var (

	NewCertManager       = mitm.NewCertManager
	NewMitmFilter        = mitm.NewMitmFilter
	newMitmTcpHandler    = mitm.NewMitmTcpHandler
	newMitmUdpHandler    = mitm.NewMitmUdpHandler
	SetCosmeticCSS       = mitm.SetCosmeticCSS
	SetScriptletStore    = mitm.SetScriptletStore
	SetScriptletsRuntime = mitm.SetScriptletsRuntime
	ServeLocalAsset      = mitm.ServeLocalAsset

	ParseWgConfigJSON = wireguard.ParseWgConfigJSON
	BuildIpcConfig    = wireguard.BuildIpcConfig
	newChannelTUN     = wireguard.NewChannelTUN
	NewWgOutbound     = wireguard.NewWgOutbound

	ParseProtocol = internaldns.ParseProtocol
	NewResolver   = internaldns.NewResolver

	ParseResponseType      = packet.ParseResponseType
	ParseTUNPacket         = packet.ParseTUNPacket
	BuildBlockedResponse   = packet.BuildBlockedResponse
	BuildNXDomainResponse  = packet.BuildNXDomainResponse
	BuildRefusedResponse   = packet.BuildRefusedResponse
	BuildServfailResponse  = packet.BuildServfailResponse
	BuildRedirectResponse  = packet.BuildRedirectResponse
	BuildForwardedResponse = packet.BuildForwardedResponse
	buildIPv4UDPPacket     = packet.BuildIPv4UDPPacket
	buildIPv6UDPPacket     = packet.BuildIPv6UDPPacket
	newPacketPipe          = packet.NewPacketPipe
)
