package trie

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/nqmgaming/blockads-tunnel/internal/bloom"
)

// CompileFilterList parses a local raw filter list at inputPath and writes
// .trie, .bloom, and optional sidecar .css, .scriptlets, and .modules.json files.
// Returns the number of compiled rules, or an error.
func CompileFilterList(inputPath, triePath, bloomPath string) (int, error) {
	f, err := os.Open(inputPath)
	if err != nil {
		return 0, fmt.Errorf("open input: %w", err)
	}
	defer f.Close()

	domains := make(map[string]struct{})
	ips := make(map[string]struct{})
	seenCSS := make(map[string]struct{})
	seenScriptlets := make(map[string]struct{})
	var cssRules []string
	var scriptlets []string

	domainModules := make(map[string]*domainModuleRules)
	seenGlobalCSS := make(map[string]struct{})
	var globalCSS []string
	seenGlobalPaths := make(map[string]struct{})
	var globalBlockPaths []string

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 256*1024), 1024*1024)
	for scanner.Scan() {
		rawLine := strings.TrimSpace(scanner.Text())
		if rawLine == "" || strings.HasPrefix(rawLine, "!") || (strings.HasPrefix(rawLine, "#") && !strings.HasPrefix(rawLine, "##")) {
			continue
		}

		// 1. Extract Scriptlet Rules
		if strings.Contains(rawLine, "##+js(") || strings.Contains(rawLine, "#%#//scriptlet(") {
			if _, exists := seenScriptlets[rawLine]; !exists {
				seenScriptlets[rawLine] = struct{}{}
				scriptlets = append(scriptlets, rawLine)
			}
			continue
		}

		// 2. Extract Cosmetic CSS Rules (generic ## and domain-specific domain##)
		if doms, selector, ok := parseCosmeticRule(rawLine); ok {
			if len(doms) == 0 {
				rule := "##" + selector
				if _, exists := seenCSS[rule]; !exists {
					seenCSS[rule] = struct{}{}
					cssRules = append(cssRules, rule)
				}
				if _, exists := seenGlobalCSS[selector]; !exists {
					seenGlobalCSS[selector] = struct{}{}
					globalCSS = append(globalCSS, selector)
				}
			} else {
				fullRule := strings.Join(doms, ",") + "##" + selector
				if _, exists := seenCSS[fullRule]; !exists {
					seenCSS[fullRule] = struct{}{}
					cssRules = append(cssRules, fullRule)
				}
				for _, d := range doms {
					if strings.HasPrefix(d, "~") {
						continue
					}
					mod, exists := domainModules[d]
					if !exists {
						mod = &domainModuleRules{}
						domainModules[d] = mod
					}
					mod.CSS = append(mod.CSS, selector)
				}
			}
			continue
		}

		// 3. Extract URL Network Path Rules
		if targetDomain, pathPattern, ok := parseNetworkPathRule(rawLine); ok {
			if targetDomain == "*" {
				if _, exists := seenGlobalPaths[pathPattern]; !exists {
					seenGlobalPaths[pathPattern] = struct{}{}
					globalBlockPaths = append(globalBlockPaths, pathPattern)
				}
			} else {
				mod, exists := domainModules[targetDomain]
				if !exists {
					mod = &domainModuleRules{}
					domainModules[targetDomain] = mod
				}
				mod.BlockPaths = append(mod.BlockPaths, pathPattern)
			}
		}

		// 4. Extract Domains (for DNS Trie & Bloom) or Public IP/CIDR (for .ips)
		if strings.HasPrefix(rawLine, "@@") {
			continue
		}
		if ipRule, ok := parseIPOrCIDR(rawLine); ok {
			ips[ipRule] = struct{}{}
			continue
		}
		d := parseDomainLine(rawLine)
		if d != "" {
			domains[d] = struct{}{}
		}
	}
	if err := scanner.Err(); err != nil {
		return 0, fmt.Errorf("scan input: %w", err)
	}

	hasModules := len(domainModules) > 0 || len(globalCSS) > 0 || len(globalBlockPaths) > 0
	if len(domains) == 0 && len(ips) == 0 && len(cssRules) == 0 && len(scriptlets) == 0 && !hasModules {
		return 0, fmt.Errorf("no valid rules found in filter list")
	}

	// Build trie (even if 0 domains, write valid empty trie so loader succeeds)
	root := newTrieBuilderNode()
	for d := range domains {
		root.insert(d)
	}
	if err := root.saveToFile(triePath); err != nil {
		return 0, fmt.Errorf("write trie: %w", err)
	}

	// Build bloom filter
	bloomFilter := bloom.NewBloomBuilder(len(domains), 0.001)
	for d := range domains {
		bloomFilter.Add(d)
	}
	if err := bloomFilter.SaveToFile(bloomPath); err != nil {
		return 0, fmt.Errorf("write bloom: %w", err)
	}

	// Write optional sidecar files (.css, .scriptlets, .ips, .modules.json) next to triePath
	basePath := strings.TrimSuffix(triePath, ".trie")
	if len(cssRules) > 0 {
		_ = os.WriteFile(basePath+".css", []byte(strings.Join(cssRules, "\n")+"\n"), 0644)
	}
	if len(scriptlets) > 0 {
		sort.Strings(scriptlets)
		_ = os.WriteFile(basePath+".scriptlets", []byte(strings.Join(scriptlets, "\n")+"\n"), 0644)
	}
	if len(ips) > 0 {
		ipList := make([]string, 0, len(ips))
		for ip := range ips {
			ipList = append(ipList, ip)
		}
		sort.Strings(ipList)
		_ = os.WriteFile(basePath+".ips", []byte(strings.Join(ipList, "\n")+"\n"), 0644)
	}
	if hasModules {
		pkg := &moduleRulesPackage{
			Name:             filepath.Base(basePath),
			RuleCount:        len(domains) + len(ips) + len(cssRules) + len(scriptlets),
			Domains:          domainModules,
			GlobalCSS:        globalCSS,
			GlobalBlockPaths: globalBlockPaths,
		}
		if modBytes, err := json.Marshal(pkg); err == nil {
			_ = os.WriteFile(basePath+".modules.json", modBytes, 0644)
		}
	}

	effectiveCount := len(domains) + len(ips)
	if effectiveCount == 0 {
		effectiveCount = len(cssRules) + len(scriptlets)
		if effectiveCount == 0 {
			effectiveCount = len(domainModules) + len(globalBlockPaths)
		}
	}

	fmt.Fprintf(os.Stderr, "[BlockAds/Go] Compiled %d domains, %d IPs, %d CSS, %d scriptlets → %s\n",
		len(domains), len(ips), len(cssRules), len(scriptlets), triePath)
	return effectiveCount, nil
}

type trieBuilderNode struct {
	children   map[string]*trieBuilderNode
	isTerminal bool
}

func newTrieBuilderNode() *trieBuilderNode {
	return &trieBuilderNode{children: make(map[string]*trieBuilderNode)}
}

func (n *trieBuilderNode) insert(domain string) {
	labels := strings.Split(domain, ".")
	node := n
	for i := len(labels) - 1; i >= 0; i-- {
		label := labels[i]
		child, ok := node.children[label]
		if !ok {
			child = newTrieBuilderNode()
			node.children[label] = child
		}
		node = child
	}
	node.isTerminal = true
}

func (n *trieBuilderNode) saveToFile(path string) error {
	type bfsEntry struct {
		node   *trieBuilderNode
		offset int
	}

	nodeCount := 0
	domainCount := 0
	var countNodes func(node *trieBuilderNode)
	countNodes = func(node *trieBuilderNode) {
		nodeCount++
		if node.isTerminal {
			domainCount++
		}
		for _, child := range node.children {
			countNodes(child)
		}
	}
	countNodes(n)

	queue := []bfsEntry{{node: n, offset: headerSize}}
	currentOffset := headerSize
	offsets := make(map[*trieBuilderNode]int)

	for len(queue) > 0 {
		entry := queue[0]
		queue = queue[1:]

		offsets[entry.node] = currentOffset

		nodeSize := 1 + 4
		sortedLabels := sortedKeys(entry.node.children)
		for _, label := range sortedLabels {
			nodeSize += 2 + len(label) + 4
		}
		currentOffset += nodeSize

		for _, label := range sortedLabels {
			child := entry.node.children[label]
			queue = append(queue, bfsEntry{node: child})
		}
	}

	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create trie file: %w", err)
	}
	defer f.Close()

	header := make([]byte, headerSize)
	binary.BigEndian.PutUint32(header[0:4], trieMagic)
	binary.BigEndian.PutUint32(header[4:8], trieVersion)
	binary.BigEndian.PutUint32(header[8:12], uint32(nodeCount))
	binary.BigEndian.PutUint32(header[12:16], uint32(domainCount))
	if _, err := f.Write(header); err != nil {
		return err
	}

	queue2 := []*trieBuilderNode{n}
	for len(queue2) > 0 {
		node := queue2[0]
		queue2 = queue2[1:]

		if node.isTerminal {
			f.Write([]byte{1})
		} else {
			f.Write([]byte{0})
		}

		sortedLabels := sortedKeys(node.children)
		countBuf := make([]byte, 4)
		binary.BigEndian.PutUint32(countBuf, uint32(len(sortedLabels)))
		f.Write(countBuf)

		for _, label := range sortedLabels {
			child := node.children[label]

			lenBuf := make([]byte, 2)
			binary.BigEndian.PutUint16(lenBuf, uint16(len(label)))
			f.Write(lenBuf)
			f.Write([]byte(label))

			offBuf := make([]byte, 4)
			binary.BigEndian.PutUint32(offBuf, uint32(offsets[child]))
			f.Write(offBuf)
		}

		for _, label := range sortedLabels {
			queue2 = append(queue2, node.children[label])
		}
	}

	return nil
}

func sortedKeys(m map[string]*trieBuilderNode) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
