# Architecture Rules & Stack Overview

## Overview
BlockAds is a privacy-first, open-source Android ad blocker with dual routing modes:
1. **VPN Mode (Default)**: Non-root VPNService with local DNS interception and userspace HTTPS filtering.
2. **Root Mode**: Transparent proxy via `iptables` and `libsu`.

## Tech Stack
- **Languages**: Kotlin (Android UI & Services), Go (DNS/Tunnel Engine via `gomobile`).
- **UI Framework**: Jetpack Compose with Material 3 and Navigation 3.
- **Dependency Injection**: Koin (`koin-android`, `koin-compose`).
- **Database & Storage**: Room (`androidx.room`), DataStore Preferences (`androidx.datastore`).
- **Networking**: Ktor Client (`cio` engine) for HTTP updates; Go userspace TCP/IP for tunnel.
- **Logging**: Timber for Android app; gomobile `LogCallback` for tunnel DNS tracing.
- **Architecture**: MVI / MVVM with unidirectional data flow (`StateFlow` and immutable UI state).

## Project Structure
- `app/`: Primary Android phone/tablet application.
- `blockadstv/`: Android TV companion module.
- `tunnel/`: Go source code compiled to `app/libs/tunnel.aar` via gomobile.
- `scripts/`: Build and automation scripts (e.g., `build_tunnel.sh`).
- `.agent/`: Agent workflows, skills, and rule sets.

## Key Constraints
- **Privacy by Design**: Never collect, track, or transmit user traffic without explicit opt-in.
- **16KB Page Size**: Native libraries (`tunnel.aar`) must support 16KB page size alignment for Android 15+ compatibility (`-extldflags=-Wl,-z,max-page-size=16384`).
- **Reproducible Builds**: Do not introduce non-deterministic metadata, timestamps, or dynamic build IDs in release builds.
