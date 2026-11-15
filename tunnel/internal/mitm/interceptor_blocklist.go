package mitm

import (
	"io"
	"net/http"
	"strings"
)

// BlocklistInterceptor blocks domains matching DNS ad/malware tries and legacy ad path patterns.
type BlocklistInterceptor struct {
	baseHostname string
	blocker      adBlockChecker
	filter       *MitmFilter
	flow         FlowID
}

// NewBlocklistInterceptor creates a BlocklistInterceptor.
func NewBlocklistInterceptor(baseHostname string, blocker adBlockChecker, filter *MitmFilter) *BlocklistInterceptor {
	return NewBlocklistInterceptorWithFlow(baseHostname, blocker, filter, FlowID{})
}

// NewBlocklistInterceptorWithFlow creates a BlocklistInterceptor bound to a flow for query logging.
func NewBlocklistInterceptorWithFlow(baseHostname string, blocker adBlockChecker, filter *MitmFilter, flow FlowID) *BlocklistInterceptor {
	return &BlocklistInterceptor{
		baseHostname: baseHostname,
		blocker:      blocker,
		filter:       filter,
		flow:         flow,
	}
}

func (b *BlocklistInterceptor) Name() string {
	return "BlocklistInterceptor"
}

func (b *BlocklistInterceptor) Matches(host string, req *http.Request) bool {
	return true
}

func (b *BlocklistInterceptor) OnRequest(req *http.Request) (bool, *http.Response) {
	reqHost := normalizeHost(req.Host)
	checkHost := reqHost
	if checkHost == "" {
		checkHost = normalizeHost(b.baseHostname)
	}
	if checkHost != "" && isDomainWhitelisted(b.blocker, checkHost) {
		return false, nil
	}

	// 1. Domain block check (e.g. sub-requests pointing to a blocked tracking domain)
	if b.blocker != nil && reqHost != "" && reqHost != b.baseHostname {
		if blocked, reason := checkDomainBlock(b.blocker, reqHost); blocked {
			logMitmQuery(b.blocker, b.flow, reqHost, true, reason, 65)
			resp := &http.Response{
				StatusCode:    http.StatusForbidden,
				ProtoMajor:    1,
				ProtoMinor:    1,
				ContentLength: 19,
				Header:        make(http.Header),
				Body:          io.NopCloser(strings.NewReader("Blocked by BlockAds")),
			}
			resp.Header.Set("Connection", "keep-alive")
			resp.Header.Set("Content-Length", "19")
			return true, resp
		}
	}

	// 2. Ad path block check
	if b.filter != nil && b.filter.IsAdPathBlocked(req.URL.Path) {
		logMitmQuery(b.blocker, b.flow, checkHost, true, "ad_path", 65)
		path := req.URL.Path
		if strings.Contains(path, "/ad_break") || strings.Contains(path, "/get_midroll_info") || strings.HasSuffix(path, ".json") {
			jsonResp := &http.Response{
				StatusCode:    http.StatusOK,
				ProtoMajor:    1,
				ProtoMinor:    1,
				Header:        make(http.Header),
				ContentLength: 2,
				Body:          io.NopCloser(strings.NewReader("{}")),
			}
			jsonResp.Header.Set("Content-Type", "application/json; charset=utf-8")
			jsonResp.Header.Set("Connection", "keep-alive")
			jsonResp.Header.Set("Content-Length", "2")
			return true, jsonResp
		}

		noContentResp := &http.Response{
			StatusCode: http.StatusNoContent,
			ProtoMajor: 1, ProtoMinor: 1,
			Header:        make(http.Header),
			ContentLength: 0,
			Body:          io.NopCloser(strings.NewReader("")),
		}
		noContentResp.Header.Set("Connection", "keep-alive")
		return true, noContentResp
	}

	return false, nil
}

func (b *BlocklistInterceptor) OnResponse(req *http.Request, resp *http.Response) error {
	return nil
}
