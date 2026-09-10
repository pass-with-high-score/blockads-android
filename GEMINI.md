# BlockAds Project Guidelines

This file serves as the primary instructions and workspace rules for AI assistants (Antigravity, Gemini, Copilot, Cursor).

## Core Architecture & Stack
- **Languages**: Kotlin (Android UI & Services), Go (DNS & VPN Tunnel engine).
- **Android Framework**: Jetpack Compose (Material 3, Navigation 3), Room DB, DataStore, Koin DI, Ktor CIO client, Timber.
- **Go Engine (`tunnel/`)**: Compiled into `app/libs/tunnel.aar` via `gomobile bind`.
  - Android 15 compatibility requires 16KB page size alignment (`-extldflags=-Wl,-z,max-page-size=16384`).
- **Privacy Standard**: 100% on-device filtering, zero telemetry without explicit user opt-in, no data selling or third-party ad tracking.

## Development Workflow & Rules

### 1. Kotlin & Jetpack Compose
- Always use **Material 3** components (`androidx.compose.material3`).
- Follow unidirectional data flow with `StateFlow<UiState>` and `collectAsStateWithLifecycle()`.
- Inject dependencies via Koin constructor injection.
- Keep composables stateless where possible and supply `@Preview` for Light/Dark themes.

### 2. Go Tunnel & Gomobile Interop
- All exported functions, structs, and interfaces in `tunnel/` must use **strictly gomobile-compatible types** (primitives, basic interfaces, `[]byte`).
- Avoid passing slices of structs, maps, or non-byte slices directly across JNI boundaries (serialize via JSON string or iterator callback).
- Ensure hot path performance (DNS interception, Bloom filters) has zero unnecessary heap allocations.
- When changing `tunnel/*.go`, recompile via `./scripts/build_tunnel.sh` or `./gradlew buildGoTunnel`.

### 3. Git & Commits
- Commit messages must strictly adhere to **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `perf:`).
- Verify code integrity with `./gradlew assembleDebug` or relevant unit tests before finalizing.

## Detailed Rule References
- Architecture & Stack: `.agent/rules/architecture.md`
- Kotlin & Compose: `.agent/rules/kotlin-compose.md`
- Go Tunnel & Gomobile: `.agent/rules/go-tunnel.md`
- Git & Workflow: `.agent/rules/git-workflow.md`
