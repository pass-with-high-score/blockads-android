package tailscale

import (
	"encoding/json"
	"net"
	"testing"
)

func TestManagerPeerAndMagicDNS(t *testing.T) {
	st, err := NewState("https://controlplane.tailscale.com")
	if err != nil {
		t.Fatalf("NewState failed: %v", err)
	}

	mgr := NewManager(st, nil)

	online := true
	mockMapResp := &MapResponse{
		Node: &Node{
			ID:        1,
			Name:      "my-phone.ts.net.",
			Addresses: []string{"100.64.0.5/32"},
		},
		Peers: []*Node{
			{
				ID:         2,
				Name:       "nas.tail1234.ts.net.",
				Addresses:  []string{"100.64.0.10/32"},
				AllowedIPs: []string{"100.64.0.10/32", "192.168.1.0/24"},
				Online:     &online,
			},
			{
				ID:         3,
				Name:       "exit-node.tail1234.ts.net.",
				Addresses:  []string{"100.64.0.20/32"},
				AllowedIPs: []string{"0.0.0.0/0", "::/0"},
				Online:     &online,
			},
		},
	}

	mgr.handleMapResponse(mockMapResp)

	// Test MagicDNS resolution
	ip := mgr.ResolveMagicDNS("nas")
	if ip == nil || ip.String() != "100.64.0.10" {
		t.Fatalf("expected 100.64.0.10 for 'nas', got %v", ip)
	}

	ip2 := mgr.ResolveMagicDNS("nas.tail1234.ts.net")
	if ip2 == nil || ip2.String() != "100.64.0.10" {
		t.Fatalf("expected 100.64.0.10 for full FQDN, got %v", ip2)
	}

	// Test MatchPeer for AllowedIPs
	peer := mgr.MatchPeer(net.ParseIP("100.64.0.10"))
	if peer == nil || peer.ID != 2 {
		t.Fatalf("expected peer ID 2, got %v", peer)
	}

	subnetPeer := mgr.MatchPeer(net.ParseIP("192.168.1.50"))
	if subnetPeer == nil || subnetPeer.ID != 2 {
		t.Fatalf("expected subnet match for peer ID 2, got %v", subnetPeer)
	}

	// Test Exit node detection
	statusJSON := mgr.GetStatusJSON()
	var status Status
	if err := json.Unmarshal([]byte(statusJSON), &status); err != nil {
		t.Fatalf("unmarshal status JSON failed: %v", err)
	}
	if len(status.ExitNodes) != 1 || status.ExitNodes[0].ID != 3 {
		t.Fatalf("expected 1 exit node with ID 3, got %v", status.ExitNodes)
	}

	// Test Setting Exit Node
	mgr.SetExitNode(3)
	if mgr.activeExitID != 3 {
		t.Fatalf("expected activeExitID 3, got %d", mgr.activeExitID)
	}
}
