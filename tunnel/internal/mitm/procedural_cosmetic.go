package mitm

import (
	"encoding/json"
	"strings"
)

// proceduralOperators lists uBlock Origin / AdGuard procedural cosmetic operators
// that browsers cannot evaluate natively inside a CSS stylesheet.
var proceduralOperators = []string{
	":has-text(",
	":-abp-contains(",
	":upward(",
	":remove()",
	":remove-attr(",
	":remove-class(",
	":matches-path(",
	":matches-css(",
	":matches-attr(",
	":xpath(",
	":min-text-length(",
}

// isProceduralSelector returns true if the selector contains any procedural
// cosmetic pseudo-operator that must be executed in JS instead of plain CSS.
func isProceduralSelector(selector string) bool {
	lower := strings.ToLower(selector)
	for _, op := range proceduralOperators {
		if strings.Contains(lower, op) {
			return true
		}
	}
	return false
}

// formatCosmeticSelector converts a raw cosmetic selector (from .css or .modules.json)
// into either:
//   - a valid CSS rule (for standard selectors or uBlock :style(...) rules), OR
//   - a procedural rule string to be executed by the DOM procedural runtime.
//
// Supported forms:
//   - `.ad-banner` -> css: `.ad-banner { display: none !important; }`
//   - `body:style(overflow: auto !important;)` -> css: `body { overflow: auto !important; }`
//   - `div.card:has-text(Sponsored):upward(2)` -> procedural: `div.card:has-text(Sponsored):upward(2)`
//   - `.popup:remove()` -> procedural: `.popup:remove()`
//   - `div.item:has-text(Ad):style(opacity: 0 !important)` -> procedural (mixed procedural + :style)
func classifyCosmeticSelector(raw string) (cssRule string, proceduralRule string) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", ""
	}

	// Check for procedural operators first (including mixed e.g. :has-text(...):style(...))
	if isProceduralSelector(s) {
		return "", s
	}

	// Already a full CSS rule block (e.g. from pre-compiled .css or module css)
	if strings.Contains(s, "{") {
		return s, ""
	}

	// uBlock Origin :style(...) extended CSS syntax
	if baseSel, styleBody, ok := splitStyleAction(s); ok {
		if baseSel == "" || styleBody == "" {
			return "", ""
		}
		return baseSel + " { " + styleBody + " }", ""
	}

	// Standard element-hiding selector
	return s + " { display: none !important; }", ""
}

// splitStyleAction splits a trailing `:style(...)` action from a selector,
// respecting nested parentheses inside the style body (e.g. `rgba(0,0,0,0)` or `url(...)`).
func splitStyleAction(selector string) (baseSelector string, styleBody string, ok bool) {
	lower := strings.ToLower(selector)
	idx := strings.LastIndex(lower, ":style(")
	if idx < 0 {
		return "", "", false
	}

	// Verify balanced parentheses from `:style(` to the end of the selector
	start := idx + len(":style(")
	depth := 1
	end := -1
	for i := start; i < len(selector); i++ {
		switch selector[i] {
		case '(':
			depth++
		case ')':
			depth--
			if depth == 0 {
				end = i
			}
		}
		if end != -1 {
			break
		}
	}
	if end == -1 || strings.TrimSpace(selector[end+1:]) != "" {
		return "", "", false
	}

	base := strings.TrimSpace(selector[:idx])
	body := strings.TrimSpace(selector[start:end])
	return base, body, base != "" && body != ""
}

// buildProceduralCosmeticJS generates a self-contained, debounced JS runtime
// that evaluates uBlock procedural cosmetic rules (`:has-text()`, `:upward()`,
// `:remove()`, and trailing `:style()`) on the page and via MutationObserver.
func buildProceduralCosmeticJS(rules []string) string {
	if len(rules) == 0 {
		return ""
	}
	encoded, err := json.Marshal(rules)
	if err != nil {
		return ""
	}
	var b strings.Builder
	b.WriteString("\n// [BlockAds] Procedural cosmetic runtime (")
	b.WriteString(")\n(function(rules){\n")
	b.WriteString(proceduralCosmeticRuntimeBody)
	b.WriteString("\n})(")
	b.Write(encoded)
	b.WriteString(");\n")
	return b.String()
}

// proceduralCosmeticRuntimeBody is an ES5-compatible DOM runner supporting:
//   - :has-text(text) and :has-text(/regex/flags)
//   - :upward(N) and :upward(selector)
//   - :remove()
//   - :style(css-declarations)
//   - default hide (display: none !important) when neither :remove() nor :style() is present
const proceduralCosmeticRuntimeBody = `
if (!rules || !rules.length) return;
function parseRule(raw) {
  var ops = [];
  var rest = raw.trim();
  var action = { type: 'hide', value: '' };
  var m;
  // Extract trailing :remove()
  if (/:remove\(\)\s*$/i.test(rest)) {
    action = { type: 'remove', value: '' };
    rest = rest.replace(/:remove\(\)\s*$/i, '').trim();
  } else {
    var sIdx = rest.toLowerCase().lastIndexOf(':style(');
    if (sIdx > -1 && rest.charAt(rest.length - 1) === ')') {
      action = { type: 'style', value: rest.slice(sIdx + 7, -1).trim() };
      rest = rest.slice(0, sIdx).trim();
    }
  }
  // Tokenize base selector and chained procedural operators
  var opRe = /:(has-text|-abp-contains|upward)\(/ig;
  var firstMatch = opRe.exec(rest);
  var baseSelector = firstMatch ? rest.slice(0, firstMatch.index).trim() : rest;
  if (!baseSelector) baseSelector = '*';
  var pos = firstMatch ? firstMatch.index : rest.length;
  while (pos < rest.length) {
    opRe.lastIndex = pos;
    m = opRe.exec(rest);
    if (!m || m.index !== pos) break;
    var opName = m[1].toLowerCase();
    var argStart = opRe.lastIndex;
    var depth = 1, i = argStart;
    while (i < rest.length && depth > 0) {
      var ch = rest.charAt(i);
      if (ch === '\\') { i += 2; continue; }
      if (ch === '(') depth++;
      else if (ch === ')') depth--;
      i++;
    }
    var arg = rest.slice(argStart, i - 1).trim();
    ops.push({ name: opName, arg: arg });
    pos = i;
  }
  return { base: baseSelector, ops: ops, action: action };
}
function matchText(el, arg) {
  var text = el.textContent || '';
  if (arg.length >= 2 && arg.charAt(0) === '/') {
    var lastSlash = arg.lastIndexOf('/');
    if (lastSlash > 0) {
      try {
        var re = new RegExp(arg.slice(1, lastSlash), arg.slice(lastSlash + 1));
        return re.test(text);
      } catch (e) {}
    }
  }
  return text.indexOf(arg) !== -1;
}
function applyUpward(el, arg) {
  if (/^\d+$/.test(arg)) {
    var n = parseInt(arg, 10);
    var cur = el;
    while (n > 0 && cur && cur.parentElement) {
      cur = cur.parentElement;
      n--;
    }
    return n === 0 ? cur : null;
  }
  try {
    return el.parentElement ? el.parentElement.closest(arg) : null;
  } catch (e) {
    return null;
  }
}
function applyAction(el, action) {
  if (!el || el.nodeType !== 1) return;
  if (action.type === 'remove') {
    if (el.parentNode) el.parentNode.removeChild(el);
  } else if (action.type === 'style') {
    var decls = action.value.split(';');
    for (var i = 0; i < decls.length; i++) {
      var part = decls[i].trim();
      if (!part) continue;
      var colon = part.indexOf(':');
      if (colon <= 0) continue;
      var prop = part.slice(0, colon).trim();
      var val = part.slice(colon + 1).trim();
      var imp = '';
      if (/!important\s*$/i.test(val)) {
        imp = 'important';
        val = val.replace(/!important\s*$/i, '').trim();
      }
      try { el.style.setProperty(prop, val, imp); } catch (e) {}
    }
  } else {
    try { el.style.setProperty('display', 'none', 'important'); } catch (e) {}
  }
}
var parsed = [];
for (var r = 0; r < rules.length; r++) {
  try { parsed.push(parseRule(rules[r])); } catch (e) {}
}
function runAll() {
  for (var i = 0; i < parsed.length; i++) {
    var rule = parsed[i];
    var nodes;
    try { nodes = document.querySelectorAll(rule.base); } catch (e) { continue; }
    for (var j = 0; j < nodes.length; j++) {
      var el = nodes[j];
      for (var k = 0; k < rule.ops.length && el; k++) {
        var op = rule.ops[k];
        if (op.name === 'has-text' || op.name === '-abp-contains') {
          if (!matchText(el, op.arg)) el = null;
        } else if (op.name === 'upward') {
          el = applyUpward(el, op.arg);
        }
      }
      if (el) applyAction(el, rule.action);
    }
  }
}
var timer = null;
function schedule() {
  if (timer) return;
  timer = setTimeout(function() { timer = null; runAll(); }, 120);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', runAll, { once: true });
} else {
  runAll();
}
try {
  var mo = new MutationObserver(schedule);
  mo.observe(document.documentElement || document, { childList: true, subtree: true });
} catch (e) {}
`
