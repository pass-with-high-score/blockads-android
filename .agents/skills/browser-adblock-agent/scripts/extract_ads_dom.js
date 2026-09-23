/**
 * Injected DOM inspection script for ad and anti-adblock detection.
 * Can be executed in Browser Subagent, Chrome Console, or Headless Browser.
 */
(() => {
    const isVisible = (el) => {
        const style = window.getComputedStyle(el);
        return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
    };

    // 1. Check body / html scroll lock
    const bodyStyle = window.getComputedStyle(document.body || {});
    const htmlStyle = window.getComputedStyle(document.documentElement || {});
    const scrollLocked = bodyStyle.overflow === 'hidden' || htmlStyle.overflow === 'hidden';

    // 2. Detect Fixed / Absolute Overlays (Modals, Popups, Anti-adblock screens)
    const allElements = Array.from(document.querySelectorAll('*'));
    const overlays = allElements.filter(el => {
        if (!isVisible(el)) return false;
        const style = window.getComputedStyle(el);
        const isPositioned = style.position === 'fixed' || style.position === 'absolute';
        const zIndex = parseInt(style.zIndex, 10);
        const hasHighZIndex = !isNaN(zIndex) && zIndex >= 999;
        const coversScreen = el.offsetWidth > (window.innerWidth * 0.7) && el.offsetHeight > (window.innerHeight * 0.4);
        return isPositioned && hasHighZIndex && coversScreen;
    }).map(el => ({
        tag: el.tagName.toLowerCase(),
        id: el.id || null,
        className: el.className ? String(el.className).trim() : null,
        zIndex: window.getComputedStyle(el).zIndex,
        rect: { width: el.offsetWidth, height: el.offsetHeight }
    }));

    // 3. Detect known ad patterns / bait elements
    const adKeywords = [
        'ad-container', 'ad-wrapper', 'ad-slot', 'ad_unit', 'adsbox', 'banner_ad',
        'pub_300x250', 'adblock', 'notice-wrap', 'siteNotice', 'vid-sld-cont',
        'popunder', 'inpagepush', 'cathaytrash', 'acscdn'
    ];
    const adSelectors = adKeywords.map(k => `[class*="${k}"], [id*="${k}"]`).join(', ');
    const candidateAdElements = Array.from(document.querySelectorAll(adSelectors))
        .filter(el => el.offsetWidth > 0 && el.offsetHeight > 0)
        .slice(0, 30)
        .map(el => ({
            tag: el.tagName.toLowerCase(),
            id: el.id || null,
            className: el.className ? String(el.className).trim() : null,
            size: `${el.offsetWidth}x${el.offsetHeight}`
        }));

    // 4. Detect external scripts (Ad networks & Anti-Adblock detectors)
    const scriptKeywords = [
        'pagead', 'googlesyndication', 'doubleclick', 'adnxs', 'criteo',
        'adblock', 'ads.js', 'prebid', 'outbrain', 'taboola', 'mgid',
        'aclib', 'chatmate', 'cathaytrash', 'inplayer'
    ];
    const scripts = Array.from(document.querySelectorAll('script[src]'))
        .map(s => s.src)
        .filter(src => scriptKeywords.some(kw => src.toLowerCase().includes(kw)))
        .slice(0, 25);

    // 5. Detect Iframes
    const iframes = Array.from(document.querySelectorAll('iframe'))
        .map(i => ({
            src: i.src || i.getAttribute('data-src') || null,
            id: i.id || null,
            className: i.className ? String(i.className).trim() : null,
            size: `${i.offsetWidth}x${i.offsetHeight}`
        }))
        .filter(i => i.src && scriptKeywords.some(kw => i.src.toLowerCase().includes(kw)))
        .slice(0, 15);

    return {
        url: window.location.href,
        title: document.title,
        scrollLocked,
        bodyOverflow: bodyStyle.overflow,
        htmlOverflow: htmlStyle.overflow,
        overlays,
        candidateAdElements,
        suspiciousScripts: scripts,
        suspiciousIframes: iframes
    };
})();
