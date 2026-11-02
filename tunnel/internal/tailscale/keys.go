package tailscale

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"

	"golang.org/x/crypto/curve25519"
)

// KeyPair represents a Curve25519 private and public key.
type KeyPair struct {
	Private [32]byte `json:"-"`
	Public  [32]byte `json:"-"`
}

// NewKeyPair generates a new random Curve25519 keypair.
func NewKeyPair() (*KeyPair, error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return nil, fmt.Errorf("generate keypair: %w", err)
	}
	// Clamp private key for Curve25519
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &priv)
	return &KeyPair{Private: priv, Public: pub}, nil
}

// KeyPairFromHex loads a keypair from a 64-character hex string representing the private key.
func KeyPairFromHex(hexPriv string) (*KeyPair, error) {
	hexPriv = strings.TrimSpace(hexPriv)
	b, err := hex.DecodeString(hexPriv)
	if err != nil || len(b) != 32 {
		return nil, fmt.Errorf("invalid private key hex (must be 32 bytes)")
	}
	var priv [32]byte
	copy(priv[:], b)
	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &priv)
	return &KeyPair{Private: priv, Public: pub}, nil
}

// PrivateHex returns the hex-encoded private key.
func (kp *KeyPair) PrivateHex() string {
	return hex.EncodeToString(kp.Private[:])
}

// PublicHex returns the hex-encoded public key.
func (kp *KeyPair) PublicHex(prefix string) string {
	return prefix + hex.EncodeToString(kp.Public[:])
}

// State holds the persistent cryptographic state and identity for a Tailscale node.
type State struct {
	MachineKeyHex string `json:"machine_key"`
	NodeKeyHex    string `json:"node_key"`
	DiscoKeyHex   string `json:"disco_key"`
	NodeID        int64  `json:"node_id,omitempty"`
	NodeName      string `json:"node_name,omitempty"`
	ControlURL    string `json:"control_url,omitempty"`
	TailscaleIPs  []string `json:"tailscale_ips,omitempty"`
	AuthKey       string `json:"auth_key,omitempty"`

	machineKey *KeyPair
	nodeKey    *KeyPair
	discoKey   *KeyPair
}

// NewState creates a fresh identity with new machine, node, and disco keys.
func NewState(controlURL string) (*State, error) {
	if controlURL == "" {
		controlURL = "https://controlplane.tailscale.com"
	}
	mKey, err := NewKeyPair()
	if err != nil {
		return nil, err
	}
	nKey, err := NewKeyPair()
	if err != nil {
		return nil, err
	}
	dKey, err := NewKeyPair()
	if err != nil {
		return nil, err
	}

	return &State{
		MachineKeyHex: mKey.PrivateHex(),
		NodeKeyHex:    nKey.PrivateHex(),
		DiscoKeyHex:   dKey.PrivateHex(),
		ControlURL:    controlURL,
		machineKey:    mKey,
		nodeKey:       nKey,
		discoKey:      dKey,
	}, nil
}

// StateFromJSON parses a JSON string into a State.
func StateFromJSON(raw string) (*State, error) {
	s := &State{}
	if err := json.Unmarshal([]byte(raw), s); err != nil {
		return nil, err
	}
	if s.ControlURL == "" {
		s.ControlURL = "https://controlplane.tailscale.com"
	}
	var err error
	if s.MachineKeyHex != "" {
		s.machineKey, err = KeyPairFromHex(s.MachineKeyHex)
		if err != nil {
			return nil, fmt.Errorf("invalid machine key: %w", err)
		}
	} else {
		s.machineKey, err = NewKeyPair()
		if err != nil {
			return nil, err
		}
		s.MachineKeyHex = s.machineKey.PrivateHex()
	}

	if s.NodeKeyHex != "" {
		s.nodeKey, err = KeyPairFromHex(s.NodeKeyHex)
		if err != nil {
			return nil, fmt.Errorf("invalid node key: %w", err)
		}
	} else {
		s.nodeKey, err = NewKeyPair()
		if err != nil {
			return nil, err
		}
		s.NodeKeyHex = s.nodeKey.PrivateHex()
	}

	if s.DiscoKeyHex != "" {
		s.discoKey, err = KeyPairFromHex(s.DiscoKeyHex)
		if err != nil {
			return nil, fmt.Errorf("invalid disco key: %w", err)
		}
	} else {
		s.discoKey, err = NewKeyPair()
		if err != nil {
			return nil, err
		}
		s.DiscoKeyHex = s.discoKey.PrivateHex()
	}

	return s, nil
}

// ToJSON serializes State to a JSON string for persistent storage.
func (s *State) ToJSON() string {
	b, err := json.Marshal(s)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// MachineKey returns the machine keypair.
func (s *State) MachineKey() *KeyPair { return s.machineKey }

// NodeKey returns the node keypair.
func (s *State) NodeKey() *KeyPair { return s.nodeKey }

// DiscoKey returns the disco keypair.
func (s *State) DiscoKey() *KeyPair { return s.discoKey }
