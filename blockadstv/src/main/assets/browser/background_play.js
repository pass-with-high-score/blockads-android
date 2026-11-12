/**
 * BlockAds - Background Play Scriptlet
 * Inspired by uBlock Origin & Brave Browser background play fixes.
 * Spoofs Page Visibility API, Page Lifecycle API, and hooks HTMLMediaElement.pause
 * and YouTube player API to keep media playing when screen is locked or switching apps.
 * Also defeats the "Video paused. Continue watching?" inactivity dialogs.
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

        // 4. User action tracking
        // We only allow pause if triggered by a real user interaction on the page or media notification.
        var isUserAction = false;
        var userPaused = false;
        var resetTimer = null;

        function markUserAction() {
            isUserAction = true;
            if (resetTimer) clearTimeout(resetTimer);
            resetTimer = setTimeout(function() {
                isUserAction = false;
            }, 1000);
        }
        window.__blockads_trigger_user_action = markUserAction;
        window.__blockads_set_user_paused = function(paused) {
            userPaused = !!paused;
            markUserAction();
        };

        var userEvents = ['click', 'touchstart', 'touchend', 'pointerdown', 'pointerup', 'keydown'];
        for (var j = 0; j < userEvents.length; j++) {
            window.addEventListener(userEvents[j], markUserAction, true);
            document.addEventListener(userEvents[j], markUserAction, true);
        }

        document.addEventListener('pause', function() {
            if (isUserAction) {
                userPaused = true;
            }
        }, true);
        document.addEventListener('play', function() {
            userPaused = false;
        }, true);
        document.addEventListener('playing', function() {
            userPaused = false;
        }, true);

        // 5. Hook HTMLMediaElement.prototype.pause
        var originalPause = HTMLMediaElement.prototype.pause;
        HTMLMediaElement.prototype.pause = function() {
            if (!isUserAction && !userPaused) {
                return;
            }
            userPaused = true;
            return originalPause.apply(this, arguments);
        };

        // 5b. Catch and counter native pauses triggered by Chromium backgrounding / screen-off
        var resumeTimer = null;
        function onNativePause(e) {
            if (isUserAction || userPaused) return;
            try { e.stopImmediatePropagation(); } catch(err) {}
            if (resumeTimer) clearTimeout(resumeTimer);
            resumeTimer = setTimeout(function() {
                resumeTimer = null;
                if (!isUserAction && !userPaused) {
                    var v = document.querySelector('video');
                    var mp = document.querySelector('#movie_player') || document.querySelector('#player');
                    if (v && v.paused && !v.ended) {
                        try {
                            v.muted = false;
                            var p = v.play();
                            if (p && typeof p.catch === 'function') {
                                p.catch(function() {
                                    if (mp && typeof mp.playVideo === 'function') {
                                        try { mp.playVideo(); } catch(err) {}
                                    }
                                });
                            }
                        } catch(err) {}
                    }
                    if (mp && typeof mp.getPlayerState === 'function') {
                        var st = mp.getPlayerState();
                        if (st === 2 || st === -1 || st === 3) {
                            try { mp.playVideo(); } catch(err) {}
                        }
                    }
                }
            }, 60);
        }
        document.addEventListener('pause', onNativePause, true);

        // Continuous background keep-alive:
        // If media became paused without a user interaction (e.g. screen off or app minimized),
        // automatically resume playback.
        setInterval(function() {
            if (!isUserAction && !userPaused) {
                var v = document.querySelector('video');
                if (v && v.paused && v.currentTime > 0 && !v.ended) {
                    try {
                        v.muted = false;
                        var p = v.play();
                        if (p && typeof p.catch === 'function') {
                            p.catch(function() {
                                var mp = document.querySelector('#movie_player');
                                if (mp && typeof mp.playVideo === 'function') {
                                    try { mp.playVideo(); } catch(err) {}
                                }
                            });
                        }
                    } catch(err) {}
                }
            }
        }, 1500);

        // 6. Hook YouTube player pauseVideo API
        function hookMoviePlayer() {
            var mp = document.querySelector('#movie_player');
            if (mp && !mp.__blockads_bg_hooked) {
                mp.__blockads_bg_hooked = true;
                var origPauseVideo = mp.pauseVideo;
                if (typeof origPauseVideo === 'function') {
                    mp.pauseVideo = function() {
                        if (!isUserAction && !userPaused) {
                            return;
                        }
                        userPaused = true;
                        return origPauseVideo.apply(this, arguments);
                    };
                }
            }
        }
        window.addEventListener('yt-navigate-finish', hookMoviePlayer, { passive: true });
        setInterval(hookMoviePlayer, 2000);

        // 7. Defeat YouTube "Video paused. Continue watching?" (youThereRenderer / inactivity dialogs)
        function preventYouThere() {
            var dialogs = document.querySelectorAll('dialog, ytm-dialog-renderer, ytm-you-there-renderer, #dialog-container dialog');
            for (var k = 0; k < dialogs.length; k++) {
                var d = dialogs[k];
                var txt = (d.textContent || '').toLowerCase();
                if (txt.includes('video paused') || txt.includes('continue watching') ||
                    txt.includes('tạm dừng') || txt.includes('tiếp tục xem') ||
                    d.querySelector('.confirm-dialog-content')) {
                    var btn = d.querySelector('button');
                    if (btn) btn.click();
                    if (typeof d.close === 'function') {
                        try { d.close(); } catch(e) {}
                    }
                    var mp = document.querySelector('#movie_player');
                    if (mp && typeof mp.playVideo === 'function') {
                        try { mp.playVideo(); } catch(e) {}
                    }
                }
            }

            var activePlayer = document.querySelector('#movie_player');
            if (activePlayer && typeof activePlayer.updateLastActiveTime === 'function') {
                try { activePlayer.updateLastActiveTime(); } catch(e) {}
            }
        }
        setInterval(preventYouThere, 3500);

        // Keep global activity timer fresh
        setInterval(function() {
            try {
                window.dispatchEvent(new Event('mousemove'));
            } catch (e) {}
        }, 15000);

        // 8. Keep YouTube player UI in sync with actual media playback state
        function syncPlayerState() {
            var v = document.querySelector('video');
            var mp = document.querySelector('#movie_player');
            if (v && !v.paused && mp && typeof mp.getPlayerState === 'function') {
                var s = mp.getPlayerState();
                if (s === 2 || s === 3) {
                    v.dispatchEvent(new Event('playing'));
                }
            }
        }
        setInterval(syncPlayerState, 2500);

        // 9. MediaSession action handlers for system controls
        if ('mediaSession' in navigator) {
            try {
                navigator.mediaSession.setActionHandler('pause', function() {
                    isUserAction = true;
                    var mp = document.querySelector('#movie_player');
                    if (mp && typeof mp.pauseVideo === 'function') {
                        mp.pauseVideo();
                    } else {
                        var v = document.querySelector('video');
                        if (v) originalPause.call(v);
                    }
                });
                navigator.mediaSession.setActionHandler('play', function() {
                    isUserAction = true;
                    var mp = document.querySelector('#movie_player');
                    if (mp && typeof mp.playVideo === 'function') {
                        mp.playVideo();
                    } else {
                        var v = document.querySelector('video');
                        if (v) v.play().catch(function(){});
                    }
                });
            } catch (e) {}
        }
    } catch (e) {}
})();
