# Kotlin & Jetpack Compose Rules

## Compose Standards
- **Material 3**: Exclusively use `androidx.compose.material3` components and color schemes.
- **State Hoisting**: Keep composables stateless where possible. Pass state down, events up (`onEvent: () -> Unit`).
- **StateFlow & Coroutines**: Expose immutable `StateFlow<UiState>` from ViewModels. Collect with `collectAsStateWithLifecycle()` in composables.
- **Preview Support**: Provide `@Preview` annotations with Light and Dark theme configurations for all reusable UI components.
- **Dynamic Theming**: Respect Material You dynamic colors while supporting user-selected accent themes.

## Coding Style
- **Clean Architecture**: Separate UI (`ui/`), Business Logic/ViewModels, and Data Layer (`data/` repositories & DAOs).
- **DI with Koin**: Inject dependencies via Koin constructor injection in ViewModels and services. Do not instantiate singletons manually.
- **Error Handling**: Use structured `Result` or domain-specific sealed classes for operations. Do not swallow exceptions silently.
- **Coroutines Scope**: Respect Android lifecycle; use `viewModelScope` for ViewModels and `lifecycleScope` / structured CoroutineScope for foreground services.
