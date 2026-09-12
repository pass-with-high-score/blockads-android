package app.pwhs.blockads.ui.browser.interceptor

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object BrowserAdBlocker {

    private val AD_HOST_KEYWORDS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "scorecardresearch.com",
        "zedo.com",
        "admob.com",
        "pagead2.googlesyndication.com",
        "adservice.google.com",
        "ads.youtube.com",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "fls-na.amazon.com",
        "fls-eu.amazon.com",
        "advertising.com",
        "rubiconproject.com",
        "pubmatic.com",
        "casalemedia.com",
        "openx.net"
    )

    private val AD_PATH_PATTERNS = listOf(
        "/ads.js",
        "/pagead/",
        "/doubleclick/",
        "/ad_status",
        "googletagservices.com/tag/js/gpt.js"
    )

    const val COSMETIC_CSS_SCRIPT = """
        (function() {
            if (window.__blockads_css_injected) return;
            window.__blockads_css_injected = true;
            var css = `
                .ad-banner, .adsbygoogle, .video-ads, 
                ytd-promoted-video-renderer, ytd-display-ad-renderer,
                ytd-statement-banner-renderer, ytd-banner-promo-renderer,
                .ytp-ad-module, .ytp-ad-overlay-container,
                .ytp-ad-player-overlay, ytd-in-feed-ad-layout-renderer,
                #player-ads, .sparkles-light-cta,
                div[class*="ad-container"], div[id*="google_ads"],
                ins.adsbygoogle { display: none !important; }
            `;
            var style = document.createElement('style');
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
        })();
    """

    const val YOUTUBE_AD_SKIP_SCRIPT = """
        (function() {
            if (window.__blockads_yt_skip_injected) return;
            window.__blockads_yt_skip_injected = true;
            setInterval(function() {
                // 1. Click skip ad buttons immediately
                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                if (skipBtn) { skipBtn.click(); }
                // 2. Mute & fast-forward ad videos
                var video = document.querySelector('video');
                var ad = document.querySelector('.ad-showing, .ytp-ad-player-overlay');
                if (ad && video && !isNaN(video.duration) && video.duration > 0) {
                    video.muted = true;
                    video.currentTime = video.duration;
                }
            }, 300);
        })();
    """

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val host = url.host?.lowercase(Locale.US) ?: return false
        val fullUrl = url.toString().lowercase(Locale.US)

        // 1. Check ad host keywords
        for (adHost in AD_HOST_KEYWORDS) {
            if (host == adHost || host.endsWith(".$adHost") || fullUrl.contains(adHost)) {
                return true
            }
        }

        // 2. Check ad path keywords
        for (pattern in AD_PATH_PATTERNS) {
            if (fullUrl.contains(pattern)) {
                return true
            }
        }

        return false
    }

    fun createBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun sanitizeSearchUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + Uri.encode(trimmed)
        }
    }
}
