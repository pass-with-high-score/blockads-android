package mitm

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
)

// InterceptAction defines the response action when a path matches.
type InterceptAction string

const (
	ActionMock204  InterceptAction = "mock_204"
	ActionMockJSON InterceptAction = "mock_json"
	ActionReject   InterceptAction = "reject"
	ActionRedirect InterceptAction = "redirect"
)

// PathIntercept defines a URL path pattern match and mock action.
type PathIntercept struct {
	PathPattern string          `json:"path_pattern"`
	Method      string          `json:"method,omitempty"`
	Action      InterceptAction `json:"action"`
	MockStatus  int             `json:"mock_status,omitempty"`
	MockBody    string          `json:"mock_body,omitempty"`
	ContentType string          `json:"content_type,omitempty"`
	RedirectURL string          `json:"redirect_url,omitempty"`

	compiledRegex *regexp.Regexp
}

// HeaderRewrite defines header mutations when a URL path matches.
type HeaderRewrite struct {
	PathPattern string            `json:"path_pattern"`
	SetHeaders  map[string]string `json:"set_headers,omitempty"`
	DelHeaders  []string          `json:"del_headers,omitempty"`

	compiledRegex *regexp.Regexp
}

// RuleModule defines domain-specific interception rules, header rewrites, custom CSS, and scripts.
type RuleModule struct {
	ID             string          `json:"id"`
	Domains        []string        `json:"domains"`
	Intercepts     []PathIntercept `json:"intercepts,omitempty"`
	HeaderRewrites []HeaderRewrite `json:"header_rewrites,omitempty"`
	CSSRules       []string        `json:"css_rules,omitempty"`
	Scripts        []string        `json:"scripts,omitempty"`
}

// RuleManifest is the root configuration containing all dynamic modules.
type RuleManifest struct {
	Version int          `json:"version"`
	Modules []RuleModule `json:"modules"`
}

// ManifestRegistry stores compiled modules and provides fast domain/path lookups.
type ManifestRegistry struct {
	mu           sync.RWMutex
	modules      []RuleModule
	byDomain     map[string][]*RuleModule
	domainScripts map[string][]string
}

// Global manifest registry singleton
var defaultManifest = NewManifestRegistry()

// NewManifestRegistry creates an empty registry.
func NewManifestRegistry() *ManifestRegistry {
	return &ManifestRegistry{
		byDomain:      make(map[string][]*RuleModule),
		domainScripts: make(map[string][]string),
	}
}

// LoadJSON parses and compiles a RuleManifest from JSON string.
func (r *ManifestRegistry) LoadJSON(jsonStr string) error {
	if strings.TrimSpace(jsonStr) == "" {
		r.Clear()
		return nil
	}

	var manifest RuleManifest
	if err := json.Unmarshal([]byte(jsonStr), &manifest); err != nil {
		return fmt.Errorf("parse manifest json failed: %w", err)
	}

	byDomain := make(map[string][]*RuleModule)
	domainScripts := make(map[string][]string)

	for i := range manifest.Modules {
		mod := &manifest.Modules[i]
		for j := range mod.Intercepts {
			ic := &mod.Intercepts[j]
			if ic.PathPattern != "" {
				rx, err := regexp.Compile(ic.PathPattern)
				if err == nil {
					ic.compiledRegex = rx
				}
			}
		}
		for j := range mod.HeaderRewrites {
			hr := &mod.HeaderRewrites[j]
			if hr.PathPattern != "" {
				rx, err := regexp.Compile(hr.PathPattern)
				if err == nil {
					hr.compiledRegex = rx
				}
			}
		}

		for _, d := range mod.Domains {
			d = strings.ToLower(strings.TrimSpace(d))
			if d != "" {
				byDomain[d] = append(byDomain[d], mod)
				if len(mod.Scripts) > 0 {
					domainScripts[d] = append(domainScripts[d], mod.Scripts...)
				}
			}
		}
	}

	r.mu.Lock()
	r.modules = manifest.Modules
	r.byDomain = byDomain
	r.domainScripts = domainScripts
	r.mu.Unlock()

	logf("RuleManifest updated: %d modules, %d domain mappings", len(manifest.Modules), len(byDomain))
	return nil
}

// Clear removes all loaded manifest rules.
func (r *ManifestRegistry) Clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.modules = nil
	r.byDomain = make(map[string][]*RuleModule)
	r.domainScripts = make(map[string][]string)
}

// MatchesDomain returns true if the host is handled by any manifest module.
func (r *ManifestRegistry) MatchesDomain(host string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()

	host = normalizeHost(host)
	if _, ok := r.byDomain[host]; ok {
		return true
	}
	for d := range r.byDomain {
		if strings.HasSuffix(host, "."+d) {
			return true
		}
	}
	return false
}

// findModules returns all modules matching the given host.
func (r *ManifestRegistry) findModules(host string) []*RuleModule {
	r.mu.RLock()
	defer r.mu.RUnlock()

	var matched []*RuleModule
	for d, mods := range r.byDomain {
		if host == d || strings.HasSuffix(host, "."+d) {
			matched = append(matched, mods...)
		}
	}
	return matched
}

// GetDomainScripts returns any custom JavaScript injected specifically for this host.
func (r *ManifestRegistry) GetDomainScripts(host string) []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	host = normalizeHost(host)
	var scripts []string
	for d, sList := range r.domainScripts {
		if host == d || strings.HasSuffix(host, "."+d) {
			scripts = append(scripts, sList...)
		}
	}
	return scripts
}

// ManifestInterceptor implements TrafficInterceptor using ManifestRegistry rules.
type ManifestInterceptor struct {
	registry *ManifestRegistry
	blocker  adBlockChecker
	flow     FlowID
}

// NewManifestInterceptor creates an interceptor bound to a registry.
func NewManifestInterceptor(registry *ManifestRegistry) *ManifestInterceptor {
	return NewManifestInterceptorWithFlow(registry, nil, FlowID{})
}

// NewManifestInterceptorWithFlow creates a ManifestInterceptor bound to a blocker and flow for whitelist/logging.
func NewManifestInterceptorWithFlow(registry *ManifestRegistry, blocker adBlockChecker, flow FlowID) *ManifestInterceptor {
	if registry == nil {
		registry = defaultManifest
	}
	return &ManifestInterceptor{
		registry: registry,
		blocker:  blocker,
		flow:     flow,
	}
}

func (m *ManifestInterceptor) Name() string {
	return "ManifestInterceptor"
}

func (m *ManifestInterceptor) Matches(host string, req *http.Request) bool {
	return m.registry.MatchesDomain(host)
}

func (m *ManifestInterceptor) OnRequest(req *http.Request) (bool, *http.Response) {
	host := normalizeHost(req.Host)
	if host != "" && isDomainWhitelisted(m.blocker, host) {
		return false, nil
	}
	modules := m.registry.findModules(host)
	if len(modules) == 0 {
		return false, nil
	}

	path := req.URL.Path

	// 1. Check path intercepts
	for _, mod := range modules {
		for _, ic := range mod.Intercepts {
			if ic.Method != "" && !strings.EqualFold(ic.Method, req.Method) {
				continue
			}
			matched := false
			if ic.compiledRegex != nil {
				matched = ic.compiledRegex.MatchString(path)
			} else if ic.PathPattern != "" {
				matched = strings.Contains(path, ic.PathPattern)
			}

			if matched {
				if resp := executeInterceptAction(ic, req); resp != nil {
					blocked := resp.StatusCode != http.StatusFound
					reason := ""
					if blocked {
						reason = "manifest:" + mod.ID
					}
					logMitmQuery(m.blocker, m.flow, host, blocked, reason, 65)
					return true, resp
				}
			}
		}
	}

	// 2. Check header rewrites
	for _, mod := range modules {
		for _, hr := range mod.HeaderRewrites {
			matched := false
			if hr.compiledRegex != nil {
				matched = hr.compiledRegex.MatchString(path)
			} else if hr.PathPattern != "" {
				matched = strings.Contains(path, hr.PathPattern)
			}

			if matched {
				for _, del := range hr.DelHeaders {
					req.Header.Del(del)
				}
				for k, v := range hr.SetHeaders {
					req.Header.Set(k, v)
				}
			}
		}
	}

	return false, nil
}

func (m *ManifestInterceptor) OnResponse(req *http.Request, resp *http.Response) error {
	return nil
}

func executeInterceptAction(ic PathIntercept, req *http.Request) *http.Response {
	switch ic.Action {
	case ActionMock204:
		status := ic.MockStatus
		if status == 0 {
			status = http.StatusNoContent
		}
		resp := &http.Response{
			StatusCode:    status,
			ProtoMajor:    1,
			ProtoMinor:    1,
			Header:        make(http.Header),
			ContentLength: 0,
			Body:          io.NopCloser(strings.NewReader("")),
		}
		resp.Header.Set("Connection", "keep-alive")
		return resp

	case ActionMockJSON:
		status := ic.MockStatus
		if status == 0 {
			status = http.StatusOK
		}
		body := ic.MockBody
		if body == "" {
			body = "{}"
		}
		resp := &http.Response{
			StatusCode:    status,
			ProtoMajor:    1,
			ProtoMinor:    1,
			Header:        make(http.Header),
			ContentLength: int64(len(body)),
			Body:          io.NopCloser(strings.NewReader(body)),
		}
		ct := ic.ContentType
		if ct == "" {
			ct = "application/json; charset=utf-8"
		}
		resp.Header.Set("Content-Type", ct)
		resp.Header.Set("Connection", "keep-alive")
		return resp

	case ActionRedirect:
		if ic.RedirectURL == "" {
			return nil
		}
		resp := &http.Response{
			StatusCode: http.StatusFound,
			ProtoMajor: 1, ProtoMinor: 1,
			Header:        make(http.Header),
			ContentLength: 0,
			Body:          io.NopCloser(strings.NewReader("")),
		}
		resp.Header.Set("Location", ic.RedirectURL)
		resp.Header.Set("Connection", "keep-alive")
		return resp

	case ActionReject:
		fallthrough
	default:
		resp := &http.Response{
			StatusCode:    http.StatusForbidden,
			ProtoMajor:    1,
			ProtoMinor:    1,
			Header:        make(http.Header),
			ContentLength: 19,
			Body:          io.NopCloser(strings.NewReader("Blocked by BlockAds")),
		}
		resp.Header.Set("Connection", "keep-alive")
		return resp
	}
}

// SetRuleManifestJSON sets the global dynamic rule manifest from JSON string.
func SetRuleManifestJSON(jsonStr string) error {
	return defaultManifest.LoadJSON(jsonStr)
}
