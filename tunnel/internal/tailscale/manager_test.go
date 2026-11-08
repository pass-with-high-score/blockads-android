package tailscale

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"
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

func TestManagerExitNodeSelectionAndLeakGuard(t *testing.T) {
	st, err := NewState("")
	if err != nil {
		t.Fatalf("NewState: %v", err)
	}
	mgr := NewManager(st, nil)
	defer mgr.Stop()

	peerSt, _ := NewState("")
	mgr.handleMapResponse(&MapResponse{
		Node: &Node{
			ID:        1,
			Name:      "self.tail.ts.net.",
			Key:       st.NodeKey().PublicHex("nodekey:"),
			Addresses: []string{"100.64.0.1/32"},
		},
		Peers: []*Node{
			{
				ID:         2,
				Name:       "exit-mac.tail.ts.net.",
				Key:        peerSt.NodeKey().PublicHex("nodekey:"),
				Addresses:  []string{"100.64.0.2/32"},
				AllowedIPs: []string{"100.64.0.2/32", "0.0.0.0/0", "::/0"},
			},
		},
	})

	// 1. With activeExitID == 0, public internet IP (8.8.8.8) must NOT match the exit-capable peer!
	if p := mgr.MatchPeer(net.ParseIP("8.8.8.8")); p != nil {
		t.Fatalf("expected 8.8.8.8 not to match peer when exit node is disabled, got %+v", p)
	}

	// 2. Peer's own Tailscale IP (100.64.0.2) MUST match Peer 2.
	if p := mgr.MatchPeer(net.ParseIP("100.64.0.2")); p == nil || p.ID != 2 {
		t.Fatalf("expected 100.64.0.2 to match peer 2, got %+v", p)
	}

	// 3. Unknown Tailscale CGNAT IP (100.99.88.77) must fail closed (used=true, err!=nil) to prevent Wi-Fi leak.
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, used, err := mgr.DialTCP(ctx, net.ParseIP("100.99.88.77"), 80)
	if !used || err == nil {
		t.Fatalf("expected fail-closed leak guard for unknown Tailscale IP 100.99.88.77, got used=%v err=%v", used, err)
	}

	// 4. BlockAds synthetic DNS IP (100.64.100.1) must NOT be treated as a Tailscale CGNAT peer.
	if IsTailscaleCGNAT(net.ParseIP("100.64.100.1")) || IsTailscaleCGNAT(net.ParseIP("100.64.100.2")) {
		t.Fatalf("expected BlockAds synthetic TUN/DNS IPs to be excluded from IsTailscaleCGNAT")
	}

	// 5. Enabling exit node ID=2 routes 8.8.8.8 to Peer 2.
	mgr.SetExitNode(2)
	if p := mgr.MatchPeer(net.ParseIP("8.8.8.8")); p == nil || p.ID != 2 {
		t.Fatalf("expected 8.8.8.8 to match exit node 2 when enabled, got %+v", p)
	}
}
