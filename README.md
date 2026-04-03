<div align="center">      
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" height="128">
  <h1>BlockAds</h1>
  <p><strong>BlockAds</strong> is a free, open-source ad blocker for Android.</p>
<p>It blocks ads, trackers, and malware system-wide using local VPN-based DNS filtering — no root required, no data collection.</p>
<p>Built with Jetpack Compose and Material 3 for a modern, premium experience.</p>
  <br><br>
  <a href="https://github.com/pass-with-high-score/blockads-android/releases">
    <img src="https://img.shields.io/github/v/release/pass-with-high-score/blockads-android">
  </a>
  <a href="https://github.com/pass-with-high-score/blockads-android/releases">
    <img src="https://img.shields.io/github/downloads/pass-with-high-score/blockads-android/total">
  </a>
  <br><br>
  <h4>Download</h4>
  <a href="https://f-droid.org/packages/app.pwhs.blockads">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80" alt="Get it on F-Droid">
  </a>
  <a href="https://github.com/pass-with-high-score/blockads-android/releases">
    <img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" height="80">
  </a>
  <br><br>
    <a href="https://apt.izzysoft.de/packages/app.pwhs.blockads">
    <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="54" alt="Get it at IzzyOnDroid">
  </a>
</div> 

---  

## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="200">
</div>

---

## Features

* Dual Routing Modes: System-wide ad blocking via VPN (no root needed) OR Root Proxy Mode (via iptables)
* Privacy-first Optional Crash Reporting & Manual Local Logs Export
* Comprehensive Onboarding Flow (Permissions, Battery Optimization, Opt-in telemetry)
* WireGuard Profile Import & HTTPS Filtering (Beta)
* Multiple built-in filter lists (StevenBlack, AdGuard DNS, EasyList, and more)
* Region-aware defaults — auto-enables filters for your language
* Real-time DNS query logs with search & filtering
* Security protection — blocks phishing, malware, and malvertising domains
* Dark / Light / System theme with Material 3 dynamic colors
* 7 accent color options + Material You dynamic theming
* Quick Settings tile & home screen widget
* Custom block/allow rules and whitelisting
* Per-app filtering (bypass VPN for selected apps)
* DNS-over-HTTPS (DoH) support with multiple providers
* Auto-update filter lists on schedule (6h / 12h / 24h / 48h)
* Export / Import settings backup
* Auto-reconnect on boot
* Multi-language support (English, Vietnamese, Japanese, Korean, Chinese, Thai, Spanish)
* 100% local — all data stays on your device

---  

## Community

Join our community:  
[![Reddit](https://img.shields.io/badge/Reddit-Join%20Community-orange?logo=reddit)](https://www.reddit.com/r/BlockAds/)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20Chat-blue?logo=telegram)](https://t.me/blockads_android)

## Sponsor

If you enjoy BlockAds, consider supporting the project! Your sponsorship helps us maintain and
improve the app.

[![Sponsor](https://img.shields.io/badge/Sponsor-❤️-red?logo=github-sponsors)](https://github.com/sponsors/pass-with-high-score)

## Build Instructions

### Requirements

* [Android Studio](https://developer.android.com/studio) Ladybug or newer
* JDK 17 or higher
* Android SDK 36 (min SDK 24)
* [Go](https://go.dev/doc/install) 1.21 or higher
* [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/pass-with-high-score/blockads-android.git 
   cd blockads-android 
   ```

2. Initialize gomobile (one-time setup):
   ```bash
   go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20240404231514-09dbf07665ed
   export PATH=$PATH:$(go env GOPATH)/bin
   gomobile init
   ```

3. **(Optional)** Build the Go tunnel AAR/JAR (with Android 15 16KB page size support):
   ```bash
   ./scripts/build_tunnel.sh
   ```
   Or
    ```
   ./gradlew buildGoTunnel
   ```
   *Note: A pre-built version is already included in `app/libs/`.*

4. Open the project in Android Studio

5. Sync Gradle and run the app on a device or emulator

6. Build from command line:
   ```bash
   ./gradlew assembleDebug
   ./gradlew bundleRelease   # requires signing key
   ```

---  

## How It Works

BlockAds routes DNS queries locally either through a VpnService (Vpn Mode) or via iptables redirection (Root Proxy Mode). These queries are matched against loaded filter lists using a memory-efficient Trie data structure. Matching queries are blocked locally, while all other traffic passes through normally — no data leaves your device.

---  

## License

This project is licensed under the **GNU General Public License v3.0**.  
You are free to use, modify, and distribute it under the terms of the license.  
See the full [LICENSE](LICENSE) file for details.

---  

## Credits

* Developed and maintained by [Nguyen Quang Minh](https://github.com/nqmgaming)
* Built with [Jetpack Compose](https://developer.android.com/jetpack/compose), [Koin](https://insert-koin.io/), and [Compose Destinations](https://github.com/raamcosta/compose-destinations)

---  

## Contributing

Pull requests and issue reports are welcome.  
Help us improve BlockAds!

### Help us translate BlockAds

Want to see BlockAds in your language?  
Open an issue or submit a PR with your translations.

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=pass-with-high-score/blockads-android&type=Date)](https://www.star-history.com/#pass-with-high-score/blockads-android&Date)
