# BlockAds Open Issues Analysis (Updated 2026-04-11)

Total: **13 open** / 15 closed today

---

## CLOSED - Fixed (6)

| # | Title | Fix |
|---|-------|-----|
| ~~#126~~ | Custom Lists Statistics shows 0 | Go engine now attributes all matching filters, not just first (d4727ef) |
| ~~#92~~ | Hide from recent tasks | Added toggle in Settings > Privacy & Diagnostics (40e21d1) |
| ~~#102~~ | Watchdog / auto-restart | BootReceiver handles MY_PACKAGE_REPLACED (0123203) |
| ~~#129~~ | Internal domains in WireGuard mode | Split-DNS routes private zones via WireGuard DNS (e6a3e71) |
| ~~#132~~ | Crowdin/Weblate for translations | Using POEditor, users can request language invites |
| — | WireGuard mode DNS not intercepted | Used fake local DNS to force port 53 instead of DoT port 853 (3fb9b16) |

## CLOSED - Already Implemented (5)

| # | Title | Status |
|---|-------|--------|
| ~~#120~~ | Quick Settings toggle | AdBlockTileService already registered and functional |
| ~~#128~~ | Automation via intent | TaskerReceiver supports TASKER_START / TASKER_STOP |
| ~~#69~~ | Add Delete button for filters | Delete button exists in filter list and detail pages |
| ~~#105~~ | Root Mode without VPN | Duplicate of #123, Root Proxy mode already exists |
| ~~#118~~ | Intercept hardcoded port 53 | Already handled by VPN TUN + Root Proxy iptables |

## CLOSED - Not a Bug / Resolved (4)

| # | Title | Reason |
|---|-------|--------|
| ~~#125~~ | Unable to add custom filters | Server issue, confirmed fixed |
| ~~#124~~ | Whitelist domain needs reboot | Not a code bug — Android/browser DNS cache |
| ~~#106~~ | Brave Browser broken | Caused by beta HTTPS filtering, disabling fixes it |
| ~~#100~~ | CA Certificate for HTTPS | Already implemented as HTTPS filtering (beta) |

---

## OPEN - Needs Investigation (2)

| # | Title | Status |
|---|-------|--------|
| **#130** | Root Proxy mode doesn't work | No code bug found. Likely Android 16 + KernelSU compatibility. Need user logs. |
| **#63** | Issue with NextDNS | Active discussion (24 comments). Log discrepancy is expected — local blocks don't reach NextDNS. |

## OPEN - Feature Gaps (1)

| # | Title | Notes |
|---|-------|-------|
| **#56** | Whitelist filter lists improperly load | App only supports block lists. Need whitelist filter list format support. Backend doesn't support yet. |

## OPEN - Feature Requests: Medium Effort (4)

| # | Title | Notes |
|---|-------|-------|
| **#91** | WireGuard blocks local servers | Exclude LAN/private IP ranges from WireGuard tunnel. |
| **#104** | Accessing by IP address blocked | Bypass domain filtering for raw IP addresses. |
| **#123** | Root Proxy conflicts with other VPNs | Root proxy should work independently of VPN slot. |
| **#107** | Per-app domain blocking/unblocking | Extend FirewallManager for per-app domain rules. |

## OPEN - Feature Requests: Large Effort (6)

| # | Title | Notes |
|---|-------|-------|
| **#133** | DNS over HTTP/3 and QUIC | Requires QUIC library integration. |
| **#119** | Shizuku/ADB connection mode | New connection mode, significant development. |
| **#89** | Bypass DPI / spoof SNI RST | Out of scope for DNS-based blocker. |
| **#74** | Block internet by default for new apps | Default-deny firewall for new installs. |
| **#73** | Queries tab, App tab, Stats, Profiles | Major UI restructuring. |
| **#111** | Filter building locally and VPS | Novel architecture change. |
| **#95** | Multiple requests | Umbrella issue, mixed complexity. |

---

## Summary

| Category | Count |
|----------|-------|
| Closed — Fixed | 6 |
| Closed — Already Implemented | 5 |
| Closed — Not a Bug | 4 |
| Open — Needs Investigation | 2 |
| Open — Feature Gaps | 1 |
| Open — FR Medium | 4 |
| Open — FR Large | 6 |
| **Total** | **28** |
