---
name: code-audit
description: Scan the codebase for architectural issues, files exceeding 500 lines, missing MVI contracts, duplicate code, and gomobile interop risks, and generate actionable refactoring plans. Use when the user asks to "audit code", "check code issues", "find files to refactor", or "refactor screen".
---

# Code Audit & Refactoring Skill

This skill diagnoses code smells, architectural violations, and rule non-compliance across the `blockads-android` project, then guides safe, incremental refactoring according to project standards (`GEMINI.md` and `.agent/rules/`).

## Capabilities

1. **Automated Codebase Audit**:
   - Detect files exceeding the **500-line limit** (both Kotlin and Go).
   - Identify UI screen modules missing dedicated **`*Contract.kt`** files (MVI compliance).
   - Identify god-classes and monoliths needing decomposition.
   - Verify gomobile interop constraints in `tunnel/`.

2. **Targeted Refactoring Workflows**:
   - Splitting large composables (>500 lines) into focused sub-composables in dedicated files.
   - Converting legacy screens to standard MVI (`Contract` + `Screen` + `ViewModel`).
   - Breaking down monolithic services (e.g., `AdBlockVpnService.kt`, `AppPreferences.kt`) into delegates.

---

## How to Run an Audit

Execute the built-in audit script from the terminal:

```bash
python3 .agent/skills/code-audit/scripts/audit_codebase.py
```

This generates an instant Markdown report covering:
- File line count violations categorized by severity (🔴 Critical > 750 lines, 🟡 Warning > 500 lines).
- UI modules lacking dedicated MVI Contracts.
- Gomobile compatibility issues.
- Prioritized refactoring roadmap.

---

## 4-Phase Refactoring Process

When tasked with refactoring any module or screen identified by the audit:

### Phase 1: Diagnosis & Scope Definition
- Run the audit script or inspect the target file.
- Identify the exact responsibilities of the file.
- Define what parts will be extracted (e.g., UI dialogs, card items, state models, helper functions).

### Phase 2: Refactoring Plan
Draft a concise plan detailing:
1. **Target File**: File to be decomposed.
2. **New Extracted Files**:
   - `*Contract.kt`: Immutable `UiState`, `UiIntent`, `UiEffect`.
   - `components/*Item.kt` or `components/*Dialog.kt`: Extracted stateless composables.
   - `*Delegate.kt` or `*Helper.kt`: Extracted business logic or DataStore operations.
3. Target line count for each file (all strictly < 500 lines).

### Phase 3: Execution (Incremental & Atomic)
- Extract independent contracts/models first.
- Extract reusable UI components into `ui/components/` or screen-specific subpackages.
- Keep the main Screen or Service as an orchestrator with a flat hierarchy.
- Ensure all dependencies are injected via Koin constructor injection.

### Phase 4: Taste Test & Verification
- Compile the project:
  ```bash
  ./gradlew assembleDebug
  ```
- Run unit tests:
  ```bash
  ./gradlew test
  ```
- If modifying Go tunnel:
  ```bash
  ./gradlew buildGoTunnel
  ```
- Verify zero regression in functionality and confirm all new files are under 500 lines.
