# Kotlin & Jetpack Compose Rules

## File & Structure Constraints
- **File Length Limit**: Every file must **NOT exceed 500 lines**. If a file approaches or exceeds this limit, decompose it into smaller, focused files (e.g., separate sub-composables, models, helpers, or delegates).
- **Single Responsibility Principle (SRP)**: Each file has exactly **one responsibility**. Do not combine models, contracts, ViewModels, and UI composables in a single file.
- **Flat Code Hierarchy**: Keep code flat and avoid deep nesting (in composable trees, conditional statements, and inner classes).

## MVI Architecture Standard
Each screen must strictly follow the **MVI (Model-View-Intent)** pattern separated into clear components:
1. **Contract (`*Contract.kt`)**:
   - `UiState`: Data class representing immutable UI state.
   - `UiIntent` (or `UiEvent`): Sealed interface/class representing all possible user actions or screen events.
   - `UiEffect` (or `UiSideEffect`): Sealed interface/class for one-off events (navigation, toast/snackbars, dialogs).
2. **ViewModel (`*ViewModel.kt`)**:
   - Exposes `StateFlow<UiState>` and `SharedFlow<UiEffect>` (or channel-backed flow).
   - Processes `UiIntent` through a single public handler (e.g., `processIntent(intent)` or `sendIntent(intent)`).
   - Updates state via immutable copy operations and injects dependencies via Koin constructor injection.
3. **Screen (`*Screen.kt`)**:
   - Stateless Jetpack Compose functions receiving `uiState` and an event dispatch lambda `(UiIntent) -> Unit`.
   - Side effects handled with `LaunchedEffect` collecting `uiEffect`.
   - Separate stateful wrapper (if needed for routing) from the pure stateless composable.

## Clean Code & High Reusability (DRY)
- **Shared Components**: Do not duplicate UI elements or business logic. Extract reusable composables (cards, headers, toggle items, empty states) into shared UI packages.
- **Extensions & Utils**: Place repetitive logic (formatting, permissions check, flow extensions) into dedicated utility files.
- **Concise Comments (No Verbose / Long Comments)**:
  - Code must be self-explanatory with clean, descriptive naming for variables, classes, and functions.
  - Avoid writing obvious comments that describe *what* code does (e.g., `// set state to true`).
  - Only write brief (1-2 lines max) comments when explaining *why* an unusual or non-obvious decision was made.
  - Never commit commented-out dead code or lengthy comment blocks.

## Compose & Material 3 Standards
- **Material 3**: Exclusively use `androidx.compose.material3` components and color schemes.
- **State Hoisting**: Keep composables stateless where possible. Pass state down, events up.
- **StateFlow & Lifecycle**: Collect state with `collectAsStateWithLifecycle()` in composables.
- **Preview Support**: Provide `@Preview` annotations with Light and Dark theme configurations for all reusable UI components.
- **Dynamic Theming**: Respect Material You dynamic colors while supporting user-selected accent themes.

## Error Handling & Coroutines
- **Error Handling**: Use structured `Result` or domain-specific sealed classes for operations. Do not swallow exceptions silently.
- **Coroutines Scope**: Respect Android lifecycle; use `viewModelScope` for ViewModels and `lifecycleScope` / structured `CoroutineScope` for foreground services.
