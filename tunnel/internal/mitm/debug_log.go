package mitm

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
)

// MitmDebugEvent represents an HTTP request or injected scriptlet event observed in MITM mode.
type MitmDebugEvent struct {
	Action       string
	Rule         string
	Method       string
	ResourceType string
	Initiator    string
	URL          string
	Detail       string
}

type mitmDebugLoggerHolder struct {
	fn func(MitmDebugEvent)
}

var activeDebugLogger atomic.Value

// SetDebugLogger registers the callback that receives MITM HTTP and scriptlet debug events.
func SetDebugLogger(fn func(MitmDebugEvent)) {
	if fn == nil {
		activeDebugLogger.Store(mitmDebugLoggerHolder{})
		return
	}
	activeDebugLogger.Store(mitmDebugLoggerHolder{fn: fn})
}

func emitDebugEvent(ev MitmDebugEvent) {
	v := activeDebugLogger.Load()
	if v == nil {
		return
	}
	holder, ok := v.(mitmDebugLoggerHolder)
	if !ok || holder.fn == nil {
		return
	}
	holder.fn(ev)
}

type scriptletBeaconPayload struct {
	Rule   string `json:"rule"`
	URL    string `json:"url"`
	Detail string `json:"detail"`
}

func handleBlockAdsLogBeacon(req *http.Request, fallbackHost string) *http.Response {
	if req != nil && req.Body != nil {
		raw, _ := io.ReadAll(io.LimitReader(req.Body, 8192))
		req.Body.Close()
		var payload scriptletBeaconPayload
		if err := json.Unmarshal(raw, &payload); err == nil && payload.Rule != "" {
			emitDebugEvent(MitmDebugEvent{
				Action:       "SCRIPTLET",
				Rule:         payload.Rule,
				Method:       "JS",
				ResourceType: "scriptlet",
				Initiator:    extractInitiator(req, fallbackHost),
				URL:          payload.URL,
				Detail:       payload.Detail,
			})
		}
	}
	return &http.Response{
		StatusCode: http.StatusNoContent,
		Status:     "204 No Content",
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header:     http.Header{"Cache-Control": {"no-store"}},
		Body:       io.NopCloser(bytes.NewReader(nil)),
	}
}

func fullRequestURL(reqHost string, req *http.Request) string {
	if req == nil || req.URL == nil {
		return reqHost
	}
	if req.URL.Scheme != "" {
		return req.URL.String()
	}
	return "https://" + reqHost + req.URL.RequestURI()
}

func extractInitiator(req *http.Request, fallbackHost string) string {
	if req != nil {
		for _, hdr := range []string{"Referer", "Origin"} {
			if raw := req.Header.Get(hdr); raw != "" {
				if u, err := url.Parse(raw); err == nil && u.Hostname() != "" {
					return strings.ToLower(u.Hostname())
				}
			}
		}
	}
	return strings.ToLower(normalizeHost(fallbackHost))
}

func inferResourceType(req *http.Request) string {
	if req == nil {
		return "other"
	}
	if dest := strings.ToLower(req.Header.Get("Sec-Fetch-Dest")); dest != "" {
		switch dest {
		case "iframe", "frame":
			return "frame"
		case "empty":
			return "xhr"
		case "style":
			return "stylesheet"
		default:
			return dest
		}
	}
	if req.URL != nil {
		p := strings.ToLower(req.URL.Path)
		switch {
		case strings.HasSuffix(p, ".js") || strings.Contains(p, ".js?"):
			return "script"
		case strings.HasSuffix(p, ".css"):
			return "stylesheet"
		case strings.HasSuffix(p, ".png") || strings.HasSuffix(p, ".jpg") || strings.HasSuffix(p, ".webp") || strings.HasSuffix(p, ".gif"):
			return "image"
		}
	}
	if strings.Contains(strings.ToLower(req.Header.Get("Accept")), "text/html") {
		return "document"
	}
	return "other"
}
