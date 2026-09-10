package bloom

import (
	"encoding/binary"
	"fmt"
	"golang.org/x/sys/unix"
	"hash/fnv"
	"math"
	"os"
	"strings"
)

// Bloom Filter file format constants
const (
	bloomMagic   = 0x424C4F4D // "BLOM" in hex
	bloomVersion = 1
	// Header: magic(4) + version(4) + bitCount(8) + hashCount(4) + padding(4) = 24 bytes
	bloomHeaderSize = 24
)

// BloomFilter is a memory-mapped, read-only probabilistic data structure.
// It can tell you with certainty that a domain is NOT in the blocklist.
// If it says "maybe", we fall back to the MmapTrie for a definitive answer.
//
// This eliminates trie traversal for ~90%+ of clean DNS queries.
type BloomFilter struct {
	file      *os.File
	buffer    []byte
	bitCount  uint64
	hashCount uint32
}

// BloomBuilder is used to construct a Bloom Filter and serialize it to a file.
// This is used on the Kotlin side (via Go) or in tests.
type BloomBuilder struct {
	bits      []byte
	bitCount  uint64
	hashCount uint32
}

// OptimalBloomParams calculates the optimal bit count and hash count
// for a given number of expected items and desired false positive rate.
//
// m = -(n * ln(p)) / (ln(2))^2   (optimal bit count)
// k = (m / n) * ln(2)            (optimal hash count)
func OptimalBloomParams(expectedItems int, fpRate float64) (bitCount uint64, hashCount uint32) {
	if expectedItems <= 0 {
		expectedItems = 1
	}
	if fpRate <= 0 || fpRate >= 1 {
		fpRate = 0.001 // default 0.1%
	}
	n := float64(expectedItems)
	m := -n * math.Log(fpRate) / (math.Ln2 * math.Ln2)
	k := (m / n) * math.Ln2

	bitCount = uint64(math.Ceil(m))
	hashCount = uint32(math.Max(math.Ceil(k), 1))

	// Round bit count up to nearest byte boundary
	if bitCount%8 != 0 {
		bitCount = (bitCount/8 + 1) * 8
	}
	return
}

// NewBloomBuilder creates a new BloomBuilder with optimal parameters.
func NewBloomBuilder(expectedItems int, fpRate float64) *BloomBuilder {
	bitCount, hashCount := OptimalBloomParams(expectedItems, fpRate)
	byteCount := bitCount / 8
	return &BloomBuilder{
		bits:      make([]byte, byteCount),
		bitCount:  bitCount,
		hashCount: hashCount,
	}
}

// Add inserts a domain into the bloom filter.
func (b *BloomBuilder) Add(domain string) {
	for i := uint32(0); i < b.hashCount; i++ {
		idx := b.hash(domain, i) % b.bitCount
		b.bits[idx/8] |= 1 << (idx % 8)
	}
}

// MightContain checks if a domain might be in the set.
// Returns false = definitely NOT in set, true = maybe in set.
func (b *BloomBuilder) MightContain(domain string) bool {
	for i := uint32(0); i < b.hashCount; i++ {
		idx := b.hash(domain, i) % b.bitCount
		if b.bits[idx/8]&(1<<(idx%8)) == 0 {
			return false
		}
	}
	return true
}

// SaveToFile writes the bloom filter to a binary file.
func (b *BloomBuilder) SaveToFile(path string) error {
	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("failed to create bloom file: %w", err)
	}
	defer f.Close()

	header := make([]byte, bloomHeaderSize)
	binary.BigEndian.PutUint32(header[0:4], bloomMagic)
	binary.BigEndian.PutUint32(header[4:8], bloomVersion)
	binary.BigEndian.PutUint64(header[8:16], b.bitCount)
	binary.BigEndian.PutUint32(header[16:20], b.hashCount)
	// header[20:24] = padding (zeros)

	if _, err := f.Write(header); err != nil {
		return fmt.Errorf("failed to write bloom header: %w", err)
	}
	if _, err := f.Write(b.bits); err != nil {
		return fmt.Errorf("failed to write bloom bits: %w", err)
	}
	return nil
}

// hash computes the i-th hash of domain using double hashing.
// h(i) = h1 + i*h2, where h1 = FNV-1a and h2 = FNV-1.
func (b *BloomBuilder) hash(domain string, i uint32) uint64 {
	h1, h2 := bloomDoubleHash(domain)
	return h1 + uint64(i)*h2
}

// ─────────────────────────────────────────────────────────────
// Read-only mmap'd Bloom Filter (used at runtime in Engine)
// ─────────────────────────────────────────────────────────────

// LoadBloomFilter opens a file and memory-maps its contents as a read-only Bloom Filter.
func LoadBloomFilter(path string) (*BloomFilter, error) {
	if path == "" {
		return nil, fmt.Errorf("empty path")
	}

	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("failed to open bloom file: %w", err)
	}

	stat, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("failed to stat bloom file: %w", err)
	}

	size := stat.Size()
	if size < bloomHeaderSize {
		f.Close()
		return nil, fmt.Errorf("file too small to be a valid bloom filter")
	}

	data, err := unix.Mmap(int(f.Fd()), 0, int(size), unix.PROT_READ, unix.MAP_SHARED)
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("mmap failed: %w", err)
	}

	magic := binary.BigEndian.Uint32(data[0:4])
	if magic != bloomMagic {
		unix.Munmap(data)
		f.Close()
		return nil, fmt.Errorf("invalid bloom magic: expected %X, got %X", bloomMagic, magic)
	}

	version := binary.BigEndian.Uint32(data[4:8])
	if version != bloomVersion {
		unix.Munmap(data)
		f.Close()
		return nil, fmt.Errorf("invalid bloom version: expected %d, got %d", bloomVersion, version)
	}

	bitCount := binary.BigEndian.Uint64(data[8:16])
	hashCount := binary.BigEndian.Uint32(data[16:20])

	expectedSize := int64(bloomHeaderSize) + int64(bitCount/8)
	if size < expectedSize {
		unix.Munmap(data)
		f.Close()
		return nil, fmt.Errorf("bloom file truncated: expected %d bytes, got %d", expectedSize, size)
	}

	return &BloomFilter{
		file:      f,
		buffer:    data,
		bitCount:  bitCount,
		hashCount: hashCount,
	}, nil
}

// Close unmaps the memory and closes the file.
func (bf *BloomFilter) Close() {
	if bf.buffer != nil {
		unix.Munmap(bf.buffer)
		bf.buffer = nil
	}
	if bf.file != nil {
		bf.file.Close()
		bf.file = nil
	}
}

// MightContain checks if a single domain string might be in the set.
// Returns false = definitely NOT blocked, true = maybe blocked (need trie confirmation).
func (bf *BloomFilter) MightContain(domain string) bool {
	if bf.buffer == nil {
		return true // fail-open: if bloom is broken, fall through to trie
	}
	h1, h2 := bloomDoubleHash(domain)
	for i := uint32(0); i < bf.hashCount; i++ {
		idx := (h1 + uint64(i)*h2) % bf.bitCount
		byteIdx := bloomHeaderSize + int(idx/8)
		if byteIdx >= len(bf.buffer) {
			return true // out of bounds, fail-open
		}
		if bf.buffer[byteIdx]&(1<<(idx%8)) == 0 {
			return false // definitely not in set
		}
	}
	return true // maybe in set
}

// MightContainDomainOrParent checks if a domain OR any of its parent domains
// might be in the bloom filter. This mirrors the trie's parent-domain matching.
//
// For "sub.ads.google.com", checks: sub.ads.google.com → ads.google.com → google.com → com
//
// Returns false only if ALL levels are definitely NOT in the filter.
func (bf *BloomFilter) MightContainDomainOrParent(domain string) bool {
	if bf.buffer == nil {
		return true // fail-open
	}
	d := domain
	for {
		if bf.MightContain(d) {
			return true // maybe blocked at this level
		}
		idx := strings.IndexByte(d, '.')
		if idx < 0 {
			break
		}
		d = d[idx+1:]
	}
	return false // definitely clean at ALL levels
}

// ─────────────────────────────────────────────────────────────
// Shared hash function (must be identical in Go and Kotlin)
// ─────────────────────────────────────────────────────────────

// bloomDoubleHash computes two independent hash values for double hashing.
// h1 = FNV-1a 64-bit, h2 = FNV-1 64-bit (different mixing strategy).
func bloomDoubleHash(s string) (uint64, uint64) {
	// h1: FNV-1a
	h1 := fnv.New64a()
	h1.Write([]byte(s))
	v1 := h1.Sum64()

	// h2: FNV-1 (standard, non-'a' variant)
	h2 := fnv.New64()
	h2.Write([]byte(s))
	v2 := h2.Sum64()

	// Ensure h2 is odd (avoids cycles in modular arithmetic)
	if v2%2 == 0 {
		v2++
	}

	return v1, v2
}
