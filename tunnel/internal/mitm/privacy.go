package mitm

import (
	"net/http"
	"net/url"
	"strings"
)

// trackingParams contains common tracking query parameters that can be safely removed.
var trackingParams = map[string]bool{
	"utm_source":   true,
	"utm_medium":   true,
	"utm_campaign": true,
	"utm_term":     true,
	"utm_content":  true,
	"fbclid":       true,
	"gclid":        true,
	"dclid":        true,
	"msclkid":      true,
	"mc_eid":       true,
	"zanpid":       true,
	"igshid":       true,
	"_hsenc":       true,
	"_hsmi":        true,
	"mkt_tok":      true,
}

// SanitizeRequest removes privacy-invasive headers, strips tracking URL query parameters,
// and appends privacy standards (DNT, Sec-GPC).
func SanitizeRequest(req *http.Request, targetHost string) {
	if req == nil {
		return
	}

	// 1. Gỡ bỏ header định danh Chrome
	req.Header.Del("X-Client-Data")

	// 2. Set Global Privacy Control & Do Not Track
	req.Header.Set("DNT", "1")
	req.Header.Set("Sec-GPC", "1")

	// 3. Strip Referer on third-party requests to protect browsing history
	if ref := req.Header.Get("Referer"); ref != "" {
		if refURL, err := url.Parse(ref); err == nil {
			refHost := refURL.Hostname()
			targetHostClean := targetHost
			if i := strings.IndexByte(targetHostClean, ':'); i >= 0 {
				targetHostClean = targetHostClean[:i]
			}
			// If cross-origin / third-party, sanitize referer to origin only
			if refHost != "" && !strings.EqualFold(refHost, targetHostClean) {
				req.Header.Set("Referer", refURL.Scheme+"://"+refHost+"/")
			}
		}
	}

	// 4. Strip tracking parameters from query
	if req.URL != nil && req.URL.RawQuery != "" {
		cleanQuery(req)
	}
}

func cleanQuery(req *http.Request) {
	values := req.URL.Query()
	modified := false
	for param := range trackingParams {
		if _, exists := values[param]; exists {
			values.Del(param)
			modified = true
		}
	}
	if modified {
		req.URL.RawQuery = values.Encode()
		// Important for net/http: req.Write uses req.RequestURI if not empty.
		// Resetting req.RequestURI makes req.Write call req.URL.RequestURI(),
		// propagating the stripped parameters upstream.
		req.RequestURI = ""
	}
}
