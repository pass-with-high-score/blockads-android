package geoip

import (
	"encoding/binary"
	"net"
)

// RecordSize is the size in bytes of each GeoIP record:
// 4 bytes start_ip + 4 bytes end_ip + 2 bytes country_code.
const RecordSize = 10

// Database represents an in-memory or mmap'd binary IPv4 GeoIP database.
type Database struct {
	data        []byte
	recordCount int
}

// NewDatabase initializes a GeoIP database from raw binary data.
// It creates a Go-managed heap copy to ensure safety across JNI/C boundaries.
func NewDatabase(data []byte) *Database {
	if len(data) < RecordSize {
		return nil
	}
	buf := make([]byte, len(data))
	copy(buf, data)
	return &Database{
		data:        buf,
		recordCount: len(buf) / RecordSize,
	}
}

// RecordCount returns the number of records in the database.
func (d *Database) RecordCount() int {
	if d == nil {
		return 0
	}
	return d.recordCount
}

// Lookup finds the 2-letter ISO country code for an IPv4 address.
// Returns an uppercase 2-letter country code (e.g., "VN", "US"), or empty string if not found.
func (d *Database) Lookup(ip net.IP) string {
	if d == nil || d.recordCount == 0 {
		return ""
	}
	ip4 := ip.To4()
	if ip4 == nil {
		return ""
	}
	val := binary.BigEndian.Uint32(ip4)

	low := 0
	high := d.recordCount - 1

	for low <= high {
		mid := (low + high) / 2
		off := mid * RecordSize

		startIP := binary.BigEndian.Uint32(d.data[off : off+4])
		endIP := binary.BigEndian.Uint32(d.data[off+4 : off+8])

		if val < startIP {
			high = mid - 1
		} else if val > endIP {
			low = mid + 1
		} else {
			return string(d.data[off+8 : off+10])
		}
	}
	return ""
}
