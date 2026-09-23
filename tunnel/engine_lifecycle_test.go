package tunnel

import (
	"os"
	"testing"
	"time"
)

// startInBackground runs Engine.Start on the read end of a pipe standing in
// for the TUN and reports when Start returns.
func startInBackground(t *testing.T, e *Engine, wgConfigJSON string) (*os.File, <-chan struct{}) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { r.Close(); w.Close() })

	done := make(chan struct{})
	go func() {
		e.Start(int(r.Fd()), nil, wgConfigJSON)
		close(done)
	}()
	return w, done
}

func waitReturned(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Start did not return")
	}
}

func TestStartClearsRunningWhenWireGuardInitFails(t *testing.T) {
	e := NewEngine()
	_, done := startInBackground(t, e, "not json")
	waitReturned(t, done)

	if e.IsRunning() {
		t.Fatal("engine reports running after WireGuard init failed")
	}
}

func TestStartClearsRunningOnTunReadError(t *testing.T) {
	e := NewEngine()
	w, done := startInBackground(t, e, "")

	deadline := time.Now().Add(5 * time.Second)
	for !e.IsRunning() {
		if time.Now().After(deadline) {
			t.Fatal("engine never started")
		}
		time.Sleep(10 * time.Millisecond)
	}

	w.Close() // EOF on the TUN read ends the interceptor loop
	waitReturned(t, done)

	if e.IsRunning() {
		t.Fatal("engine reports running after its TUN read loop exited")
	}
}
