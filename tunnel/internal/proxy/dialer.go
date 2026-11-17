package proxy

import (
	"fmt"
	"net"
	"syscall"
	"time"
)

// NewProtectedDialer returns a net.Dialer whose sockets bypass the Android VPN via protectFn.
func NewProtectedDialer(protectFn func(fd int) bool, timeout time.Duration) *net.Dialer {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return &net.Dialer{
		Timeout: timeout,
		Control: func(network, address string, cRaw syscall.RawConn) error {
			if protectFn == nil {
				return nil
			}
			var protectErr error
			if err := cRaw.Control(func(fd uintptr) {
				if !protectFn(int(fd)) {
					protectErr = fmt.Errorf("protect socket fd=%d failed", fd)
				}
			}); err != nil {
				return err
			}
			return protectErr
		},
	}
}
