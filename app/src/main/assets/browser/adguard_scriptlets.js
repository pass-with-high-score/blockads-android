/**
 * BlockAds - AdGuard-Grade General Scriptlets
 * Defuses Popups, Pop-unders, Clickjacking Invisible Overlays,
 * Fake Click Triggers, Catfish Banners and Anti-AdBlock Traps on all websites.
 */
(function() {
    'use strict';
    if (window.__blockads_scriptlets_injected) return;
    window.__blockads_scriptlets_injected = true;

    // Immediate unblur & scroll unlock
    try {
        if (document.documentElement) document.documentElement.classList.remove('xh-thumb-disabled');
        if (document.body) {
            document.body.classList.remove('xh-scroll-disabled');
            document.body.style.position = 'static';
            document.body.style.overflow = 'auto';
        }
    } catch(e) {}

    // --- 0. ADGUARD CONSTANT DEFUSER (set-constant) ---
    try {
        // Defuse Vietnamese streaming/18+ ad engines (adxcontent, vlit, etc.)
        Object.defineProperty(window, 'show_adx', {
            get: function() { return 0; },
            set: function() {},
            configurable: false
        });
        Object.defineProperty(window, 'codeAdx', {
            get: function() { return function() {}; },
            set: function() {},
            configurable: false
        });
        // Falsify timeclick so popunder engines believe user click quota is maxed out
        window.timeclick = Date.now() + 864000000;

        // Neutralize common anti-debugger / devtools traps
        window.devtoolsDetector = {
            addListener: function() {},
            launch: function() {},
            stop: function() {}
        };
    } catch(e) {}

    // --- 1. POPUP & POPUNDER DEFUSER ---
    var originalOpen = window.open;
    var lastUserInteractionTime = 0;

    function recordUserInteraction() {
        lastUserInteractionTime = Date.now();
    }

    window.addEventListener('click', recordUserInteraction, true);
    window.addEventListener('touchend', recordUserInteraction, true);
    window.addEventListener('keydown', recordUserInteraction, true);

    var BLOCKED_PATTERNS = [
        'shopee', 'lazada', 'tiki', 'affiliate', 'tracking',
        'popads', 'popcash', 'propeller', 'adsterra', 'clickadu',
        'exoclick', 'exosrv', 'doubleclick', 'adnxs', 'mgid', 'taboola',
        'adxcontent', 'adxmedia', 'vlit', 'catfish', 'popunder', 'clumsy-whereas',
        // External social app hijacking from ads (TikTok / Snack / etc)
        'tiktok', 'snssdk', 'bytedance', 'musically', 'douyin',
        // Gambling & Betting networks commonly injected via popunders
        'lu88', 'hbet', 'vu88', 'man88', 'k88', 'tx88', 'du88', 'x1bet',
        'bet88', 'kubet', 'shbet', '789bet', 'okvip', 'jun88', 'hi88',
        'f8bet', 'mb66', '123b', 'w88', 'fb88', 'fun88', 'bk8', 'casino',
        'gamebaidoithuong', 'taixiu', 'nổ hũ', 'baccarat'
    ];

    function isAdOrMaliciousUrl(url) {
        if (!url || typeof url !== 'string') return false;
        var lower = url.toLowerCase();
        for (var i = 0; i < BLOCKED_PATTERNS.length; i++) {
            if (lower.indexOf(BLOCKED_PATTERNS[i]) !== -1) {
                return true;
            }
        }
        return false;
    }

    // Dummy Window proxy to satisfy callers expecting a Window object
    function createDummyWindow() {
        var dummyLocation = {
            _href: 'about:blank',
            get href() { return this._href; },
            set href(val) { console.info('[BlockAds] Blocked popup redirect to:', val); },
            replace: function() {},
            assign: function() {}
        };
        return {
            closed: false,
            focus: function() {},
            blur: function() {},
            close: function() {},
            postMessage: function() {},
            location: dummyLocation,
            document: { write: function() {}, close: function() {} }
        };
    }

    window.open = function(url, target, features) {
        var isRecentUserAction = (Date.now() - lastUserInteractionTime) < 1200;

        // Block if not explicit recent human tap OR points to ad/gambling URL
        if (!isRecentUserAction || isAdOrMaliciousUrl(url)) {
            console.info('[BlockAds] Prevented suspicious popup:', url);
            return createDummyWindow();
        }

        return originalOpen.apply(this, arguments);
    };

    // --- 2. FAKE CLICK & CLICKJACKING DEFUSER (AdGuard prevent-popunder) ---
    var origDispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function(event) {
        if (event && (event.type === 'click' || event.type === 'mousedown')) {
            if (this instanceof HTMLAnchorElement) {
                if (isAdOrMaliciousUrl(this.href) || (this.id && this.id.indexOf('bb') === 0)) {
                    console.info('[BlockAds] Blocked synthetic click on ad link:', this.href);
                    return false;
                }
            }
        }
        return origDispatch.apply(this, arguments);
    };

    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        var isRecentUserAction = (Date.now() - lastUserInteractionTime) < 1200;

        // Check for hidden popunder triggers (e.g. <a id="bb0" style="opacity:0; width:1px">)
        var isHiddenPopunder = false;
        try {
            var s = window.getComputedStyle(this);
            var rect = this.getBoundingClientRect();
            if (s.opacity === '0' || s.display === 'none' || s.visibility === 'hidden' || (rect.width <= 5 && rect.height <= 5)) {
                isHiddenPopunder = true;
            }
        } catch(e) {}

        if (this.id && this.id.indexOf('bb') === 0) isHiddenPopunder = true;

        if (!isRecentUserAction || isHiddenPopunder || isAdOrMaliciousUrl(this.href)) {
            console.info('[BlockAds] Blocked programmatic anchor popunder click:', this.href);
            return;
        }
        return origAnchorClick.apply(this, arguments);
    };

    // --- 3. INVISIBLE OVERLAY & CATFISH BANNER PURGE ---
    function purgeAdArtifacts() {
        try {
            // A. Remove known catfish banners & popup containers
            var adSelectors = [
                '.catfish-top', '.catfish-bottom', '.banner-catfish-top', '.banner-catfish-bottom',
                '.banner-preload', '.banner-preload-container', '.banner-preload-close',
                '#vl-top-adx', '#vl-native-adx', '#vl-underplayer-adx', '#adx',
                'a[id^="bb"]', 'a[style*="1px"]', 'a[style*="opacity:0"]', 'a[style*="opacity: 0"]',
                '.▶', '.▶__wrap', '.▶__iframe', '[class*="▶"]', 'iframe[src*="clumsy-whereas"]',
                '.section_ads_300x250', '.section_ads', '.banner_mobile_300x250', '#banner_top', '#TOP_BANNER', 'div[id^="sis_"]',
                '#bottom-slider', '.apkm-timed-slider', '.ains', '[class*="ains-"]', '.advertisement-text', '[id*="ai_widget"]'
            ];
            var adEls = document.querySelectorAll(adSelectors.join(','));
            for (var i = 0; i < adEls.length; i++) {
                var el = adEls[i];
                el.style.setProperty('display', 'none', 'important');
                el.style.setProperty('pointer-events', 'none', 'important');
                if (el.parentNode) el.parentNode.removeChild(el);
            }

            // B. Remove any anchors pointing to gambling or ad networks
            var links = document.querySelectorAll('a[href]');
            for (var j = 0; j < links.length; j++) {
                var link = links[j];
                if (isAdOrMaliciousUrl(link.href)) {
                    // If it is a floating or standalone banner link, remove parent container
                    var parent = link.closest('.catfish-top, .catfish-bottom, .banner-preload, [class*="catfish"], [class*="banner"]');
                    if (parent && parent.parentNode) {
                        parent.parentNode.removeChild(parent);
                    } else if (link.parentNode) {
                        link.parentNode.removeChild(link);
                    }
                }
            }

            // C. Scan full-viewport invisible overlays (Clickjacking traps)
            var screenW = window.innerWidth || document.documentElement.clientWidth || 360;
            var screenH = window.innerHeight || document.documentElement.clientHeight || 640;
            var minArea = screenW * screenH * 0.4;

            var candidates = document.querySelectorAll('a, div, span, section');
            for (var k = 0; k < candidates.length; k++) {
                var c = candidates[k];
                if (c.id === '__blockads_pip_box' || c.querySelector('[role="dialog"]') || c.getAttribute('role') === 'dialog') continue;

                var style = window.getComputedStyle(c);
                if (style.position === 'fixed' || style.position === 'absolute') {
                    var zIndex = parseInt(style.zIndex, 10);
                    var opacity = parseFloat(style.opacity);

                    if (zIndex >= 50 && (opacity <= 0.1 || style.visibility === 'hidden' || style.backgroundColor === 'rgba(0, 0, 0, 0)')) {
                        var r = c.getBoundingClientRect();
                        if ((r.width * r.height) >= minArea) {
                            c.style.pointerEvents = 'none';
                            c.style.display = 'none';
                            if (c.parentNode) c.parentNode.removeChild(c);
                            console.info('[BlockAds] Removed clickjacking overlay');
                        }
                    }
                }
            }

            // D. Remove Anti-Adblock popup modals only (never touch navigation menus/drawers)
            var dialogs = document.querySelectorAll('[role="dialog"], [role="alertdialog"]');
            for (var m = 0; m < dialogs.length; m++) {
                var dlg = dialogs[m];
                var txt = (dlg.innerText || '');
                if (txt.indexOf('Ad Blocker') > -1 || txt.indexOf('ad blocker') > -1 || txt.indexOf('Adblock') > -1) {
                    var parentModal = dlg.closest('[tabindex="-1"]') || dlg.parentElement;
                    if (parentModal && parentModal.parentNode) parentModal.parentNode.removeChild(parentModal);
                    var modalBackdrops = document.querySelectorAll('.z-50.backdrop-blur-md.bg-black\\/80, .z-50.backdrop-blur-md.bg-black\\/70');
                    for (var b = 0; b < modalBackdrops.length; b++) {
                        modalBackdrops[b].remove();
                    }
                    document.documentElement.style.overflow = 'auto';
                    document.body.style.overflow = 'auto';
                    var hiddenNodes = document.querySelectorAll('[aria-hidden="true"]');
                    for (var h = 0; h < hiddenNodes.length; h++) {
                        if (hiddenNodes[h].getAttribute('data-overlay-container') === 'true') {
                            hiddenNodes[h].removeAttribute('aria-hidden');
                        }
                    }
                }
            }

            // E. Remove forced page blur & locked scroll (xHamster, age verification gates)
            if (document.documentElement && document.documentElement.classList.contains('xh-thumb-disabled')) {
                document.documentElement.classList.remove('xh-thumb-disabled');
            }
            if (document.body && document.body.classList.contains('xh-scroll-disabled')) {
                document.body.classList.remove('xh-scroll-disabled');
                document.body.style.position = 'static';
                document.body.style.overflow = 'auto';
            }
            var blurredWraps = document.querySelectorAll('.main-wrap[style*="blur"]');
            for (var bw = 0; bw < blurredWraps.length; bw++) {
                blurredWraps[bw].style.filter = 'none';
            }
            var cookieModals = document.querySelectorAll('[data-role="cookies-modal"], [data-role="dialog-manager"]');
            for (var cm = 0; cm < cookieModals.length; cm++) {
                cookieModals[cm].remove();
            }
            var adWidgets = document.querySelectorAll('.thumb-list-mobile-item--widget, [class*="thumb-list-mobile-item--widget"], [data-role="promo-messages-wrapper"]');
            for (var aw = 0; aw < adWidgets.length; aw++) {
                adWidgets[aw].remove();
            }

            // F. Hide streaming gambling popups & auto-skip video ads
            var motphimAds = document.querySelectorAll('div.fixed.inset-0.z-\\[9999\\], div[class*="fixed"][class*="inset-0"]:has(button), div[class*="fixed"]:has(img[src*="offa"]), div:has(> a.no-ads-under)');
            for (var ma = 0; ma < motphimAds.length; ma++) {
                motphimAds[ma].style.setProperty('display', 'none', 'important');
                motphimAds[ma].style.setProperty('pointer-events', 'none', 'important');
            }
            var skipBtn = document.querySelector('.jw-skip, .videoAdUiSkipButton, .ytp-ad-skip-button, .ytp-skip-ad-button');
            if (skipBtn) {
                try { skipBtn.click(); } catch(e) {}
            }
            if (window.jwplayer && typeof window.jwplayer === 'function') {
                try {
                    var jp = window.jwplayer();
                    if (jp && typeof jp.skipAd === 'function') {
                        jp.skipAd();
                    }
                } catch(e) {}
            }
        } catch(e) {}
    }

    // Run purge immediately, repeatedly, and on DOM mutation
    purgeAdArtifacts();
    setInterval(purgeAdArtifacts, 600);

    if (document.body) {
        var observer = new MutationObserver(function() {
            purgeAdArtifacts();
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    // --- 4. ANTI-ADBLOCK DEFUSER & SPOOFING ---
    try {
        window.canRunAds = true;
        window.isAdBlockActive = false;
        window.adsBlocked = false;
        window.google_ad_status = 1;
        window.abp = false;

        // AdsbyGoogle surrogate for AdSense detectors
        window.adsbygoogle = window.adsbygoogle || [];
        window.adsbygoogle.loaded = true;
        window.adsbygoogle.push = function() {};

        // Anti-Adblock Bait Unhide (defeat geometry detection on #banner_ad)
        try {
            var baitStyle = document.createElement('style');
            baitStyle.textContent = '#banner_ad, div#banner_ad { display: block !important; visibility: visible !important; width: 300px !important; min-width: 300px !important; max-width: 300px !important; height: 250px !important; min-height: 250px !important; max-height: 250px !important; left: -9999px !important; position: absolute !important; }';
            (document.head || document.documentElement).appendChild(baitStyle);
        } catch(e) {}

        if (!window.ga) {
            window.ga = function() {};
            window.ga.loaded = true;
        }

        if (!window.googletag) {
            window.googletag = {
                cmd: [],
                display: function() {},
                openConsole: function() {},
                enableServices: function() {},
                pubads: function() {
                    return {
                        addEventListener: function() {},
                        clear: function() {},
                        collapseEmptyDivs: function() {},
                        disableInitialLoad: function() {},
                        enableSingleRequest: function() {},
                        refresh: function() {}
                    };
                }
            };
        }
    } catch(e) {}

    // --- 5. BLOCK NAVIGATION HIJACKING & UNLOAD TRAPS ---
    window.addEventListener('beforeunload', function(e) {
        e.stopImmediatePropagation();
    }, true);

})();
