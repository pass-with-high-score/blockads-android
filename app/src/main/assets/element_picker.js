/**
 * BlockAds Element Picker
 * Robust capture-phase event interception prevents link navigation and form submissions.
 * Visual highlight with selector display and floating bottom toolbar with auto-flip when selected element is near bottom.
 * Zero top overlays to ensure all header and top items can be touched and blocked freely.
 */
(function () {
  'use strict';

  if (window.__blockadsPickerActive__) return;
  window.__blockadsPickerActive__ = true;

  /* ── State ─────────────────────────────────────────────────────────────── */
  var state = {
    selected: null,
    history: [],
  };

  var highlight = null;
  var toolbar = null;
  var selectorLabel = null;
  var hintText = null;

  /* ── Helpers ────────────────────────────────────────────────────────────── */
  function isPickerOwned(el) {
    if (!el) return false;
    if (highlight && highlight.contains(el)) return true;
    if (toolbar && toolbar.contains(el)) return true;
    if (typeof el.closest === 'function') {
      return !!(el.closest('#__blockads_toolbar__') || el.closest('#__blockads_highlight__'));
    }
    return false;
  }

  function getRoot() {
    return document.body || document.documentElement;
  }

  /** Generate stable, concise CSS selector */
  function generateSelector(el) {
    if (!el || el === document.body || el === document.documentElement) return '';
    if (el.id && /^[a-zA-Z][\w\-]*$/.test(el.id)) {
      return '#' + el.id;
    }

    var tag = el.tagName.toLowerCase();
    var validClasses = [];
    for (var i = 0; i < el.classList.length; i++) {
      var c = el.classList[i];
      if (c && /^[a-zA-Z][\w\-]*$/.test(c) && !c.startsWith('__blockads')) {
        validClasses.push('.' + c);
      }
    }

    if (validClasses.length > 0 && validClasses.length <= 3) {
      var classSel = tag + validClasses.join('');
      try {
        if (document.querySelectorAll(classSel).length <= 6) return classSel;
      } catch (e) {}
    }

    var parent = el.parentElement;
    if (parent && parent !== document.body && parent !== document.documentElement) {
      if (parent.id && /^[a-zA-Z][\w\-]*$/.test(parent.id)) {
        return '#' + parent.id + ' > ' + tag;
      }
      var idx = Array.prototype.indexOf.call(parent.children, el) + 1;
      return tag + ':nth-child(' + idx + ')';
    }

    return tag;
  }

  /* ── Visual Highlight ───────────────────────────────────────────────────── */
  function ensureHighlight() {
    if (highlight) return;
    highlight = document.createElement('div');
    highlight.id = '__blockads_highlight__';
    Object.assign(highlight.style, {
      position: 'fixed',
      zIndex: '2147483646',
      pointerEvents: 'none',
      border: '2px solid #6366F1',
      background: 'rgba(99, 102, 241, 0.22)',
      borderRadius: '4px',
      boxSizing: 'border-box',
      display: 'none',
      boxShadow: '0 0 0 1px rgba(255,255,255,0.4), inset 0 0 12px rgba(99,102,241,0.3)',
      transition: 'top 0.12s ease, left 0.12s ease, width 0.12s ease, height 0.12s ease',
    });
    getRoot().appendChild(highlight);
  }

  function updateHighlight(el) {
    if (!el) {
      if (highlight) highlight.style.display = 'none';
      return;
    }
    ensureHighlight();
    var r = el.getBoundingClientRect();
    Object.assign(highlight.style, {
      display: 'block',
      top: r.top + 'px',
      left: r.left + 'px',
      width: r.width + 'px',
      height: r.height + 'px',
    });
  }

  /* ── Floating Toolbar ───────────────────────────────────────────────────── */
  function ensureToolbar() {
    if (toolbar) return;
    toolbar = document.createElement('div');
    toolbar.id = '__blockads_toolbar__';
    Object.assign(toolbar.style, {
      position: 'fixed',
      zIndex: '2147483647',
      bottom: '72px',
      left: '50%',
      transform: 'translateX(-50%)',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: '6px',
      background: 'rgba(18, 18, 28, 0.95)',
      backdropFilter: 'blur(16px)',
      webkitBackdropFilter: 'blur(16px)',
      border: '1px solid rgba(255, 255, 255, 0.16)',
      borderRadius: '24px',
      padding: '8px 14px',
      boxShadow: '0 12px 40px rgba(0, 0, 0, 0.75)',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      boxSizing: 'border-box',
      maxWidth: '94vw',
      transition: 'bottom 0.15s ease, top 0.15s ease',
    });

    // Selector label
    selectorLabel = document.createElement('div');
    Object.assign(selectorLabel.style, {
      color: '#A5B4FC',
      fontFamily: 'monospace',
      fontSize: '11px',
      fontWeight: '600',
      maxWidth: '280px',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap',
      padding: '2px 8px',
      background: 'rgba(99, 102, 241, 0.16)',
      borderRadius: '8px',
      display: 'none',
    });
    toolbar.appendChild(selectorLabel);

    // Buttons container
    var btnContainer = document.createElement('div');
    Object.assign(btnContainer.style, {
      display: 'flex',
      gap: '6px',
      alignItems: 'center',
    });

    // Initial hint text (before element is selected)
    hintText = document.createElement('div');
    hintText.textContent = '👆 Chạm vào phần tử muốn chặn';
    Object.assign(hintText.style, {
      color: '#E0E7FF',
      fontSize: '13px',
      fontWeight: '500',
      padding: '4px 6px',
      whiteSpace: 'nowrap',
    });
    btnContainer.appendChild(hintText);

    function makeBtn(id, text, bgColor, textColor, onClick) {
      var b = document.createElement('button');
      b.id = id;
      b.textContent = text;
      Object.assign(b.style, {
        padding: '8px 13px',
        borderRadius: '16px',
        border: 'none',
        cursor: 'pointer',
        fontWeight: '600',
        fontSize: '12px',
        background: bgColor,
        color: textColor,
        whiteSpace: 'nowrap',
        boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
        outline: 'none',
        webkitTapHighlightColor: 'transparent',
        touchAction: 'manipulation',
      });
      function trigger(e) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        onClick();
      }
      b.addEventListener('click', trigger);
      b.addEventListener('touchend', trigger);
      return b;
    }

    var btnCancel = makeBtn('__btn_cancel__', '✕ Hủy', '#2D3139', '#E5E7EB', cancel);
    var btnParent = makeBtn('__btn_parent__', '↑ Rộng hơn', '#374151', '#F3F4F6', goParent);
    var btnChild  = makeBtn('__btn_child__',  '↓ Hẹp hơn',  '#374151', '#F3F4F6', goChild);
    var btnBlock  = makeBtn('__btn_block__',  '🚫 Chặn',    '#6366F1', '#FFFFFF', confirmBlock);

    btnParent.style.display = 'none';
    btnChild.style.display  = 'none';
    btnBlock.style.display  = 'none';

    btnContainer.appendChild(btnCancel);
    btnContainer.appendChild(btnParent);
    btnContainer.appendChild(btnChild);
    btnContainer.appendChild(btnBlock);
    toolbar.appendChild(btnContainer);

    getRoot().appendChild(toolbar);
  }

  function updateToolbarState() {
    ensureToolbar();
    var p = document.getElementById('__btn_parent__');
    var c = document.getElementById('__btn_child__');
    var b = document.getElementById('__btn_block__');

    if (state.selected) {
      if (hintText) hintText.style.display = 'none';
      if (p) p.style.display = 'inline-block';
      if (c) c.style.display = 'inline-block';
      if (b) b.style.display = 'inline-block';
      if (selectorLabel) {
        selectorLabel.style.display = 'block';
        selectorLabel.textContent = generateSelector(state.selected) || state.selected.tagName.toLowerCase();
      }

      // Reposition toolbar if selected element is in the bottom area to avoid obstruction
      var r = state.selected.getBoundingClientRect();
      if (r.bottom > window.innerHeight - 150) {
        toolbar.style.top = '24px';
        toolbar.style.bottom = 'auto';
      } else {
        toolbar.style.bottom = '72px';
        toolbar.style.top = 'auto';
      }
    } else {
      if (hintText) hintText.style.display = 'block';
      if (p) p.style.display = 'none';
      if (c) c.style.display = 'none';
      if (b) b.style.display = 'none';
      if (selectorLabel) selectorLabel.style.display = 'none';
      toolbar.style.bottom = '72px';
      toolbar.style.top = 'auto';
    }
  }

  /* ── Selection Logic ────────────────────────────────────────────────────── */
  function selectElement(el) {
    if (!el || el === document.body || el === document.documentElement) return;
    state.history = [];
    state.selected = el;
    updateHighlight(el);
    updateToolbarState();
  }

  function goParent() {
    if (!state.selected) return;
    var parent = state.selected.parentElement;
    if (!parent || parent === document.body || parent === document.documentElement) return;
    state.history.push(state.selected);
    state.selected = parent;
    updateHighlight(parent);
    updateToolbarState();
  }

  function goChild() {
    if (!state.selected) return;
    var prev = state.history.pop();
    if (prev) {
      state.selected = prev;
      updateHighlight(prev);
      updateToolbarState();
    }
  }

  /* ── Event Interception (Capture Phase) ─────────────────────────────────── */
  function trapAndSelect(e) {
    if (isPickerOwned(e.target)) return;

    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();

    var target = e.target;
    if (target && target !== document.body && target !== document.documentElement) {
      selectElement(target);
    }
  }

  function suppressEvent(e) {
    if (isPickerOwned(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
  }

  // Register all pointer/touch/mouse events on CAPTURE PHASE
  var eventOptions = { capture: true, passive: false };

  window.addEventListener('click', trapAndSelect, eventOptions);
  window.addEventListener('mousedown', suppressEvent, eventOptions);
  window.addEventListener('mouseup', suppressEvent, eventOptions);
  window.addEventListener('touchstart', suppressEvent, eventOptions);
  window.addEventListener('touchend', trapAndSelect, eventOptions);
  window.addEventListener('pointerdown', suppressEvent, eventOptions);
  window.addEventListener('pointerup', trapAndSelect, eventOptions);

  /* ── Confirm / Cancel ───────────────────────────────────────────────────── */
  function confirmBlock() {
    if (!state.selected) return;
    var selector = generateSelector(state.selected);
    var target = state.selected;
    var domain = window.location.hostname || '';

    // Instant local visual feedback
    try {
      target.style.transition = 'opacity 0.25s ease, transform 0.25s ease';
      target.style.opacity = '0';
      target.style.transform = 'scale(0.95)';
      setTimeout(function () {
        try { target.style.setProperty('display', 'none', 'important'); } catch (e) {}
      }, 260);
    } catch (e) {}

    cleanup();

    try {
      if (window.blockadsPickerProxy && window.blockadsPickerProxy.onPickerCompleted) {
        window.blockadsPickerProxy.onPickerCompleted(selector, domain);
      }
    } catch (e) {
      console.error('[BlockAds] onPickerCompleted error', e);
    }
  }

  function cancel() {
    cleanup();
    try {
      if (window.blockadsPickerProxy && window.blockadsPickerProxy.onPickerDismissed) {
        window.blockadsPickerProxy.onPickerDismissed();
      }
    } catch (e) {}
  }

  /* ── Cleanup ─────────────────────────────────────────────────────────────── */
  function cleanup() {
    window.__blockadsPickerActive__ = false;

    window.removeEventListener('click', trapAndSelect, eventOptions);
    window.removeEventListener('mousedown', suppressEvent, eventOptions);
    window.removeEventListener('mouseup', suppressEvent, eventOptions);
    window.removeEventListener('touchstart', suppressEvent, eventOptions);
    window.removeEventListener('touchend', trapAndSelect, eventOptions);
    window.removeEventListener('pointerdown', suppressEvent, eventOptions);
    window.removeEventListener('pointerup', trapAndSelect, eventOptions);

    if (highlight && highlight.parentNode) highlight.parentNode.removeChild(highlight);
    if (toolbar && toolbar.parentNode) toolbar.parentNode.removeChild(toolbar);
    highlight = null;
    toolbar = null;
    selectorLabel = null;
    hintText = null;
    state.selected = null;
    state.history = [];
  }

  // Handle scroll & resize to update highlight position
  window.addEventListener('scroll', function () {
    if (state.selected) updateHighlight(state.selected);
  }, { passive: true });
  window.addEventListener('resize', function () {
    if (state.selected) updateHighlight(state.selected);
  });

  // Initialize toolbar at the bottom immediately upon injection
  ensureToolbar();
  updateToolbarState();

  window.__blockadsPickerCancel__ = cancel;
})();
