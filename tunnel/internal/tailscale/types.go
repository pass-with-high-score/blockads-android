package tailscale

import "time"

// ControlKeyResponse represents the response from GET /key?v=...
type ControlKeyResponse struct {
	LegacyPublicKey string `json:"legacyPublicKey"`
	PublicKey       string `json:"publicKey"`
}

// NetInfo describes the client's network connectivity and home DERP region.
type NetInfo struct {
	PreferredDERP int `json:"PreferredDERP,omitempty"`
}

// Service describes a service or meta-service running on the node (e.g. peerapi4).
type Service struct {
	Proto       string `json:"Proto"`
	Port        uint16 `json:"Port"`
	Description string `json:"Description,omitempty"`
}

// Hostinfo carries basic client system information.
type Hostinfo struct {
	OS          string    `json:"OS,omitempty"`
	OSVersion   string    `json:"OSVersion,omitempty"`
	Hostname    string    `json:"Hostname,omitempty"`
	DeviceModel string    `json:"DeviceModel,omitempty"`
	RoutableIPs []string  `json:"RoutableIPs,omitempty"`
	Frontend    string    `json:"FrontendLogID,omitempty"`
	Backend     string    `json:"BackendLogID,omitempty"`
	NetInfo     *NetInfo  `json:"NetInfo,omitempty"`
	Services    []Service `json:"Services,omitempty"`
}

// AuthBlock holds credentials for machine registration.
type AuthBlock struct {
	AuthKey string `json:"AuthKey,omitempty"`
}

// RegisterRequest is sent via POST /machine/register.
type RegisterRequest struct {
	Version      int       `json:"Version"`
	NodeKey      string    `json:"NodeKey"`
	OldNodeKey   string    `json:"OldNodeKey,omitempty"`
	Auth         AuthBlock `json:"Auth,omitempty"`
	Expiry       time.Time `json:"Expiry,omitempty"`
	Followup     string    `json:"Followup,omitempty"`
	Hostinfo     *Hostinfo `json:"Hostinfo,omitempty"`
	Ephemeral    bool      `json:"Ephemeral,omitempty"`
}

// RegisterResponse is returned from POST /machine/register.
type RegisterResponse struct {
	MachineAuthorized bool   `json:"MachineAuthorized"`
	NodeKeyExpired    bool   `json:"NodeKeyExpired"`
	AuthURL           string `json:"AuthURL,omitempty"`
	Error             string `json:"Error,omitempty"`
	Login             struct {
		LoginName   string `json:"LoginName"`
		DisplayName string `json:"DisplayName"`
	} `json:"Login"`
	NodeKey string `json:"NodeKey,omitempty"`
}

// MapRequest is sent via POST /machine/map to subscribe to network updates.
type MapRequest struct {
	Version    int       `json:"Version"`
	Compress   string    `json:"Compress,omitempty"`
	KeepAlive  bool      `json:"KeepAlive,omitempty"`
	NodeKey    string    `json:"NodeKey"`
	DiscoKey   string    `json:"DiscoKey"`
	Endpoints  []string  `json:"Endpoints"`
	Stream     bool      `json:"Stream"`
	Hostinfo   *Hostinfo `json:"Hostinfo,omitempty"`
	OmitPeers  bool      `json:"OmitPeers"`
}

// Node represents a machine in the Tailscale network.
type Node struct {
	ID         int64      `json:"ID"`
	StableID   string     `json:"StableID"`
	Name       string     `json:"Name"`
	User       int64      `json:"User"`
	Key        string     `json:"Key"`
	DiscoKey   string     `json:"DiscoKey"`
	Addresses  []string   `json:"Addresses"`
	AllowedIPs []string   `json:"AllowedIPs"`
	Endpoints  []string   `json:"Endpoints"`
	Addrs      []string   `json:"Addrs,omitempty"`
	DERP       string     `json:"DERP"`
	HomeDERP   int        `json:"HomeDERP,omitempty"`
	Hostinfo   *Hostinfo  `json:"Hostinfo,omitempty"`
	Online     *bool      `json:"Online,omitempty"`
	LastSeen   *time.Time `json:"LastSeen,omitempty"`
	KeyExpiry  *time.Time `json:"KeyExpiry,omitempty"`
	KeyExpired bool       `json:"KeyExpired,omitempty"`
}

// DERPNode represents a DERP relay server.
type DERPNode struct {
	Name     string `json:"Name"`
	RegionID int    `json:"RegionID"`
	HostName string `json:"HostName"`
	IPv4     string `json:"IPv4,omitempty"`
	IPv6     string `json:"IPv6,omitempty"`
	Port     int    `json:"Port,omitempty"`
	DERPPort int    `json:"DERPPort,omitempty"`
}

// DERPRegion represents a geographic group of DERP relay nodes.
type DERPRegion struct {
	RegionID   int         `json:"RegionID"`
	RegionCode string      `json:"RegionCode"`
	RegionName string      `json:"RegionName"`
	Nodes      []*DERPNode `json:"Nodes"`
}

// DERPMap is the collection of all available DERP regions.
type DERPMap struct {
	Regions map[int]*DERPRegion `json:"Regions"`
}

// DNSResolver represents an upstream resolver in DNSConfig.
type DNSResolver struct {
	Addr string `json:"Addr"`
}

// DNSConfig specifies nameservers and MagicDNS search paths.
type DNSConfig struct {
	Resolvers []DNSResolver            `json:"Resolvers,omitempty"`
	Routes    map[string][]DNSResolver `json:"Routes,omitempty"`
	Domains   []string                 `json:"Domains,omitempty"`
	Proxied   bool                     `json:"Proxied,omitempty"`
}

// MapResponse represents an update stream frame from POST /machine/map.
type MapResponse struct {
	KeepAlive    bool        `json:"KeepAlive,omitempty"`
	Node         *Node       `json:"Node,omitempty"`
	Peers        []*Node     `json:"Peers,omitempty"`
	PeersChanged []*Node     `json:"PeersChanged,omitempty"`
	PeersRemoved []int64     `json:"PeersRemoved,omitempty"`
	DERPMap      *DERPMap    `json:"DERPMap,omitempty"`
	DNSConfig    *DNSConfig  `json:"DNSConfig,omitempty"`
	Domain       string      `json:"Domain,omitempty"`
}
