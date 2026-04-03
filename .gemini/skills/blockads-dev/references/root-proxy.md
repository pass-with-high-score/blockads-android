# Root Proxy Mode Architecture

BlockAds supports a Root Proxy mode as an alternative to the standard `VpnService`. This mode requires a rooted device (e.g., via Magisk or KernelSU) and utilizes `libsu` and `iptables` to redirect DNS traffic transparently.

## Core Component: `RootProxyService`
`RootProxyService` is a Foreground Service that manages the lifecycle of the root proxy.

### Lifecycle & Mechanics
1. **Startup (`startProxy`):**
   - Loads filters, whitelist, and custom rules via `FilterListRepository`.
   - Configures the Go engine (DNS protocol, Upstream, SafeSearch, Block Response Type) via `GoTunnelAdapter`.
   - If Firewall is enabled, it initializes `FirewallManager` to load rules.
   - **Standalone Mode:** Unlike `VpnService` which feeds packets via TUN, Root Proxy starts the Go engine in "standalone" mode (`goTunnelAdapter.startStandalone(port = 15353)`). This spins up standard UDP/TCP listeners on `127.0.0.1:15353`.
   - **iptables Rules:** Uses `IptablesManager.setupRules()` to apply NAT rules that redirect all outgoing port 53 (DNS) traffic to `127.0.0.1:15353`.
   - Uses a retry loop (`VpnRetryManager`) in case `su` access or iptables configuration fails initially.

2. **Watchdog:**
   - A background coroutine periodically (every 10s) checks if the `iptables` rules are still active via `IptablesManager.isActive()`.
   - If the rules are cleared (e.g., by the system or another app), it reapplies them automatically.

3. **Shutdown (`stopProxy` / `onDestroy`):**
   - **CRITICAL:** Always calls `IptablesManager.teardownRules()`. Failing to remove these rules when the Go engine stops will result in total loss of DNS resolution (and thus internet access) for the device.
   - Stops the Go engine.

4. **Pause/Restart:**
   - `pauseProxy`: Tears down the proxy and schedules a `RootProxyResumeWorker` via WorkManager to restart it after 1 hour.
   - `restartProxy`: Used to apply new settings or filter changes without requiring the user to manually stop/start. It stops the engine, tears down rules, and immediately restarts.

## Key Differences from VpnService
- **Traffic Interception:** `VpnService` captures all IP packets at the network layer via a virtual TUN interface. Root proxy only captures DNS traffic by modifying the Linux kernel's NAT table (`iptables -t nat`).
- **Engine Execution:** 
  - `VpnService`: Go Engine reads raw IP packets from the TUN file descriptor.
  - `Root Proxy`: Go Engine acts as a standard local DNS server listening on a localhost port.
- **Firewall Constraints:** Root proxy can still enforce app-specific firewall rules by resolving the UID of the process making the DNS request, provided the proxy implementation supports reading the original socket metadata (though standard UDP proxying may obscure this depending on the iptables setup).