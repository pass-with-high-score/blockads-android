package tunnel

import (
	"net"
	"sync/atomic"
	"testing"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
)

type fakeProxyClient struct {
	dialCalls atomic.Int64
}

func (f *fakeProxyClient) DialTCP(net.IP, int) (net.Conn, error) {
	f.dialCalls.Add(1)
	return nil, net.ErrClosed
}

func (f *fakeProxyClient) Protocol() string               { return "fake" }
func (f *fakeProxyClient) ServerAddress() string          { return "203.0.113.10:443" }
func (f *fakeProxyClient) Tag() string                    { return "fake" }
func (f *fakeProxyClient) SetProtectFn(func(int) bool) {}

func TestDialProxySkipsPrivateAndLoopback(t *testing.T) {
	e := NewEngine()
	fake := &fakeProxyClient{}
	var pc proxy.Client = fake
	e.proxyClient.Store(&pc)

	for _, ipStr := range []string{"127.0.0.1", "::1", "10.0.0.1", "172.16.0.1", "192.168.1.1"} {
		ip := net.ParseIP(ipStr)
		conn, used, err := e.DialProxy(ip, 53)
		if used || conn != nil || err != nil {
			t.Errorf("DialProxy(%s, 53) = (%v, %v, %v), want (nil, false, nil)", ipStr, conn, used, err)
		}
	}
	if fake.dialCalls.Load() != 0 {
		t.Errorf("proxy DialTCP called %d times for private/loopback IPs, want 0", fake.dialCalls.Load())
	}

	_, used, _ := e.DialProxy(net.ParseIP("8.8.8.8"), 53)
	if !used || fake.dialCalls.Load() != 1 {
		t.Errorf("DialProxy(8.8.8.8, 53): used=%v dialCalls=%d, want true / 1", used, fake.dialCalls.Load())
	}
}
