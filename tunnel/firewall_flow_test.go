package tunnel

import (
	"net"
	"testing"
)

func TestIsFlowFirewalled(t *testing.T) {
	flow := flowID{
		appIP:      net.ParseIP("100.64.100.2"),
		appPort:    54321,
		serverIP:   net.ParseIP("149.154.167.50"),
		serverPort: 443,
	}

	t.Run("disabled firewall skips UID resolution", func(t *testing.T) {
		uidPackageCache.Clear()
		e := NewEngine()
		uidr := &countingUIDResolver{uid: 10200}
		pkgr := &countingPackageResolver{pkg: "org.telegram.messenger"}
		e.SetUIDResolver(uidr)
		e.SetAppUidResolver(pkgr)
		e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"org.telegram.messenger": true}})
		e.SetFirewallEnabled(false)

		if e.isFlowFirewalled(flow, ProtocolTCP) {
			t.Fatal("expected false when firewall is disabled")
		}
		if uidr.calls.Load() != 0 || pkgr.calls.Load() != 0 {
			t.Fatalf("expected 0 resolver calls when disabled, got uidr=%d pkgr=%d", uidr.calls.Load(), pkgr.calls.Load())
		}
	})

	t.Run("enabled firewall blocks matching package and allows others", func(t *testing.T) {
		uidPackageCache.Clear()
		e := NewEngine()
		e.SetUIDResolver(&countingUIDResolver{uid: 10201})
		e.SetAppUidResolver(&countingPackageResolver{pkg: "org.telegram.messenger"})
		e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"org.telegram.messenger": true}})
		e.SetFirewallEnabled(true)

		if !e.isFlowFirewalled(flow, ProtocolTCP) {
			t.Fatal("expected blocked package to be firewalled on TCP")
		}
		if !e.isFlowFirewalled(flow, ProtocolUDP) {
			t.Fatal("expected blocked package to be firewalled on UDP")
		}

		uidPackageCache.Clear()
		flowFirewallCache.Clear()
		e.SetAppUidResolver(&countingPackageResolver{pkg: "com.brave.browser"})
		if e.isFlowFirewalled(flow, ProtocolTCP) {
			t.Fatal("expected allowed package not to be firewalled")
		}
	})
}
