package tunnel

import (
	"fmt"
	"sync"
)

// ─────────────────────────────────────────────────────────────────────────────
// conn_log.go — full-tunnel per-app attribution + connection logging.
//
// In full-tunnel mode the stack sees every flow's 5-tuple and (via the UID
// resolver) the owning UID. This lets us:
//   • attribute DNS queries to the real app (the legacy ServeDNS path only
//     knew "RootProxy" in VPN mode), and
//   • surface actual connections (TCP/UDP) — the only way to see apps like
//     Telegram / WhatsApp that connect to hard-coded IPs and barely use DNS,
//     so nothing shows in a DNS-only log.
//
// UID→package uses AppUidResolver (int arg only) — NOT AppResolver, whose
// []byte args panic under Go's cgocheck when called from this concurrent
// hot path.
// ─────────────────────────────────────────────────────────────────────────────

// appNameForFlow resolves the package name of the app owning a flow, or ""
// if it can't be determined. Cheap-ish: one UID lookup + one UID→package
// lookup (both int-only JNI calls).
func (e *Engine) appNameForFlow(flow flowID, protocol int) string {
	uidr := e.uidResolver
	r := e.appUidResolver
	if uidr == nil || r == nil {
		return ""
	}
	uid := resolveFlowUID(uidr, protocol, flow)
	if uid == UIDUnknown {
		return ""
	}
	return packageForUidCached(r, uid)
}

var pkgLookupMu sync.Mutex

// packageForUidCached resolves a UID to its package name through
// [uidPackageCache], so repeated flows from the same app don't each pay a
// getPackagesForUid binder call.
//
// Only successful lookups are cached. The Kotlin resolver returns "" both for
// "no package owns this UID" and for a thrown PackageManager call, so caching
// the empty result would pin a UID to "unknown" for the whole session over a
// single transient failure.
func packageForUidCached(r AppUidResolver, uid int) string {
	if cached, ok := uidPackageCache.Load(uid); ok {
		return cached.(string)
	}
	pkgLookupMu.Lock()
	defer pkgLookupMu.Unlock()
	// Double check after lock
	if cached, ok := uidPackageCache.Load(uid); ok {
		return cached.(string)
	}
	pkg := r.PackageForUid(uid)
	if pkg != "" {
		uidPackageCache.Store(uid, pkg)
	}
	return pkg
}

// connLogSeen dedups connection-log entries by app+dest tuple so a page
// opening many flows to the same server doesn't flood the log. The app stays
// in the key: two apps talking to the same CDN IP are two things the user
// wants to see. Cleared on engine stop (a fresh Engine per VPN session).
var connLogSeen sync.Map // key string -> struct{}

// uidPackageCache memoizes uid→package so a burst of flows from the same app
// (a page load opens dozens) costs one getPackagesForUid binder call instead
// of one per flow. UIDs are stable for an app's install lifetime, and the
// cache dies with the engine, so staleness isn't a concern.
var uidPackageCache sync.Map // int -> string

// logConnection reports a connection to the DNS-log callback so it shows in
// the app's log screen (marked blockedBy="connection"). Deduped per
// app+destIP+destPort. protocol is ProtocolTCP/ProtocolUDP.
//
// Called on the flow's handler goroutine BEFORE the upstream dial, so
// anything slow here is added latency on connection setup — and identifying
// the owning app is not cheap: resolveFlowUID is a getConnectionOwnerUid
// binder round trip that can't be cached (it's keyed on the 5-tuple). So only
// the preference gate stays inline; everything else is handed to a goroutine
// and never delays the dial. With logging off, a flow costs one atomic load.
func (e *Engine) logConnection(flow flowID, protocol int) {
	// Cheapest possible gate: the user isn't recording logs, so identifying
	// who owns this flow would be pure waste.
	if !e.connLogEnabled.Load() {
		return
	}
	cb := e.logCallback
	if cb == nil {
		return
	}
	go e.reportConnection(cb, flow, protocol)
}

// reportConnection resolves the owning app, dedups, and emits the log entry.
// Runs off the flow's handler goroutine — see logConnection. Resolving after
// the dial rather than before it means a very short-lived flow may have no
// socket left to attribute, degrading that entry to uid:<n>; the trade is
// deliberate, since the alternative is delaying every connection.
func (e *Engine) reportConnection(cb LogCallback, flow flowID, protocol int) {
	// Always log even if the app can't be resolved (fall back to uid:<n> /
	// unknown) so no traffic is silently hidden — the whole point is
	// visibility into what each app connects to.
	uid := UIDUnknown
	if e.uidResolver != nil {
		uid = resolveFlowUID(e.uidResolver, protocol, flow)
	}
	app := ""
	if uid != UIDUnknown && e.appUidResolver != nil {
		app = packageForUidCached(e.appUidResolver, uid)
	}
	if app == "" {
		if uid != UIDUnknown {
			app = fmt.Sprintf("uid:%d", uid)
		} else {
			app = "unknown"
		}
	}
	dest := flow.serverIP.String()
	key := fmt.Sprintf("%s|%s|%d|%d", app, dest, flow.serverPort, protocol)
	if _, dup := connLogSeen.LoadOrStore(key, struct{}{}); dup {
		return
	}
	proto := "TCP"
	if protocol == ProtocolUDP {
		proto = "UDP"
	}
	// Reuse the DNS-log pipeline: domain = "proto dest:port", resolvedIP =
	// dest, appName = package (Kotlin maps it to a friendly label),
	// blockedBy = "connection" so the UI can distinguish it from DNS.
	cb.OnDNSQuery(
		fmt.Sprintf("%s %s:%d", proto, dest, flow.serverPort),
		false, 0, 0, app, dest, "connection",
	)
}
