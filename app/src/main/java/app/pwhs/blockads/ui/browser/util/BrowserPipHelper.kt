package app.pwhs.blockads.ui.browser.util

/**
 * Helper generating JavaScript code for handling Picture-in-Picture on web videos.
 */
object BrowserPipHelper {
    fun getPipToggleScript(isInPipMode: Boolean): String {
        return if (isInPipMode) {
            """
            (function() {
                if (window.__blockads_set_pip) {
                    window.__blockads_set_pip(true);
                } else {
                    var v = document.querySelector('video');
                    if (v) {
                        var box = document.getElementById('__blockads_pip_box');
                        if (!box) {
                            box = document.createElement('div');
                            box.id = '__blockads_pip_box';
                            box.style.cssText = 'position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;display:flex!important;align-items:center!important;justify-content:center!important;';
                            document.body.appendChild(box);
                        }
                        if (!v._blockadsOrigParent) {
                            v._blockadsOrigParent = v.parentNode;
                            v._blockadsOrigSibling = v.nextSibling;
                        }
                        box.appendChild(v);
                        v.style.cssText = 'width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;display:block!important;';
                        if (v.paused) v.play().catch(function(){});
                    }
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                if (window.__blockads_set_pip) {
                    window.__blockads_set_pip(false);
                } else {
                    var v = document.querySelector('video');
                    var box = document.getElementById('__blockads_pip_box');
                    if (v && v._blockadsOrigParent) {
                        v.style.cssText = '';
                        try { v._blockadsOrigParent.insertBefore(v, v._blockadsOrigSibling); }
                        catch(e) { v._blockadsOrigParent.appendChild(v); }
                        delete v._blockadsOrigParent;
                        delete v._blockadsOrigSibling;
                    }
                    if (box) box.remove();
                }
            })();
            """.trimIndent()
        }
    }
}
