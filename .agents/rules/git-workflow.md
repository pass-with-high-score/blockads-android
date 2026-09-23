# Git & Contribution Workflow Rules

## Conventional Commits
All commit messages must follow the [Conventional Commits](https://www.conventionalcommits.org/) format:
- `feat: ...` for new user-facing features.
- `fix: ...` for bug fixes.
- `docs: ...` for documentation changes.
- `refactor: ...` for code restructuring without behavioral change.
- `perf: ...` for performance improvements.
- `chore: ...` for build, dependencies, or tool configurations.
- `test: ...` for adding or modifying tests.

## Branch & Pull Request Guidelines
- Direct pushes to `main` should be reserved for releases or authorized maintainer updates.
- Feature and bugfix branches should be named descriptively (e.g., `feat/...`, `fix/...`).
- PRs must pass `./gradlew test` and linting prior to merge.
- Maintain clean atomic commits; rebase or squash when appropriate.
