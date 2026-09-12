package tunnel

import (
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// countingUIDResolver stands in for the Kotlin resolver, whose real
// implementation is a getConnectionOwnerUid binder round trip.
type countingUIDResolver struct {
	calls atomic.Int64
	uid   int
}

func (c *countingUIDResolver) ResolveUID(protocol int, localIP string, localPort int, remoteIP string, remotePort int) int {
	c.calls.Add(1)
	return c.uid
}

// countingPackageResolver stands in for getPackagesForUid.
type countingPackageResolver struct {
	calls atomic.Int64
	pkg   string
}

func (c *countingPackageResolver) PackageForUid(uid int) string {
	c.calls.Add(1)
	return c.pkg
}

type recordingLogCallback struct {
	mu      sync.Mutex
	domains []string
}

func (r *recordingLogCallback) OnDNSQuery(domain string, blocked bool, queryType int, responseTimeMs int64, appName string, resolvedIP string, blockedBy string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.domains = append(r.domains, domain)
}

func (r *recordingLogCallback) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.domains)
}

func testFlow(port int) flowID {
	return flowID{
		appIP:      net.ParseIP("100.64.100.2"),
		appPort:    port,
		serverIP:   net.ParseIP("93.184.216.34"),
		serverPort: 443,
	}
}

// resetConnLogState clears the package-level caches so tests don't leak into
// each other (the engine clears both on session start).
func resetConnLogState() {
	connLogSeen.Range(func(k, _ any) bool { connLogSeen.Delete(k); return true })
	uidPackageCache.Range(func(k, _ any) bool { uidPackageCache.Delete(k); return true })
}

// waitFor polls until cond holds or the deadline passes. logConnection hands
// the resolution to a goroutine, so assertions on its effects have to wait.
func waitFor(t *testing.T, cond func() bool) bool {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return true
		}
		time.Sleep(5 * time.Millisecond)
	}
	return cond()
}

// The point of the connLogEnabled gate: with logging off, a flow must not
// trigger any per-flow app resolution. Both resolvers are binder calls on
// Android, and they used to run on the connection-setup path regardless of
// whether the user was recording logs at all.
func TestLogConnectionSkipsResolutionWhenDisabled(t *testing.T) {
	resetConnLogState()

	uidr := &countingUIDResolver{uid: 10123}
	pkgr := &countingPackageResolver{pkg: "com.example.app"}
	cb := &recordingLogCallback{}

	e := NewEngine()
	e.SetUIDResolver(uidr)
	e.SetAppUidResolver(pkgr)
	e.SetLogCallback(cb)
	// Deliberately not calling SetConnLogEnabled — off is the default.

	for i := 0; i < 20; i++ {
		e.logConnection(testFlow(40000+i), ProtocolTCP)
	}

	// Give any (incorrectly) spawned goroutine a chance to run before asserting.
	time.Sleep(50 * time.Millisecond)

	if got := uidr.calls.Load(); got != 0 {
		t.Errorf("uid resolver called %d times with logging disabled, want 0", got)
	}
	if got := pkgr.calls.Load(); got != 0 {
		t.Errorf("package resolver called %d times with logging disabled, want 0", got)
	}
	if got := cb.count(); got != 0 {
		t.Errorf("emitted %d log entries with logging disabled, want 0", got)
	}
}

// With logging on, the uid→package lookup is memoized: many flows from one app
// cost one getPackagesForUid call, not one per flow.
func TestLogConnectionCachesPackageLookup(t *testing.T) {
	resetConnLogState()

	uidr := &countingUIDResolver{uid: 10123}
	pkgr := &countingPackageResolver{pkg: "com.example.app"}
	cb := &recordingLogCallback{}

	e := NewEngine()
	e.SetUIDResolver(uidr)
	e.SetAppUidResolver(pkgr)
	e.SetLogCallback(cb)
	e.SetConnLogEnabled(true)

	const flows = 15
	for i := 0; i < flows; i++ {
		e.logConnection(testFlow(40000+i), ProtocolTCP)
	}

	if !waitFor(t, func() bool { return uidr.calls.Load() == flows }) {
		t.Fatalf("uid resolver called %d times, want %d", uidr.calls.Load(), flows)
	}
	if got := pkgr.calls.Load(); got != 1 {
		t.Errorf("package resolver called %d times, want 1 (result should be cached)", got)
	}
	// Same app + same destination + same port → one entry, the rest deduped.
	if got := cb.count(); got != 1 {
		t.Errorf("emitted %d log entries, want 1 (dedup by app+dest+port)", got)
	}
}

// A failed package lookup must not be cached — the Kotlin resolver returns ""
// both for "no package" and for a thrown PackageManager call, so caching it
// would pin the UID to "unknown" for the rest of the session.
func TestPackageLookupFailureIsNotCached(t *testing.T) {
	resetConnLogState()

	failing := &countingPackageResolver{pkg: ""}
	if got := packageForUidCached(failing, 10123); got != "" {
		t.Fatalf("first lookup returned %q, want empty", got)
	}
	if got := packageForUidCached(failing, 10123); got != "" {
		t.Fatalf("second lookup returned %q, want empty", got)
	}
	if got := failing.calls.Load(); got != 2 {
		t.Errorf("resolver called %d times, want 2 (empty results must be retried)", got)
	}

	// A later success is cached.
	ok := &countingPackageResolver{pkg: "com.example.app"}
	if got := packageForUidCached(ok, 10124); got != "com.example.app" {
		t.Fatalf("lookup returned %q, want com.example.app", got)
	}
	if got := packageForUidCached(ok, 10124); got != "com.example.app" {
		t.Fatalf("cached lookup returned %q, want com.example.app", got)
	}
	if got := ok.calls.Load(); got != 1 {
		t.Errorf("resolver called %d times, want 1 (success should be cached)", got)
	}
}

// Two apps reaching the same destination are two distinct entries — the dedup
// key includes the app, so one must not mask the other.
func TestLogConnectionKeepsPerAppEntries(t *testing.T) {
	resetConnLogState()

	cb := &recordingLogCallback{}
	e := NewEngine()
	e.SetAppUidResolver(&countingPackageResolver{pkg: ""})
	e.SetLogCallback(cb)
	e.SetConnLogEnabled(true)

	e.SetUIDResolver(&countingUIDResolver{uid: 10123})
	e.logConnection(testFlow(40000), ProtocolTCP)
	if !waitFor(t, func() bool { return cb.count() == 1 }) {
		t.Fatalf("first app emitted %d entries, want 1", cb.count())
	}

	e.SetUIDResolver(&countingUIDResolver{uid: 10456})
	e.logConnection(testFlow(40001), ProtocolTCP)
	if !waitFor(t, func() bool { return cb.count() == 2 }) {
		t.Errorf("second app emitted %d entries total, want 2", cb.count())
	}
}
