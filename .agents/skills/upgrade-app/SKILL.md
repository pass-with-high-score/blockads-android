---
name: upgrade-app
description: Automates the process of upgrading the app version. Bumps versionCode and versionName in build.gradle.kts, updates the changelog, verifies the build, commits, pushes, and creates a GitHub release (supporting both Stable v* and Pre-release beta-*/pre-* channels). Use when the user says "upgrade app", "release new version", or "bump version".
---

# Upgrade App Workflow

This skill automates the end-to-end process of releasing a new version of the application.

## Release Channels
- **Pre-release (`beta-*` / `pre-*`)**: No leading `v` (e.g. `beta-7.2.0-1`, `pre-7.2.0-rc1`). F-Droid ignores non-`v` tags, and `.github/workflows/deploy.yml` marks the GitHub Release as `prerelease: true` so `/releases/latest` and Obtainium (default) stay on Stable.
- **Stable (`v*`)**: Leading `v` (e.g. `v7.2.0`). F-Droid picks up `v*` tags, and `deploy.yml` marks the GitHub Release as `prerelease: false` (`Latest`).

## Workflow Steps

### 1. Preparation
- Ensure the git working tree is clean (`git status`).
- Check `gh auth status` to ensure GitHub CLI is authenticated.
- If `tunnel/` was changed since the last `tunnel-*` tag, make sure `.github/workflows/update_tunnel.yml` has published the new `tunnel.aar` and updated `gradle/libs.versions.toml` + `gradle/tunnel.aar.sha256` before tagging (since `deploy.yml` builds without `-Ptunnel.source=local`).

### 2. Research & Versioning
- Read `app/build.gradle.kts` and `blockadstv/build.gradle.kts` to find the current `versionCode` and `versionName`.
- Ask the user which channel to release (**Pre-release** `beta-<version>-<n>` / `pre-<version>` or **Stable** `v<version>`), and suggest the next `versionName` and `versionCode` (`+1` across both modules).

### 3. Update Files
- Update `app/build.gradle.kts` and `blockadstv/build.gradle.kts` with the new `versionCode` and `versionName`.
- Fetch commits since the last app release tag (excluding `tunnel-*` / `geoip-*` tags):
  ```bash
  LAST_TAG=$(git describe --tags --abbrev=0 --match 'v*' --match 'beta-*' --match 'pre-*')
  git log $LAST_TAG..HEAD --oneline
  ```
- **Filter commits:** Exclude commits that are only about translations or language updates (e.g., commits with `i18n`, `translation`, `locale`, `strings.xml` in the message). These are not user-facing features and should NOT appear in the changelog.
- **Strip issue references:** Remove all GitHub issue references (e.g., `#70`, `(#72)`, `#71, #78`) from the changelog text. The changelog is for end users on Google Play, not for developers tracking issues.
- Generate a concise, user-friendly changelog and write it to `fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt`.
- **MANDATORY:** Check the character count of the changelog. It MUST NOT exceed 500 characters (Google Play Store limit). Use `wc -c <file_path>` to verify. If it exceeds the limit, shorten it before proceeding.

### 4. User Confirmation
- **MANDATORY:** Present the release channel, tag name, and generated changelog to the user.
- **WAIT** for the user to confirm or edit before proceeding.

### 5. Build Verification
- Run `./gradlew assembleDebug` to verify the build (or `./gradlew -Ptunnel.source=local assembleDebug` if testing local tunnel changes).
- If it fails, stop and report errors.

### 6. Git & GitHub Operations (MUST BE SEQUENTIAL)
- To avoid race conditions and tagging the wrong commit, execute the commit and tagging sequentially in a single command block:
  - **For Pre-release (`TAG=beta-<versionName>-1` or `pre-<versionName>`):**
    ```bash
    git add app/build.gradle.kts blockadstv/build.gradle.kts fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt && \
    git commit -m "chore: bump version to <versionName> (<versionCode>)" && \
    git tag <TAG> && \
    git push origin main && \
    git push origin <TAG> && \
    gh release create <TAG> --prerelease --title "<TAG>" --notes-file fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt
    ```
  - **For Stable (`TAG=v<versionName>`):**
    ```bash
    git add app/build.gradle.kts blockadstv/build.gradle.kts fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt && \
    git commit -m "chore: bump version to <versionName> (<versionCode>)" && \
    git tag v<versionName> && \
    git push origin main && \
    git push origin v<versionName> && \
    gh release create v<versionName> --title "v<versionName>" --notes-file fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt
    ```

## Guardrails
- **Race Condition Prevention:** Never separate `git commit` and `git tag` into different tool calls in the same turn without `wait_for_previous: true`.
- **Validation:** Always verify the build with `./gradlew assembleDebug` before pushing.
- **Confirmation:** Always wait for user confirmation on the channel, tag, and changelog.
- **Format:** Stable tags MUST match `vX.Y.Z`; Pre-release tags MUST NOT start with `v` (use `beta-X.Y.Z-N` or `pre-X.Y.Z`) so F-Droid does not build pre-releases.
- **Changelog Limit:** The changelog file MUST be under 500 characters to avoid Google Play API rejection. Always verify with `wc -c`.
- **No Issue References:** Never include GitHub issue numbers (e.g., `#70`, `(#72)`) in the changelog. End users don't care about issue trackers.
- **No Translation Commits:** Never include translation/i18n/locale update entries in the changelog. These are routine maintenance, not user-facing features.
