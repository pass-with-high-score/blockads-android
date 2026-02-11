# BlockAds

Block ads system-wide on Android using local VPN-based DNS filtering. No root needed. No data collection.

## Features

- System-wide ad blocking via DNS filtering
- All data stored locally, nothing sent to servers
- Dark / Light / System theme (Material 3)
- Real-time stats and DNS query logs
- Multiple filter lists (ABPVN, AdGuard, EasyList, custom URLs)
- Quick Settings tile, home screen widget
- Export / Import settings
- Auto-reconnect on boot
- Free and open source

## Build

Requires Android Studio Ladybug+, JDK 17, Android SDK 36 (min SDK 24).

```bash
./gradlew assembleDebug
./gradlew bundleRelease   # requires signing key
```

## How It Works

BlockAds creates a local VPN on your device. DNS queries are routed through it and matched against filter lists. Matching queries are blocked locally. All other traffic passes through normally.

## License

GPL-3.0 — see [LICENSE](LICENSE).
