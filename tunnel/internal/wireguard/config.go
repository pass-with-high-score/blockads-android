package wireguard

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
)

// WgConfig represents the JSON format of a WireGuard configuration.
// Must match Kotlin's WireGuardConfig serialization format.
type WgConfig struct {
	Interface WgInterface `json:"interfaceConfig"`
	Peers     []WgPeer    `json:"peers"`
}

// WgInterface is the [Interface] section.
type WgInterface struct {
	PrivateKey string   `json:"privateKey"`
	Address    []string `json:"address"`
	ListenPort *int     `json:"listenPort,omitempty"`
	DNS        []string `json:"dns"`
}

// WgPeer is a [Peer] section.
type WgPeer struct {
	PublicKey           string   `json:"publicKey"`
	PresharedKey        *string  `json:"presharedKey,omitempty"`
	Endpoint            *string  `json:"endpoint,omitempty"`
	AllowedIPs          []string `json:"allowedIPs"`
	PersistentKeepalive *int     `json:"persistentKeepalive,omitempty"`
}

// ParseWgConfigJSON parses a JSON string into WgConfig.
func ParseWgConfigJSON(jsonStr string) (*WgConfig, error) {
	var cfg WgConfig
	if err := json.Unmarshal([]byte(jsonStr), &cfg); err != nil {
		return nil, fmt.Errorf("invalid wireguard json: %w", err)
	}
	return &cfg, nil
}

// BuildIpcConfig converts a WgConfig into wireguard-go UAPI IPC configuration format.
func BuildIpcConfig(cfg *WgConfig) (string, error) {
	var sb strings.Builder

	privHex, err := base64ToHex(cfg.Interface.PrivateKey)
	if err != nil {
		return "", fmt.Errorf("interface private_key: %w", err)
	}
	sb.WriteString(fmt.Sprintf("private_key=%s\n", privHex))

	if cfg.Interface.ListenPort != nil && *cfg.Interface.ListenPort > 0 {
		sb.WriteString(fmt.Sprintf("listen_port=%d\n", *cfg.Interface.ListenPort))
	}

	for _, peer := range cfg.Peers {
		pubHex, err := base64ToHex(peer.PublicKey)
		if err != nil {
			return "", fmt.Errorf("peer public_key: %w", err)
		}
		sb.WriteString(fmt.Sprintf("public_key=%s\n", pubHex))

		if peer.PresharedKey != nil && *peer.PresharedKey != "" {
			pskHex, err := base64ToHex(*peer.PresharedKey)
			if err != nil {
				return "", fmt.Errorf("peer preshared_key: %w", err)
			}
			sb.WriteString(fmt.Sprintf("preshared_key=%s\n", pskHex))
		}

		if peer.Endpoint != nil && *peer.Endpoint != "" {
			sb.WriteString(fmt.Sprintf("endpoint=%s\n", *peer.Endpoint))
		}

		if peer.PersistentKeepalive != nil && *peer.PersistentKeepalive > 0 {
			sb.WriteString(fmt.Sprintf("persistent_keepalive_interval=%d\n", *peer.PersistentKeepalive))
		}

		for _, allowedIP := range peer.AllowedIPs {
			allowedIP = strings.TrimSpace(allowedIP)
			if allowedIP != "" {
				sb.WriteString(fmt.Sprintf("allowed_ip=%s\n", allowedIP))
			}
		}
	}

	return sb.String(), nil
}

func base64ToHex(b64 string) (string, error) {
	b64 = strings.TrimSpace(b64)
	bytes, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return "", fmt.Errorf("base64 decode failed: %w", err)
	}
	if len(bytes) != 32 {
		return "", fmt.Errorf("expected 32 bytes for key, got %d", len(bytes))
	}
	return hex.EncodeToString(bytes), nil
}
