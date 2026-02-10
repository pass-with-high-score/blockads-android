# 🛡️ BlockAds

**Block ads system-wide on Android. No root needed.**

BlockAds uses local VPN-based DNS filtering to block ads and trackers across all apps and browsers — with zero data collection.

## ✨ Features

- 🚫 **System-wide ad blocking** — DNS filtering, no root required
- 🔒 **Privacy first** — All data stored locally, nothing sent to servers
- 🎨 **Dark / Light / System theme** — Material 3 design
- 📊 **Stats & charts** — Real-time blocked count, 24h activity chart
- 📋 **DNS query logs** — See exactly what's blocked
- ⚙️ **Multiple filter lists** — ABPVN, AdGuard, EasyList, custom URLs
- 📱 **Quick Settings tile** — Toggle from notification shade
- 💾 **Export / Import settings** — Backup & restore with JSON
- 🔄 **Auto-reconnect on boot** — Always-on protection
- 🆓 **Free & open source** — No ads, no in-app purchases

## 🚀 Getting Started

### Prerequisites

- Android Studio Ladybug+
- JDK 17
- Android SDK 36 (min SDK 24)

### Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing key)
./gradlew bundleRelease
```

### Fastlane

```bash
# Install dependencies
bundle install

# Build debug
bundle exec fastlane build_debug

# Deploy to Play Store internal track
bundle exec fastlane internal

# Bump version
bundle exec fastlane bump_version version:1.1
```

## 🏗️ Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + StateFlow |
| DI | Koin |
| Database | Room |
| Preferences | DataStore |
| Networking | Ktor |
| Serialization | kotlinx.serialization |
| CI/CD | GitHub Actions + Fastlane |

## 📁 Project Structure

```
app/src/main/java/app/pwhs/blockads/
├── data/           # Room entities, DAOs, repositories, preferences
├── di/             # Koin modules
├── service/        # VPN service, boot receiver, QS tile
└── ui/
    ├── home/       # Home screen + stats chart
    ├── logs/       # DNS query log viewer
    ├── settings/   # Settings + export/import
    ├── onboarding/ # First-time setup
    └── theme/      # Color, typography, theme
```

## 🔐 How It Works

1. BlockAds creates a **local VPN** on your device
2. DNS queries are routed through the VPN tunnel (`10.0.0.1/32`)
3. Queries matching filter lists are **blocked locally**
4. All other traffic passes through normally — **no data leaves your device**

## 📦 Release

```bash
# Tag a version to trigger CI/CD
git tag v1.0 && git push origin v1.0
```

GitHub Actions will automatically build, sign, and deploy to Play Store.

## 📄 License

This project is open source. See [LICENSE](LICENSE) for details.
