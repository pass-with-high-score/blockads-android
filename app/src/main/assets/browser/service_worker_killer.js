/**
 * BlockAds - Service Worker Neutralizer (AdGuard technique)
 * Unregisters any active Service Workers and disables future registration
 * to ensure all subresource fetches are intercepted by WebViewClient.
 */
(function() {
    'use strict';
    if (window.__blockads_sw_killer_injected) return;
    window.__blockads_sw_killer_injected = true;

    try {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.getRegistrations().then(function(registrations) {
                for (var i = 0; i < registrations.length; i++) {
                    registrations[i].unregister();
                }
            }).catch(function() {});

            // Prevent future service worker registration
            navigator.serviceWorker.register = function() {
                return Promise.reject(new Error('ServiceWorker registration blocked by BlockAds'));
            };

            // Remove serviceWorker prototype if possible
            try {
                delete navigator.__proto__.serviceWorker;
            } catch(e) {}
        }
    } catch (e) {}
})();
