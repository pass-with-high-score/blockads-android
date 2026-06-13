---
name: upgrade-app
description: Automates the process of upgrading the app version. Bumps versionCode and versionName in build.gradle.kts, updates the changelog, verifies the build, commits, pushes, and creates a GitHub release. Use when the user says "upgrade app", "release new version", or "bump version".
---

# Upgrade App Workflow

This skill automates the end-to-end process of releasing a new version of the application.

## Workflow Steps

### 1. Preparation
- Ensure the git working tree is clean (`git status`).
- Check `gh auth status` to ensure GitHub CLI is authenticated.

### 2. Research & Versioning
- Read `app/build.gradle.kts` to find the current `versionCode` and `versionName`.
- Suggest a new `versionName` (e.g., if current is `1.7.1`, suggest `1.7.2`).
- Increment `versionCode` by 1.

### 3. Update Files
- Update `app/build.gradle.kts` with the new version info.
- Fetch commits since the last tag:
  ```bash
  LAST_TAG=$(git describe --tags --abbrev=0)
  git log $LAST_TAG..HEAD --oneline
  ```
- Generate a concise changelog and write it to `fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt`.
- **MANDATORY:** Check the character count of the changelog. It MUST NOT exceed 500 characters (Google Play Store limit). Use `wc -c <file_path>` to verify. If it exceeds the limit, shorten it before proceeding.

### 4. User Confirmation
- **MANDATORY:** Present the generated changelog to the user.
- **WAIT** for the user to confirm or edit the changelog before proceeding.

### 5. Build Verification
- Run `./gradlew assembleDebug` to verify the build.
- If it fails, stop and report errors.

### 6. Git & GitHub Operations (MUST BE SEQUENTIAL)
- To avoid race conditions and tagging the wrong commit, execute the commit and tagging sequentially in a single command block:
  ```bash
  git add app/build.gradle.kts fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt && \
  git commit -m "chore: bump version to <versionName> (<versionCode>)" && \
  git tag v<versionName> && \
  git push origin main && \
  git push origin v<versionName> && \
  gh release create v<versionName> --title "v<versionName>" --notes-file fastlane/metadata/android/en-US/changelogs/<new-versionCode>.txt
  ```

## Guardrails
- **Race Condition Prevention:** Never separate `git commit` and `git tag` into different tool calls in the same turn without `wait_for_previous: true`.
- **Validation:** Always verify the build with `./gradlew assembleDebug` before pushing.
- **Confirmation:** Always wait for user confirmation on the changelog.
- **Format:** Ensure the tag matches the `vX.Y.Z` format.
- **Changelog Limit:** The changelog file MUST be under 500 characters to avoid Google Play API rejection. Always verify with `wc -c`.
