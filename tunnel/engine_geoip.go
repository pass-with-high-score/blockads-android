package tunnel

import (
	"fmt"
	"net"
	"strings"

	"github.com/nqmgaming/blockads-tunnel/internal/geoip"
)

// SetGeoIPDatabaseBytes initializes the GeoIP database from binary byte data.
func (e *Engine) SetGeoIPDatabaseBytes(data []byte) error {
	buf := make([]byte, len(data))
	copy(buf, data)
	db := geoip.NewDatabase(buf)
	if db == nil {
		return fmt.Errorf("invalid geoip database data")
	}
	e.geoIPDB.Store(db)
	if matcher := e.rulesetMatcher.Load(); matcher != nil {
		matcher.SetGeoIPLookup(db.Lookup)
	}
	logf("Loaded GeoIP database: %d records (%d bytes)", db.RecordCount(), len(buf))
	return nil
}

// SetGeoIPDatabaseFromFd loads the GeoIP database directly from a file descriptor
// (e.g. AssetFileDescriptor from Android assets) via zero-copy mmap and copies it to Go memory.
func (e *Engine) SetGeoIPDatabaseFromFd(fd int, offset int64, length int64) error {
	data, cleanup, err := mmapFd(fd, offset, length)
	if err != nil {
		logf("SetGeoIPDatabaseFromFd failed to mmap: %v", err)
		return err
	}
	defer cleanup()

	buf := make([]byte, length)
	copy(buf, data)
	return e.SetGeoIPDatabaseBytes(buf)
}

// GetGeoIPCountry returns the 2-letter ISO country code for an IP string (e.g., "VN", "US").
// Returns empty string if not found or if the IP is invalid.
func (e *Engine) GetGeoIPCountry(ipStr string) string {
	db := e.geoIPDB.Load()
	if db == nil {
		return ""
	}
	ip := net.ParseIP(strings.TrimSpace(ipStr))
	if ip == nil {
		return ""
	}
	return db.Lookup(ip)
}
