package tunnel

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"strings"

	"golang.org/x/sys/unix"
)

// mmapFd maps a region of a file descriptor into memory, properly aligning
// the offset to the operating system's page size (supporting 4KB and 16KB on Android 15).
func mmapFd(fd int, offset int64, length int64) ([]byte, func(), error) {
	if length <= 0 {
		return nil, func() {}, nil
	}
	pageSize := int64(os.Getpagesize())
	alignedOffset := (offset / pageSize) * pageSize
	diff := offset - alignedOffset
	alignedLength := length + diff

	data, err := unix.Mmap(fd, alignedOffset, int(alignedLength), unix.PROT_READ, unix.MAP_SHARED)
	if err != nil {
		return nil, nil, fmt.Errorf("mmap fd %d offset %d len %d failed: %w", fd, offset, length, err)
	}

	cleanSlice := data[diff : diff+length]
	cleanup := func() {
		_ = unix.Munmap(data)
	}
	return cleanSlice, cleanup, nil
}

// SetDoHBlocklistFromFd loads the DoH blocklist directly from a file descriptor
// (e.g. AssetFileDescriptor from Android assets) via zero-copy mmap.
func (e *Engine) SetDoHBlocklistFromFd(fd int, offset int64, length int64) error {
	data, cleanup, err := mmapFd(fd, offset, length)
	if err != nil {
		logf("SetDoHBlocklistFromFd failed to mmap: %v", err)
		return err
	}
	defer cleanup()

	domains := make(map[string]struct{})
	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		domain := strings.ToLower(line)
		domains[domain] = struct{}{}
	}

	e.mu.Lock()
	e.dohDomains = domains
	count := len(domains)
	e.mu.Unlock()

	logf("Loaded %d DoH domains via zero-copy mmap (fd=%d, offset=%d, len=%d)", count, fd, offset, length)
	return nil
}

// SetExtraPassthroughSuffixesFromFd loads the HTTPS passthrough suffixes directly
// from an asset file descriptor via zero-copy mmap.
func (e *Engine) SetExtraPassthroughSuffixesFromFd(fd int, offset int64, length int64) error {
	data, cleanup, err := mmapFd(fd, offset, length)
	if err != nil {
		logf("SetExtraPassthroughSuffixesFromFd failed to mmap: %v", err)
		return err
	}
	defer cleanup()

	e.mu.Lock()
	filter := e.stackMitmFilter
	e.mu.Unlock()
	if filter == nil {
		logf("SetExtraPassthroughSuffixesFromFd: stack MITM not active")
		return fmt.Errorf("stack MITM not active")
	}

	var suffixes []string
	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		suffixes = append(suffixes, line)
	}

	filter.SetExtraPassthroughSuffixes(suffixes)
	logf("Loaded %d passthrough suffixes via zero-copy mmap (fd=%d)", len(suffixes), fd)
	return nil
}

// SetCosmeticCSSFromFile loads cosmetic CSS directly from a file on disk
// without routing huge strings through the Java heap.
func (e *Engine) SetCosmeticCSSFromFile(filePath string) error {
	if filePath == "" {
		SetCosmeticCSS("")
		return nil
	}
	content, err := os.ReadFile(filePath)
	if err != nil {
		logf("SetCosmeticCSSFromFile failed to read %s: %v", filePath, err)
		SetCosmeticCSS("")
		return err
	}
	SetCosmeticCSS(string(content))
	logf("Loaded cosmetic CSS from disk: %s (%d bytes)", filePath, len(content))
	return nil
}

// SetScriptletRulesFromFile loads scriptlet rules directly from a file on disk.
func (e *Engine) SetScriptletRulesFromFile(filePath string) error {
	if filePath == "" {
		SetScriptletStore(nil)
		return nil
	}
	content, err := os.ReadFile(filePath)
	if err != nil {
		logf("SetScriptletRulesFromFile failed to read %s: %v", filePath, err)
		SetScriptletStore(nil)
		return err
	}
	rules := parseScriptletRules(string(content))
	if len(rules) == 0 {
		SetScriptletStore(nil)
		logf("Scriptlet rules from %s: parsed 0 rules (%d bytes)", filePath, len(content))
		return nil
	}
	store := buildScriptletStore(rules)
	SetScriptletStore(store)
	logf("Loaded %d scriptlet rules from disk: %s", len(rules), filePath)
	return nil
}
