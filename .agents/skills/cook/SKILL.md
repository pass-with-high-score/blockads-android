---
name: cook
description: High-autonomy "chef" for implementing new features from scratch. Handles code scanning, architectural research, technical specification, and full-stack implementation with a focus on "clean code" and project idiomatics. Use when the user says "cook this feature", "implement X", or "build Y".
---

# The "Cook" Workflow

This skill transforms a high-level feature request into a production-ready implementation through a structured "kitchen" process.

## Phase 1: Preparation (Research & Scan)
- **Scan:** Use `grep_search` and `glob` to find existing patterns, related components, and architectural precedents.
- **Analyze:** Understand how data flows and how existing features are structured in this specific codebase.
- **Ingredients:** Identify the "ingredients" needed (new classes, UI components, API endpoints, or database changes).

## Phase 2: The Recipe (Clear Spec)
- **Draft Spec:** Create a concise technical plan.
- **Check-in:** Present the plan to the user.
- **Clarify:** If anything is ambiguous (UI details, edge cases), **ASK** for confirmation before touching the stove.

## Phase 3: Cooking (Implementation)
- **Atomic Commits:** (Mental or actual if requested) Build the feature in logical, self-contained steps.
- **Idiomatic Code:** Follow the project's established style (naming, patterns, libraries).
- **Clean Code:** Prioritize readability, DRY principles, and proper error handling.

## Phase 4: Taste Test (Verification)
- **Verify:** Run the build (`./gradlew assembleDebug`) and relevant tests.
- **Cleanup:** Remove any temporary debug logs, comments, or unused imports.

## Phase 5: Serving (Final Review)
- **Report:** Summarize what was built and how to test it.

## Guardrails
- **Don't Over-Season:** Stay within the scope of the requested feature.
- **Fresh Ingredients:** Always use the latest project conventions found in `GEMINI.md`.
- **Safety First:** Never proceed with high-impact architectural changes without an approved "Recipe".
