package geoip

import (
	"encoding/binary"
	"net"
	"os"
	"testing"
)

func buildSampleDatabase() []byte {
	// Sample records:
	// 1. 1.0.0.0 - 1.0.0.255: US (16777216 - 16777471)
	// 2. 14.160.0.0 - 14.191.255.255: VN (245366784 - 247463935)
	// 3. 103.0.0.0 - 103.255.255.255: SG (1728053248 - 1744830463)
	records := []struct {
		startIP uint32
		endIP   uint32
		cc      string
	}{
		{16777216, 16777471, "US"},
		{245366784, 247463935, "VN"},
		{1728053248, 1744830463, "SG"},
	}

	buf := make([]byte, len(records)*RecordSize)
	for i, r := range records {
		off := i * RecordSize
		binary.BigEndian.PutUint32(buf[off:off+4], r.startIP)
		binary.BigEndian.PutUint32(buf[off+4:off+8], r.endIP)
		copy(buf[off+8:off+10], r.cc)
	}
	return buf
}

func TestGeoIPLookup(t *testing.T) {
	data := buildSampleDatabase()
	db := NewDatabase(data)
	if db == nil {
		t.Fatal("Expected non-nil database")
	}
	if db.RecordCount() != 3 {
		t.Fatalf("Expected 3 records, got %d", db.RecordCount())
	}

	tests := []struct {
		ip       string
		expected string
	}{
		{"1.0.0.1", "US"},
		{"1.0.0.255", "US"},
		{"14.170.1.1", "VN"},
		{"103.10.1.1", "SG"},
		{"8.8.8.8", ""},     // Not in sample
		{"127.0.0.1", ""},   // Not in sample
		{"2001:db8::1", ""}, // IPv6 not in IPv4 db
	}

	for _, tc := range tests {
		ip := net.ParseIP(tc.ip)
		got := db.Lookup(ip)
		if got != tc.expected {
			t.Errorf("Lookup(%q) = %q; expected %q", tc.ip, got, tc.expected)
		}
	}
}

func TestActualGeoIPFile(t *testing.T) {
	data, err := os.ReadFile("../../../app/src/main/assets/preset/geoip_ipv4.bin")
	if err != nil {
		t.Skip("geoip_ipv4.bin not found:", err)
	}
	db := NewDatabase(data)
	if db == nil {
		t.Fatal("nil database")
	}
	t.Logf("Loaded %d records", db.RecordCount())

	for _, ipStr := range []string{"111.65.250.2", "111.65.242.20", "14.225.254.1", "125.212.194.143"} {
		ip := net.ParseIP(ipStr)
		cc := db.Lookup(ip)
		t.Logf("IP %s -> %s", ipStr, cc)
		if cc != "VN" {
			t.Fatalf("Expected VN for %s, got %s", ipStr, cc)
		}
	}
}

func BenchmarkGeoIPLookup(b *testing.B) {
	data := buildSampleDatabase()
	db := NewDatabase(data)
	ip := net.ParseIP("14.170.1.1")

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = db.Lookup(ip)
	}
}

func BenchmarkActualGeoIPLookup(b *testing.B) {
	data, err := os.ReadFile("../../../app/src/main/assets/preset/geoip_ipv4.bin")
	if err != nil {
		b.Skip("geoip_ipv4.bin not found:", err)
	}
	db := NewDatabase(data)
	ip := net.ParseIP("111.65.250.2")

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = db.Lookup(ip)
	}
}
