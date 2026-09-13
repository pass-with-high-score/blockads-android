#!/usr/bin/env node
/**
 * Inspect active WebView tab in BlockAds Android app.
 * Run: node .agent/skills/adblock-fixer/scripts/inspect_page.mjs
 */
(async () => {
    try {
        const res = await fetch("http://127.0.0.1:9222/json");
        const tabs = await res.json();
        if (!tabs || tabs.length === 0) {
            console.error("No active DevTools tab found on port 9222.");
            process.exit(1);
        }
        const tab = tabs[0];
        console.log(`Inspecting tab: [${tab.title}] (${tab.url})`);

        const ws = new WebSocket(tab.webSocketDebuggerUrl);
        ws.onopen = () => {
            const expr = `(() => {
                const scripts = Array.from(document.querySelectorAll("script")).map(s => s.src || s.textContent.slice(0, 100)).filter(Boolean);
                const iframes = Array.from(document.querySelectorAll("iframe")).map(i => i.src || i.getAttribute("srcdoc")?.slice(0, 80));
                const potentialOverlays = Array.from(document.querySelectorAll("*")).filter(el => {
                    const style = window.getComputedStyle(el);
                    return (style.position === 'fixed' || style.position === 'absolute') && parseInt(style.zIndex, 10) > 1000 && el.offsetHeight > 200;
                }).map(el => ({ tag: el.tagName, id: el.id, class: el.className, zIndex: window.getComputedStyle(el).zIndex }));
                return {
                    title: document.title,
                    bodyOverflow: document.body.style.overflow,
                    htmlOverflow: document.documentElement.style.overflow,
                    potentialOverlays,
                    iframesCount: iframes.length,
                    iframes,
                    scriptsCount: scripts.length
                };
            })()`;
            ws.send(JSON.stringify({
                id: 1,
                method: "Runtime.evaluate",
                params: { expression: expr, returnByValue: true }
            }));
        };
        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            console.log("Analysis Result:\n", JSON.stringify(data.result?.result?.value, null, 2));
            ws.close();
            process.exit(0);
        };
    } catch (e) {
        console.error("Error inspecting page:", e.message);
        process.exit(1);
    }
})();
