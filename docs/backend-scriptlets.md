# Backend pipeline — `scriptlets.txt`

How the filter-list build server produces the JS scriptlet artifact
that the BlockAds engine consumes.

## What scriptlets are

JS snippets injected into pages to neutralise ads at the source —
preventing ad SDKs from loading, blocking tracking fetch calls,
overriding properties before the page reads them. Significantly
stronger than CSS hiding, especially against anti-adblock and SPA
sites.

EasyList syntax in filter lists looks like:

```
example.com##+js(set-constant, ad.enabled, false)
youtube.com#%#//scriptlet('abort-on-property-read', 'ytInitialData.adPlacements')
```

The engine ships with a runtime that implements ~6 common scriptlets
(`set-constant`, `abort-on-property-read`, `abort-on-property-write`,
`prevent-fetch`, `prevent-xhr`, `noeval`). Rules invoking unknown
scriptlets are silently dropped at runtime.

## Pipeline contract

The backend takes the same filter-list URLs already used to build
`.trie` / `.bloom` / `.css` and emits one additional artifact:

```
scriptlets.txt  — raw filter-list lines that contain a scriptlet rule
```

No transformation is required: the engine parses the raw EasyList
syntax directly. Comments, headers, and non-scriptlet rules in the
file are ignored on parse.

## Build command

```bash
# 1. Pull recommended filter lists (these have the most scriptlet
#    coverage; add your own as needed).
curl -sSL https://filters.adtidy.org/extension/chromium/filters/2.txt  -o /tmp/adguard-base.txt
curl -sSL https://filters.adtidy.org/extension/chromium/filters/11.txt -o /tmp/adguard-mobile.txt
curl -sSL https://filters.adtidy.org/extension/chromium/filters/14.txt -o /tmp/adguard-annoyances.txt

# 2. Filter to scriptlet rules only — both AdGuard and uBlock syntax.
cat /tmp/adguard-*.txt \
  | grep -E '#%#//scriptlet\(|##\+js\(' \
  | sort -u \
  > dist/scriptlets.txt

# 3. Stats sanity check
wc -l dist/scriptlets.txt   # expect ~9,000–10,000
```

Drop `dist/scriptlets.txt` next to `dist/{filter}.trie`,
`dist/{filter}.bloom`, `dist/cosmetic.css`. The app downloads it the
same way as the other artifacts.

## Filter list reference

| Filter | URL | Scriptlet count* |
|---|---|---|
| AdGuard Base | https://filters.adtidy.org/extension/chromium/filters/2.txt | ~4,800 |
| AdGuard Mobile Ads | https://filters.adtidy.org/extension/chromium/filters/11.txt | ~120 |
| AdGuard Annoyances | https://filters.adtidy.org/extension/chromium/filters/14.txt | ~4,900 |
| EasyList | https://easylist.to/easylist/easylist.txt | varies |
| Fanboy's Annoyance | https://easylist.to/easylist/fanboy-annoyance.txt | varies |

*counts measured 2026-04-25; AdGuard updates daily.

## Syntax recognised by the engine

Both forms are accepted; rules can mix freely in the file.

### AdGuard native (most filter lists from filters.adtidy.org)

```
domain1.com,domain2.com#%#//scriptlet('name', 'arg1', 'arg2')
```

- Single or double quotes around args
- Args are positional
- Domains comma-separated
- Negation: `~excluded.com` excludes the rule from that host

### uBlock Origin (EasyList exports, some Hagezi)

```
domain1.com,domain2.com##+js(name, arg1, arg2)
```

- Args unquoted (or quoted)
- Same domain semantics

### Variants explicitly NOT supported

- `#@#+js(...)` (uBlock exception) — silently ignored
- `#@%#//scriptlet(...)` (AdGuard exception) — silently ignored
- `##+js(scriptlet:something)` (uBlock alias namespace) — ignored

## Engine API contract

The engine consumes `scriptlets.txt` via a single Kotlin call:

```kotlin
val raw = File(filterRepo.getScriptletsPath()).readText()
engine.setScriptletRules(raw)
```

Behaviour:

- Idempotent. Calling again replaces the in-memory store.
- Empty string clears the store.
- Parse cost: ~10 ms for 10k rules.
- Memory: ~2 MB resident for 10k rules.
- Lines that don't match either dialect (or are comments) are skipped
  silently — feeding the entire filter list verbatim is safe.

The engine does NOT fetch `scriptlets.txt` from anywhere. The Kotlin
side is responsible for downloading, caching, and refreshing the
file; the engine only consumes the in-memory string.

## How the runtime side works (informational)

Once the rules are loaded, the engine serves three things from the
in-app local asset host (`local.pwhs.app`):

1. `/cosmetic.css` — element-hiding rules
2. `/scriptlets.js` — the runtime that defines `window.__ba.invoke()`
3. `/sl-<hostname>.js` — generated per request from the rules that
   match `<hostname>`; calls `window.__ba.invoke()` with the rule's
   scriptlet name and args

Both scripts are injected via a small loader that the engine splices
into every MITM'd HTML response right after `<head>`. The browser
caches them — the runtime is fetched once per origin, the per-host
script once per host.

## Testing the pipeline output

End-to-end check that the file you produced is parseable:

```bash
# In the blockads-android repo, run a quick parse test:
go run ./scripts/test_scriptlets.go path/to/scriptlets.txt
# Expected output:
#   parsed N rules (M global, K host-bound)
```

If the script doesn't exist yet, write a one-liner that calls
`tunnel.ParseScriptletRules` (export the package symbol if needed).

Symptoms of a malformed `scriptlets.txt`:

- Engine logs `Scriptlet rules: parsed 0 rules` despite a non-empty
  file → the lines aren't matching the regex; check the syntax
- Engine parses fewer than expected → quote escaping or stray spaces
  inside arg lists; inspect a sample with `head` and verify the
  format matches the table above
- App crashes on `setScriptletRules` → file out of UTF-8 or unbounded
  size; we accept up to ~5 MB safely

## Update cadence

AdGuard filters update every 24 h. Recommended:

- Backend rebuild `scriptlets.txt` daily at off-peak time
- App refresh on the same schedule as `.trie` / `.bloom` / `.css`
- No need for a separate update mechanism — keep the file in lockstep
  with the existing filter-list pipeline

## Future-proofing

The Go runtime today implements 6 scriptlets. EasyList rules invoking
others (e.g., `prevent-setTimeout`, `remove-attr`) are stored but the
runtime logs `unknown scriptlet` and does nothing. To add support:

1. Append the implementation to `scriptletRuntimeJS` in
   `tunnel/scriptlet_runtime.go` under the `ns[...] = function(...)`
   pattern
2. Bump the `version` field in the runtime so cached pages re-fetch
3. No backend change needed — the rules already pass through

If/when the Go-embedded runtime grows past ~50 KB, switch to
`//go:embed tunnel/assets/scriptlets.js` and ship the JS as a separate
file alongside the package.
