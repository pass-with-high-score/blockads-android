package wireguard

import (
	"fmt"
	"net"
	"os"
	"reflect"
	"unsafe"

	"golang.zx2c4.com/wireguard/conn"
)

type protectedBind struct {
	conn.Bind
	protect func(fd int) bool
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
			fmt.Fprintf(os.Stderr, "[BlockAds/Go] WG bind: SyscallConn() failed for %s: %v\n", name, err)
			continue
		}
		if err := raw.Control(func(fd uintptr) {
			if !b.protect(int(fd)) {
				fmt.Fprintf(os.Stderr, "[BlockAds/Go] WG bind: protect() returned false for %s fd=%d\n", name, fd)
			}
		}); err != nil {
			fmt.Fprintf(os.Stderr, "[BlockAds/Go] WG bind: Control() failed for %s: %v\n", name, err)
		}
	}
}
