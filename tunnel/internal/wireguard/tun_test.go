package wireguard

import (
	"errors"
	"io"
	"os"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun"
)

func TestChannelTUNBasics(t *testing.T) {
	ct := NewChannelTUN(nil)
	var _ tun.Device = ct
	if ev := <-ct.Events(); ev != tun.EventUp {
		t.Fatalf("first event = %v, want EventUp", ev)
	}
	if mtu, err := ct.MTU(); mtu != defaultMTU || err != nil {
		t.Fatalf("MTU = %d, %v", mtu, err)
	}
	if name, err := ct.Name(); name != "channel-tun" || err != nil {
		t.Fatalf("Name = %q, %v", name, err)
	}
	if ct.File() != nil || ct.BatchSize() != 1 {
		t.Fatal("File/BatchSize mismatch")
	}
}

func TestChannelTUNInjectRead(t *testing.T) {
	ct := NewChannelTUN(nil)
	ct.Inject([]byte{1, 2, 3})
	bufs := [][]byte{make([]byte, 64)}
	sizes := make([]int, 1)
	n, err := ct.Read(bufs, sizes, 16)
	if n != 1 || err != nil || sizes[0] != 3 || string(bufs[0][16:19]) != "\x01\x02\x03" {
		t.Fatalf("Read = %d, %v, size %d, buf %v", n, err, sizes[0], bufs[0][16:19])
	}
}

// The inbound queue holds 256 packets; further injects are dropped rather
// than blocking the caller (the engine's TUN read loop).
func TestChannelTUNQueueFullDrops(t *testing.T) {
	ct := NewChannelTUN(nil)
	done := make(chan struct{})
	go func() {
		for i := 0; i < 300; i++ {
			ct.Inject([]byte{byte(i)})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Inject blocked on a full queue")
	}
	if got := len(ct.inbound); got != cap(ct.inbound) || got != 256 {
		t.Fatalf("queue holds %d packets (cap %d), want 256", got, cap(ct.inbound))
	}
	bufs, sizes := [][]byte{make([]byte, 8)}, make([]int, 1)
	for i := 0; i < 256; i++ {
		ct.Read(bufs, sizes, 0)
		if bufs[0][0] != byte(i) {
			t.Fatalf("packet %d = %d; the oldest packets should be kept", i, bufs[0][0])
		}
	}
}

func TestChannelTUNClose(t *testing.T) {
	ct := NewChannelTUN(nil)
	<-ct.Events()
	readErr := make(chan error, 1)
	go func() {
		_, err := ct.Read([][]byte{make([]byte, 8)}, make([]int, 1), 0)
		readErr <- err
	}()
	time.Sleep(10 * time.Millisecond)
	if err := ct.Close(); err != nil {
		t.Fatal(err)
	}
	if err := <-readErr; !errors.Is(err, os.ErrClosed) {
		t.Fatalf("blocked Read returned %v, want os.ErrClosed", err)
	}
	if err := ct.Close(); err != nil {
		t.Fatal("second Close failed")
	}
	if _, ok := <-ct.Events(); ok {
		t.Fatal("events channel still open after Close")
	}
	for i := 0; i < 300; i++ {
		ct.Inject([]byte{1}) // must never block after Close
	}
}

func TestChannelTUNWrite(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	ct := NewChannelTUN(w)

	n, err := ct.Write([][]byte{[]byte("xxabc"), []byte("xx"), []byte("xxdef")}, 2)
	if n != 3 || err != nil {
		t.Fatalf("Write = %d, %v", n, err)
	}
	w.Close()
	got, _ := io.ReadAll(r)
	if string(got) != "abcdef" {
		t.Fatalf("real TUN got %q", got)
	}

	if n, err := ct.Write([][]byte{[]byte("xxghi")}, 2); err == nil || n != 0 {
		t.Fatalf("Write to a closed TUN = %d, %v", n, err)
	}
}
