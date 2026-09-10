# BlockAds Project Guidelines

This file serves as the primary instructions and workspace rules for AI assistants (Antigravity, Gemini, Copilot, Cursor).

## Core Architecture & Stack
- **Languages**: Kotlin (Android UI & Services), Go (DNS & VPN Tunnel engine).
- **Android Framework**: Jetpack Compose (Material 3, Navigation 3), Room DB, DataStore, Koin DI, Ktor CIO client, Timber.
- **Go Engine (`tunnel/`)**: Compiled into `app/libs/tunnel.aar` via `gomobile bind`.
  - Android 15 compatibility requires 16KB page size alignment (`-extldflags=-Wl,-z,max-page-size=16384`).
- **Privacy Standard**: 100% on-device filtering, zero telemetry without explicit user opt-in, no data selling or third-party ad tracking.

## Development Workflow & Code Rules

### 1. File Length & Structural Rules
- **Max 500 lines per file**: No single file may exceed 500 lines. Decompose large files into smaller, focused files, sub-composables, or delegates.
- **Single Responsibility Principle (SRP)**: Each file has exactly one responsibility. Never lump models, contracts, ViewModels, and UI composables into the same file.
- **Flat Structure (No deep nesting)**: Avoid deep nesting in composable hierarchies, conditional logic, and nested inner classes.
- **Clean Code & Reusability (DRY)**: Reusable components (cards, items, dialogs, utils) must be extracted into shared packages instead of duplicating code across screens.
- **Concise Comments (No long comments)**: Code must be self-documenting through clear, expressive naming. Do not write lengthy comments or explain obvious code. Only write brief (1-2 lines) notes explaining *why* (non-obvious rationale), never *what*. Never leave commented-out dead code.

### 2. MVI Architecture Standard
Each screen must strictly follow the **MVI pattern** separated across dedicated files:
- **Contract (`*Contract.kt`)**: Defines `UiState` (immutable data class), `UiIntent` / `UiEvent` (sealed interface for user actions), and `UiEffect` (one-off side effects like navigation/snackbars).
- **ViewModel (`*ViewModel.kt`)**: Handles business logic, exposes `StateFlow<UiState>` and `SharedFlow<UiEffect>`, processes `UiIntent` via a single entry point, and injects dependencies via Koin constructor injection.
- **Screen (`*Screen.kt`)**: Pure, stateless Jetpack Compose UI receiving `UiState` and `(UiIntent) -> Unit` event dispatcher. Handle one-off `UiEffect` via `LaunchedEffect`.

### 3. Kotlin & Jetpack Compose
- Always use **Material 3** components (`androidx.compose.material3`).
- Follow unidirectional data flow with `StateFlow<UiState>` and `collectAsStateWithLifecycle()`.
- Keep composables stateless where possible and supply `@Preview` for Light/Dark themes.

### 4. Go Tunnel & Gomobile Interop
- All exported functions, structs, and interfaces in `tunnel/` must use **strictly gomobile-compatible types** (primitives, basic interfaces, `[]byte`).
- Avoid passing slices of structs, maps, or non-byte slices directly across JNI boundaries (serialize via JSON string or iterator callback).
- Ensure hot path performance (DNS interception, Bloom filters) has zero unnecessary heap allocations.
- When changing `tunnel/*.go`, recompile via `./scripts/build_tunnel.sh` or `./gradlew buildGoTunnel`.

### 5. Git & Commits
- Commit messages must strictly adhere to **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `perf:`).
- Verify code integrity with `./gradlew assembleDebug` or relevant unit tests before finalizing.

## Detailed Rule References
- Architecture & Stack: `.agent/rules/architecture.md`
- Kotlin & Compose: `.agent/rules/kotlin-compose.md`
- Go Tunnel & Gomobile: `.agent/rules/go-tunnel.md`
- Git & Workflow: `.agent/rules/git-workflow.md`
