package tunnel

import (
	"net"
	"reflect"
	"unsafe"

	"golang.zx2c4.com/wireguard/conn"
)

// ─────────────────────────────────────────────────────────────────────────────
// protectedBind wraps the default StdNetBind so the IPv4/IPv6 UDP sockets
// created by Open() are passed to VpnService.protect(). Without this the
// encrypted WireGuard packets (sent by wireguard-go to the peer endpoint)
// get routed through our own TUN — Android's addDisallowedApplication is
// not sufficient for VPN-in-VPN: the kernel's routing-by-fwmark for VPN
// traffic kicks in before the per-app rule, so packets from our own
// process can still loop back into the TUN.
//
// We reach into the unexported `ipv4`/`ipv6` *net.UDPConn fields of the
// StdNetBind via reflect because the conn package gives no public hook to
// run a Control() func on the sockets it creates.
// ─────────────────────────────────────────────────────────────────────────────

type protectedBind struct {
	conn.Bind // StdNetBind under the hood — embed for full method delegation
	protect   func(fd int) bool
}

func newProtectedBind(protect func(fd int) bool) conn.Bind {
	return &protectedBind{
		Bind:    conn.NewDefaultBind(),
		protect: protect,
	}
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	if b.protect != nil {
		b.protectInnerSockets()
	}
	return fns, actualPort, nil
}

// protectInnerSockets walks the embedded StdNetBind via reflect and calls
// protect() on each of its underlying *net.UDPConn file descriptors.
// Field names — `ipv4`, `ipv6` — match wireguard-go's bind_std.go.
func (b *protectedBind) protectInnerSockets() {
	v := reflect.ValueOf(b.Bind)
	if v.Kind() == reflect.Ptr {
		v = v.Elem()
	}
	if v.Kind() != reflect.Struct {
		return
	}
	for _, name := range []string{"ipv4", "ipv6"} {
		f := v.FieldByName(name)
		if !f.IsValid() || f.IsZero() {
			continue
		}
		fp := reflect.NewAt(f.Type(), unsafe.Pointer(f.UnsafeAddr())).Elem()
		udp, ok := fp.Interface().(*net.UDPConn)
		if !ok || udp == nil {
			continue
		}
		raw, err := udp.SyscallConn()
		if err != nil {
			logf("WG bind: SyscallConn() failed for %s: %v", name, err)
			continue
		}
		if err := raw.Control(func(fd uintptr) {
			if !b.protect(int(fd)) {
				logf("WG bind: protect() returned false for %s fd=%d", name, fd)
			}
		}); err != nil {
			logf("WG bind: Control() failed for %s: %v", name, err)
		}
	}
}
