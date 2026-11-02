package tailscale

import (
	"strings"
	"testing"
)

func TestKeyPair(t *testing.T) {
	kp, err := NewKeyPair()
	if err != nil {
		t.Fatalf("NewKeyPair failed: %v", err)
	}

	privHex := kp.PrivateHex()
	if len(privHex) != 64 {
		t.Fatalf("expected 64 hex chars, got %d", len(privHex))
	}

	mkey := kp.PublicHex("mkey:")
	if !strings.HasPrefix(mkey, "mkey:") || len(mkey) != 69 {
		t.Fatalf("invalid mkey format: %s", mkey)
	}

	kp2, err := KeyPairFromHex(privHex)
	if err != nil {
		t.Fatalf("KeyPairFromHex failed: %v", err)
	}
	if kp.Public != kp2.Public {
		t.Fatalf("public key mismatch after reload from hex")
	}
}

func TestStateSerialization(t *testing.T) {
	st, err := NewState("https://controlplane.tailscale.com")
	if err != nil {
		t.Fatalf("NewState failed: %v", err)
	}

	jsonStr := st.ToJSON()
	if !strings.Contains(jsonStr, "machine_key") {
		t.Fatalf("expected json to contain machine_key")
	}

	st2, err := StateFromJSON(jsonStr)
	if err != nil {
		t.Fatalf("StateFromJSON failed: %v", err)
	}

	if st.MachineKey().Public != st2.MachineKey().Public {
		t.Fatalf("machine key mismatch")
	}
	if st.NodeKey().Public != st2.NodeKey().Public {
		t.Fatalf("node key mismatch")
	}
}
