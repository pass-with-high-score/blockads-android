package packet

import (
	"bytes"
	"io"
	"testing"
)

func TestPacketPipeRoundTrip(t *testing.T) {
	p := NewPacketPipe()
	src := []byte{1, 2, 3}
	p.Push(src)
	src[0] = 9 // Push must copy
	buf := make([]byte, 16)
	n, err := p.Read(buf)
	if err != nil || !bytes.Equal(buf[:n], []byte{1, 2, 3}) {
		t.Fatalf("Read = %v, %v", buf[:n], err)
	}

	out := []byte{4, 5}
	if n, _ := p.Write(out); n != 2 {
		t.Fatalf("Write n = %d", n)
	}
	out[0] = 9 // Write must copy
	if got := p.Pop(); !bytes.Equal(got, []byte{4, 5}) {
		t.Fatalf("Pop = %v", got)
	}
}

func TestPacketPipeDropsWhenFull(t *testing.T) {
	p := NewPacketPipe()
	for i := 0; i < packetQueueDepth+10; i++ {
		p.Push([]byte{byte(i)})
		p.Write([]byte{byte(i)})
	}
	if got := p.inboundDropped.Load(); got != 10 {
		t.Errorf("inbound dropped = %d, want 10", got)
	}
	if got := p.outboundDropped.Load(); got != 10 {
		t.Errorf("outbound dropped = %d, want 10", got)
	}
}

func TestPacketPipeClose(t *testing.T) {
	p := NewPacketPipe()
	p.Close()
	p.Close() // idempotent
	if _, err := p.Read(make([]byte, 4)); err != io.EOF {
		t.Errorf("Read after Close err = %v, want EOF", err)
	}
	if got := p.Pop(); got != nil {
		t.Errorf("Pop after Close = %v, want nil", got)
	}
	p.Push([]byte{1})
	if n, err := p.Write([]byte{1, 2}); n != 2 || err != nil {
		t.Errorf("Write after Close = %d, %v", n, err)
	}
}

func BenchmarkPacketPipe(b *testing.B) {
	p := NewPacketPipe()
	pkt := make([]byte, 1400)
	buf := make([]byte, 1500)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		p.Push(pkt)
		p.Read(buf)
		p.Write(pkt)
		p.Pop()
	}
}
