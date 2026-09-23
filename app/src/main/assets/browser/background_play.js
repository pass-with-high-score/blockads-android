/**
 * BlockAds - Background Play Scriptlet
 * Inspired by uBlock Origin & Brave Browser background play fixes.
 * Spoofs Page Visibility API, Page Lifecycle API, and hooks HTMLMediaElement.pause
 * to keep media playing when screen is locked or switching apps.
 */
(function() {
    'use strict';
    if (window.__blockads_bg_play_injected) return;
    window.__blockads_bg_play_injected = true;

    try {
        // 1. Force Page Visibility API properties to always report visible
        Object.defineProperty(document, 'hidden', {
            configurable: true,
            get: function() { return false; }
        });

        Object.defineProperty(document, 'visibilityState', {
            configurable: true,
            get: function() { return 'visible'; }
        });

        Object.defineProperty(document, 'webkitVisibilityState', {
            configurable: true,
            get: function() { return 'visible'; }
        });

        // 2. Force hasFocus to always return true
        document.hasFocus = function() { return true; };
        window.hasFocus = function() { return true; };

        // 3. Block all visibility & lifecycle freeze events
        var blockedEvents = [
            'visibilitychange',
            'webkitvisibilitychange',
            'pagehide',
            'freeze',
            'blur'
        ];

        function stopPropagation(e) {
            e.stopImmediatePropagation();
        }

        for (var i = 0; i < blockedEvents.length; i++) {
            window.addEventListener(blockedEvents[i], stopPropagation, true);
            document.addEventListener(blockedEvents[i], stopPropagation, true);
        }

        // 4. Hook HTMLMediaElement.prototype.pause
        // YouTube calls video.pause() when visibility changes or screen locks.
        // We only allow pause if triggered by a user action (click/touch) on the page.
        var isUserAction = false;
        var resetTimer = null;

        function markUserAction() {
            isUserAction = true;
            if (resetTimer) clearTimeout(resetTimer);
            resetTimer = setTimeout(function() {
                isUserAction = false;
            }, 500);
        }

        window.addEventListener('click', markUserAction, true);
        window.addEventListener('touchend', markUserAction, true);
        window.addEventListener('keydown', markUserAction, true);

        var originalPause = HTMLMediaElement.prototype.pause;
        HTMLMediaElement.prototype.pause = function() {
            // If pause was called automatically while not in an active user gesture, ignore
            if (!isUserAction) {
                return;
            }
            return originalPause.apply(this, arguments);
        };

        // 5. Keep MediaSession active
        if ('mediaSession' in navigator) {
            try {
                navigator.mediaSession.setActionHandler('pause', function() {
                    isUserAction = true;
                    var v = document.querySelector('video');
                    if (v) originalPause.call(v);
                });
            } catch (e) {}
        }
    } catch (e) {}
})();
