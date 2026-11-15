package mitm

import (
	"net/http"
	"strings"
)

// YouTubeInterceptor handles specialized YouTube protocol rewrites (ctier stripping, protobuf filtering).
type YouTubeInterceptor struct {
	blocker adBlockChecker
	flow    FlowID
}

// NewYouTubeInterceptor creates a new YouTubeInterceptor.
func NewYouTubeInterceptor() *YouTubeInterceptor {
	return NewYouTubeInterceptorWithFlow(nil, FlowID{})
}

// NewYouTubeInterceptorWithFlow creates a YouTubeInterceptor bound to a blocker and flow for whitelist/logging.
func NewYouTubeInterceptorWithFlow(blocker adBlockChecker, flow FlowID) *YouTubeInterceptor {
	return &YouTubeInterceptor{
		blocker: blocker,
		flow:    flow,
	}
}

func (y *YouTubeInterceptor) Name() string {
	return "YouTubeInterceptor"
}

func (y *YouTubeInterceptor) Matches(host string, req *http.Request) bool {
	return IsYouTubeHost(host)
}

func (y *YouTubeInterceptor) OnRequest(req *http.Request) (bool, *http.Response) {
	host := normalizeHost(req.Host)
	if host != "" && isDomainWhitelisted(y.blocker, host) {
		return false, nil
	}
	// Let HandleYouTubeRequest perform ctier=L stripping, legacy ad-path block, and Accept-Encoding rewrites
	handled, resp := HandleYouTubeRequest(req)
	if handled {
		blocked := resp == nil || resp.StatusCode != http.StatusFound
		reason := ""
		if blocked {
			reason = "youtube_adblock"
		}
		logMitmQuery(y.blocker, y.flow, host, blocked, reason, 65)
	}
	return handled, resp
}

func (y *YouTubeInterceptor) OnResponse(req *http.Request, resp *http.Response) error {
	host := normalizeHost(req.Host)
	if host != "" && isDomainWhitelisted(y.blocker, host) {
		return nil
	}
	if strings.Contains(host, "youtubei") {
		FilterYouTubeResponse(req, resp)
	}
	return nil
}
