---
name: blockads-dev
description: Assistance with Go and Kotlin interop, VPN architecture, and tunnel building in the BlockAds Android app. Use when adding features to the Go tunnel, working with gomobile, or modifying the VPN service.
---

# BlockAds Dev Skill

## Overview

This skill provides expert guidance on the `blockads-android` project, which combines a Go-based VPN tunnel (`tunnel/`) with an Android Kotlin application (`app/src/main/java/...`). It helps handle `gomobile` interop constraints, architectural patterns for the VPN service, and building/debugging the tunnel.

## Development Workflows

### 1. Modifying the Go Tunnel (gomobile)

When adding a new feature or modifying existing logic in the Go tunnel:
- Edit the relevant `.go` files in the `tunnel/` directory (e.g., `tunnel/engine.go`).
- Ensure all exported functions, methods, and interfaces in Go use strictly gomobile-compatible types (primitives, `[]byte`, basic interfaces).
- Rebuild the tunnel using `./scripts/build_tunnel.sh` or the Gradle task `./gradlew buildGoTunnel`.
- For detailed constraints on gomobile bindings, read `references/gomobile-interop.md`.

### 2. Updating the Android Kotlin App

When integrating new Go tunnel features into Android:
- Update `GoTunnelAdapter.kt` or `VpnService.kt` to call the newly bound Go methods.
- Ensure Kotlin interfaces passed to Go (e.g., `LogCallback`, `DomainChecker`) match the updated Go interfaces.
- For a deeper understanding of the VPN architecture and data flow, read `references/architecture.md`.

### 3. Debugging

- **DNS Tracing:** The Go engine logs queries via the `LogCallback` interface to Kotlin. To debug DNS, check `DnsLogDao` or add prints in the Kotlin callback.
- **Root Mode (iptables):** The app uses `libsu` for a root-based transparent proxy mode as an alternative to `VpnService`. Check `RootProxyService` and `IptablesManager` for related logic. See `references/root-proxy.md` for architecture details.

## References

- **[Architecture](references/architecture.md):** Deep dive into the Go Tunnel Engine and Kotlin VPN Service architecture.
- **[Root Proxy Mode](references/root-proxy.md):** Deep dive into the `RootProxyService` and `iptables` transparent proxy architecture.
- **[Gomobile Interop](references/gomobile-interop.md):** Rules, constraints, and patterns for passing data between Go and Kotlin via `gomobile bind`.
