/**
 * BlockAds - YouTube Player Sanitizer & Ad Blocker Scriptlet
 * Adopted from AdGuard CoreLibs & Scriptlets specification.
 * 
 * Intercepts YouTube player APIs at the data layer to eliminate ad requests
 * and payloads before playback starts, ensuring seamless video transitions
 * without black screens or audio cuts.
 */
(function() {
    'use strict';
    if (window.__blockads_yt_sanitizer_injected) return;
    window.__blockads_yt_sanitizer_injected = true;

    // 1. Pretend Google Ad status script loaded successfully to prevent anti-adblock
    window.google_ad_status = 1;

    // 2. Disable experimental web streaming watch flags known to force midrolls
    try {
        window.ytcfg = window.ytcfg || {};
        window.ytcfg.d = window.ytcfg.d || function() { return window.ytcfg.data_ || (window.ytcfg.data_ = {}); };
        var cfgData = window.ytcfg.data_ || (window.ytcfg.data_ = {});
        cfgData.EXPERIMENT_FLAGS = cfgData.EXPERIMENT_FLAGS || {};
        cfgData.EXPERIMENT_FLAGS.web_streaming_watch = false;
    } catch (e) {}

    // 3. Neutralize YouTube ServiceWorker so fetch/XHR hooks intercept player responses
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator && location.hostname.indexOf('youtube.com') !== -1) {
        try {
            navigator.serviceWorker.getRegistrations().then(function(regs) {
                for (var i = 0; i < regs.length; i++) { regs[i].unregister(); }
            }).catch(function(){});
            navigator.serviceWorker.register = function() {
                return new Promise(function() {});
            };
        } catch (e) {}
    }

    // 4. AdGuard Mobile YouTube JSON.stringify Hook:
    // Injects lactMilliseconds into contentPlaybackContext when adPlaybackContext is undefined.
    // YouTube server interprets this as active user interaction and does NOT serve ad payloads.
    try {
        var origStringify = window.JSON.stringify;
        var stringifyProxy = {
            apply: function(target, thisArg, args) {
                if (location.href.indexOf('/shorts/') !== -1 ||
                    location.href.indexOf('youtube.com/tv') !== -1 ||
                    location.href.indexOf('youtube.com/embed/') !== -1) {
                    return Reflect.apply(target, thisArg, args);
                }
                try {
                    var a = args[0];
                    if (a && a.context && a.context.client) {
                        var now = String(Date.now());
                        if (a.playbackContext && a.playbackContext.adPlaybackContext === undefined) {
                            if (a.playbackContext.contentPlaybackContext) {
                                a.playbackContext.contentPlaybackContext.lactMilliseconds = now;
                            }
                        }
                        if (a.playerRequest && a.playerRequest.playbackContext && a.playerRequest.playbackContext.adPlaybackContext === undefined) {
                            if (a.playerRequest.playbackContext.contentPlaybackContext) {
                                a.playerRequest.playbackContext.contentPlaybackContext.lactMilliseconds = now;
                            }
                        }
                        args[0] = a;
                    }
                } catch (err) {}
                return Reflect.apply(target, thisArg, args);
            }
        };
        window.JSON.stringify = new Proxy(origStringify, stringifyProxy);
    } catch (e) {}

    // 5. Data Sanitizer (AdGuard json-prune logic)
    function sanitizeData(data) {
        if (!data || typeof data !== 'object') return data;
        try {
            delete data.playerAds;
            delete data.adPlacements;
            delete data.adSlots;
            delete data.adPlacementConfig;
            delete data.adBreakHeartbeatParams;
            delete data.adBreakParams;
            delete data.masthead;

            if (data.playerConfig && data.playerConfig.adConfig) {
                delete data.playerConfig.adConfig;
            }
            if (data.playerConfig && data.playerConfig.audioConfig && data.playerConfig.audioConfig.muteOnStart) {
                delete data.playerConfig.audioConfig.muteOnStart;
            }
            if (data.messages && Array.isArray(data.messages)) {
                data.messages = data.messages.filter(function(m) {
                    return !m || !m.youThereRenderer;
                });
            }
            if (data.auxiliaryUi && data.auxiliaryUi.messageRenderers) {
                delete data.auxiliaryUi.messageRenderers.upsellDialogRenderer;
            }
            if (data.playerResponse && typeof data.playerResponse === 'object') {
                sanitizeData(data.playerResponse);
            }
        } catch (e) {}
        return data;
    }

    // 6. Trap ytInitialPlayerResponse & ytInitialData
    var _ytInitialPlayerResponse = window.ytInitialPlayerResponse;
    try {
        Object.defineProperty(window, 'ytInitialPlayerResponse', {
            configurable: true,
            get: function() { return _ytInitialPlayerResponse; },
            set: function(val) { _ytInitialPlayerResponse = sanitizeData(val); }
        });
    } catch (e) {}
    if (window.ytInitialPlayerResponse) sanitizeData(window.ytInitialPlayerResponse);

    var _ytInitialData = window.ytInitialData;
    try {
        Object.defineProperty(window, 'ytInitialData', {
            configurable: true,
            get: function() { return _ytInitialData; },
            set: function(val) { _ytInitialData = sanitizeData(val); }
        });
    } catch (e) {}
    if (window.ytInitialData) sanitizeData(window.ytInitialData);

    // 7. AdGuard Promise.prototype.then proxy for Protobuf / jspbResponseCtor
    try {
        var protoThen = window.Promise.prototype.then;
        var jspbHandler = {
            apply: function(target, thisArg, args) {
                var res = Reflect.apply(target, thisArg, args);
                if (res && res.responseContext) {
                    sanitizeData(res);
                }
                return res;
            }
        };
        var thenHandler = {
            apply: function(target, thisArg, args) {
                var r = args[0];
                if (typeof r === 'function') {
                    var rStr = r.toString();
                    if (rStr.indexOf('jspbResponseCtor') !== -1 || rStr.indexOf('.next(') !== -1) {
                        args[0] = new Proxy(r, jspbHandler);
                    }
                }
                return Reflect.apply(target, thisArg, args);
            }
        };
        window.Promise.prototype.then = new Proxy(protoThen, thenHandler);
    } catch (e) {}

    // 8. Hook Response.prototype.json (AdGuard json-prune-fetch-response)
    if (typeof Response !== 'undefined' && Response.prototype && Response.prototype.json) {
        var origResponseJson = Response.prototype.json;
        Response.prototype.json = function() {
            return origResponseJson.apply(this, arguments).then(function(json) {
                return sanitizeData(json);
            });
        };
    }

    // 9. Hook window.fetch for ad breaks
    if (typeof window.fetch === 'function') {
        var origFetch = window.fetch;
        window.fetch = async function() {
            var url = typeof arguments[0] === 'string' ? arguments[0] : (arguments[0] && arguments[0].url);
            if (url && typeof url === 'string') {
                if (url.indexOf('/youtubei/v1/player/ad_break') !== -1 || url.indexOf('/get_midroll_info') !== -1) {
                    return new Response('{}', {
                        status: 200,
                        statusText: 'OK',
                        headers: new Headers({ 'content-type': 'application/json; charset=utf-8' })
                    });
                }
            }
            return origFetch.apply(this, arguments);
        };
    }

    // 10. Hook XMLHttpRequest
    if (typeof XMLHttpRequest !== 'undefined') {
        var origOpen = XMLHttpRequest.prototype.open;
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            this._blockads_url = url;
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            if (this._blockads_url && typeof this._blockads_url === 'string') {
                if (this._blockads_url.indexOf('/youtubei/v1/player/ad_break') !== -1 || this._blockads_url.indexOf('/get_midroll_info') !== -1) {
                    this.addEventListener('readystatechange', function() {
                        if (this.readyState === 4) {
                            Object.defineProperty(this, 'status', { value: 200 });
                            Object.defineProperty(this, 'responseText', { value: '{}' });
                            Object.defineProperty(this, 'response', { value: '{}' });
                        }
                    });
                } else if (this._blockads_url.indexOf('/youtubei/v1/player') !== -1) {
                    this.addEventListener('readystatechange', function() {
                        if (this.readyState === 4 && this.responseText) {
                            try {
                                var data = JSON.parse(this.responseText);
                                sanitizeData(data);
                                var clean = JSON.stringify(data);
                                Object.defineProperty(this, 'responseText', { value: clean });
                                Object.defineProperty(this, 'response', { value: clean });
                            } catch (e) {}
                        }
                    });
                }
            }
            return origSend.apply(this, arguments);
        };
    }

    // 11. CSS-based instant ad hiding (Zero CPU overhead)
    try {
        var adCss = document.getElementById('__blockads_yt_ad_css');
        if (!adCss) {
            adCss = document.createElement('style');
            adCss.id = '__blockads_yt_ad_css';
            adCss.textContent = 
                'ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ' +
                'ytm-ad-slot-renderer, ytm-statement-banner-renderer, ' +
                'ytm-brand-video-singleton-renderer, ytm-in-feed-ad-layout-renderer, #masthead-ad, ' +
                '.ad-container, .ytp-ad-overlay-container, ytm-promoted-video-renderer { display: none !important; }';
            (document.head || document.documentElement).appendChild(adCss);
        }
    } catch (e) {}

    // Safe DOM cleanup for sponsored cards (runs on navigation & throttled)
    function cleanFeedAds() {
        try {
            var badges = document.querySelectorAll('yt-metadata-badge-renderer, ytm-badge-and-byline-renderer, badge-shape, .badge');
            for (var j = 0; j < badges.length; j++) {
                var bt = (badges[j].textContent || '').trim().toLowerCase();
                if (bt === 'sponsored' || bt === 'được tài trợ' || bt === 'quảng cáo' || bt === 'ad') {
                    var card = badges[j].closest('ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-item-section-renderer, ytm-rich-section-renderer');
                    if (card) card.remove();
                }
            }
        } catch (e) {}
    }

    setInterval(cleanFeedAds, 2500);
    window.addEventListener('yt-navigate-finish', cleanFeedAds, { passive: true });
    window.addEventListener('yt-page-data-updated', cleanFeedAds, { passive: true });

    // 12. Picture-in-Picture window management
    try {
        var normalStyle = document.getElementById('__blockads_normal_video_style');
        if (!normalStyle) {
            normalStyle = document.createElement('style');
            normalStyle.id = '__blockads_normal_video_style';
            normalStyle.textContent = 'html:not(.__blockads_pip_active) body #movie_player video, html:not(.__blockads_pip_active) body div.html5-video-player video { top: 0px !important; }';
            (document.head || document.documentElement).appendChild(normalStyle);
        }
    } catch (e) {}

    window.__blockads_set_pip = function(enable) {
        window.__blockads_force_pip = enable;
        if (enable) {
            document.documentElement.classList.add('__blockads_pip_active');
            var v = document.querySelector('video');
            if (v && v.paused) {
                v.play().catch(function(){});
            }
        } else {
            document.documentElement.classList.remove('__blockads_pip_active');
            var v = document.querySelector('video');
            if (v) {
                v.style.top = '0px';
                v.style.left = '0px';
            }
            var mp = document.querySelector('#movie_player');
            if (mp) {
                if (v && !v.paused) {
                    mp.classList.remove('paused-mode');
                    mp.classList.add('playing-mode');
                    mp.classList.add('ytp-autohide-active');
                    if (typeof mp.playVideo === 'function') {
                        try { mp.playVideo(); } catch(e) {}
                    }
                }
            }
            window.dispatchEvent(new Event('resize'));
        }
    };
})();
