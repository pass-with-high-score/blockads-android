# BlockAds Architecture

The `blockads-android` app uses a hybrid architecture: high-performance network filtering is handled by Go, while the UI, persistence, and Android system integration are handled by Kotlin.

## 1. Go Tunnel Engine (`tunnel/`)

The core of the network processing is the `Engine` struct in `tunnel/engine.go`.

### Key Components:
- **DNS Interceptor:** Intercepts DNS queries on port 53 (UDP/TCP), decodes them using `github.com/miekg/dns`, and checks them against the blocking engine.
- **DomainChecker Interface:** Go delegates the actual domain checking (Ad/Tracker/Security filtering) to Kotlin. Kotlin uses an efficient mmap'd Trie data structure.
- **Router:** Handles routing traffic. It can route out directly to the network or through an `OutboundAdapter` (like WireGuard).
- **LogCallback:** Go reports every DNS query back to Kotlin so it can be saved in the Room database (`DnsLogDao`) and displayed in the UI.

## 2. Kotlin Android App (`app/src/main/...`)

### Key Components:
- **VpnService:** The Android `VpnService` intercepts all device traffic, reading IP packets and passing them to the Go Engine via TUN file descriptors.
- **GoTunnelAdapter:** The bridge class that instantiates the Go `Engine`, configures DNS, and sets up callbacks (e.g., passing `LogCallback` and `DomainChecker` implementations to Go).
- **UI (Jetpack Compose):** Displays statistics, DNS logs, and settings.
- **Data (Room & DataStore):** Stores blocklists, custom rules, and DNS request history.
- **Root Mode (`libsu`):** Optionally, the app can run as a root proxy using `iptables` rules instead of a local VPN.

## 3. Data Flow

1. **App Request:** An app on the Android device makes a DNS request.
2. **TUN Interface:** Android's `VpnService` routes the packet to the TUN interface.
3. **Go Router:** The Go Engine reads the packet from the TUN interface. If it's DNS, it goes to the `DnsInterceptor`.
4. **Filtering:** Go extracts the domain and calls `DomainChecker.IsBlocked(domain)` (implemented in Kotlin).
5. **Resolution:** If not blocked, Go resolves the domain via Upstream DNS (Plain, DoH, DoT, DoQ).
6. **Logging:** Go calls `LogCallback.OnDNSQuery(...)` to notify Kotlin of the result.
7. **Response:** Go sends the DNS response back through the TUN interface to the requesting app.