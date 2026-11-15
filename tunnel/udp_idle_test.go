package tunnel

import (
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestBidiCopyUDP(t *testing.T) {
	t.Run("reaps idle flow after timeout and closes both conns", func(t *testing.T) {
		a1, a2 := net.Pipe()
		b1, b2 := net.Pipe()
		defer a1.Close()
		defer b2.Close()

		done := make(chan struct{})
		go func() {
			bidiCopyUDP(a2, b1, 80*time.Millisecond)
			close(done)
		}()

		select {
		case <-done:
		case <-time.After(1 * time.Second):
			t.Fatal("bidiCopyUDP did not exit after idle timeout")
		}

		// Both underlying conns passed to bidiCopyUDP must be closed.
		if _, err := a1.Write([]byte("ping")); err == nil {
			t.Fatal("expected local conn to be closed after idle reap")
		}
		if _, err := b2.Write([]byte("pong")); err == nil {
			t.Fatal("expected remote conn to be closed after idle reap")
		}
	})

	t.Run("refreshes deadline while datagrams flow", func(t *testing.T) {
		a1, a2 := net.Pipe()
		b1, b2 := net.Pipe()
		defer a1.Close()
		defer b2.Close()

		var exited atomic.Bool
		done := make(chan struct{})
		go func() {
			bidiCopyUDP(a2, b1, 100*time.Millisecond)
			exited.Store(true)
			close(done)
		}()

		buf := make([]byte, 16)
		for i := 0; i < 4; i++ {
			time.Sleep(50 * time.Millisecond)
			if exited.Load() {
				t.Fatalf("bidiCopyUDP exited prematurely during active traffic at step %d", i)
			}
			if _, err := a1.Write([]byte("hi")); err != nil {
				t.Fatalf("write step %d: %v", i, err)
			}
			n, err := b2.Read(buf)
			if err != nil || string(buf[:n]) != "hi" {
				t.Fatalf("read step %d: n=%d err=%v", i, n, err)
			}
		}

		select {
		case <-done:
		case <-time.After(1 * time.Second):
			t.Fatal("bidiCopyUDP did not exit after traffic stopped")
		}
	})
}
