# 📋 BlockAds Android — Improvement Plan

> **Mục tiêu**: Nâng cấp app BlockAds Android để hoạt động ổn định hơn, giữ chân người dùng, và tiệm cận chất lượng của AdGuard Android.
>
> **Nguyên tắc**: Ưu tiên **ổn định > UX > tính năng mới**. Ship từng phase nhỏ, đo lường retention sau mỗi release.

---

## 📊 So sánh hiện trạng BlockAds vs AdGuard Android

| Tính năng | BlockAds | AdGuard | Ghi chú |
|-----------|----------|---------|---------|
| DNS filtering (hosts/adblock) | ✅ | ✅ | Tương đương |
| Encrypted DNS (DoH/DoT/DoQ) | ❌ | ✅ | **Thiếu quan trọng** |
| DNS server presets | ❌ (chỉ input thủ công) | ✅ | UX kém |
| Custom user rules | ❌ | ✅ | Power user cần |
| HTTPS filtering | ❌ | ✅ | Phức tạp, ưu tiên thấp |
| Firewall (per-app internet control) | ❌ | ✅ | High value feature |
| Browsing security (phishing/malware) | ❌ | ✅ | Dễ thêm qua filter list |
| Per-app DNS control | ❌ | ✅ | Nâng cao |
| Statistics chi tiết (per-app, per-domain) | ❌ (chỉ tổng) | ✅ | **Thiếu quan trọng** |
| IPv6 support | ❌ | ✅ | Stability issue |
| DNS response customization | ❌ (chỉ NXDOMAIN) | ✅ | Nice to have |
| Auto-update filter lists | ❌ | ✅ | **Thiếu quan trọng** |
| Home screen widget | ❌ | ✅ | Retention feature |
| Blocked domain notification | ❌ | ✅ | Engagement |
| App management screen | ❌ | ✅ | UX improvement |
| Dark/Light/System theme | ✅ | ✅ | Tương đương |
| Quick Settings tile | ✅ | ✅ | Tương đương |
| Export/Import settings | ✅ | ✅ | Tương đương |
| Multi-language (EN/VI) | ✅ | ✅ | Tương đương |

---

## 🗓️ Roadmap theo Phase

### Phase 1 — Ổn định & Nền tảng (Stability First) 🔴 Critical

> **Mục tiêu**: App không crash, VPN luôn hoạt động, DNS resolve nhanh và chính xác.
> **Timeline ước tính**: 2–3 tuần

#### 1.1 IPv6 Support
- **Hiện trạng**: `AdBlockVpnService` chỉ parse IPv4 packets (`protocol == 4`), bỏ qua IPv6 hoàn toàn → DNS leak trên mạng IPv6
- **Cần làm**:
  - Thêm IPv6 address cho TUN interface (ví dụ `fd00::1/128`)
  - Parse IPv6 UDP DNS packets trong `DnsPacketParser`
  - Build IPv6 DNS response packets
  - Route cả IPv4 và IPv6 DNS traffic qua VPN
- **Impact**: Chặn ads triệt để hơn trên mạng IPv6, không bị DNS leak

#### 1.2 VPN Reconnection & Reliability
- **Hiện trạng**: Chỉ có `BootReceiver` để auto-start. Không handle network change, VPN revoke, hoặc service bị kill
- **Cần làm**:
  - Lắng nghe `ConnectivityManager` network callbacks để auto-reconnect khi đổi Wi-Fi/Mobile
  - Handle `onRevoke()` gracefully trong VpnService
  - Implement retry logic với exponential backoff khi VPN setup fail
  - Thêm `FOREGROUND_SERVICE_SPECIAL_USE` cho Android 14+
  - Sử dụng `setAlwaysOn()` hint trong notification
- **Impact**: VPN không bị ngắt giữa chừng, user luôn được bảo vệ

#### 1.3 DNS Timeout & Error Handling
- **Hiện trạng**: Nếu upstream DNS (8.8.8.8) không response, packet bị drop → app/web bị treo
- **Cần làm**:
  - Thêm DNS query timeout (3–5 giây)
  - Fallback DNS server (ví dụ: 1.1.1.1 nếu 8.8.8.8 fail)
  - Return SERVFAIL thay vì drop packet khi timeout
  - Log DNS errors vào database để debug
- **Impact**: Trải nghiệm người dùng mượt hơn, không bị treo khi DNS có vấn đề

#### 1.4 Memory & Battery Optimization
- **Hiện trạng**: Blocklist dùng `ConcurrentHashMap` load toàn bộ vào RAM
- **Cần làm**:
  - Profile memory usage với nhiều filter lists enabled
  - Cân nhắc Bloom filter cho initial check trước khi lookup exact match
  - Tối ưu packet processing loop (giảm allocations)
  - Thêm battery usage monitoring/reporting
  - Test battery drain trên các thiết bị phổ biến
- **Impact**: App chạy nhẹ hơn, ít hao pin, phù hợp thiết bị low-end

#### 1.5 Auto-update Filter Lists
- **Hiện trạng**: User phải bấm "Update All" thủ công
- **Cần làm**:
  - Thêm `WorkManager` periodic task để auto-update filters (mặc định: 24h)
  - Cho user chọn tần suất update (6h / 12h / 24h / 48h / Manual)
  - Chỉ update khi có Wi-Fi (option)
  - Notification khi update xong (silent/normal)
  - Hiển thị "Last updated" rõ ràng hơn trên UI
- **Impact**: Filter lists luôn mới nhất, chặn ads hiệu quả hơn

---

### Phase 2 — DNS Protection Nâng Cao 🟡 High Priority

> **Mục tiêu**: Nâng cấp DNS layer lên ngang AdGuard, hỗ trợ encrypted DNS.
> **Timeline ước tính**: 3–4 tuần

#### 2.1 Encrypted DNS (DoH / DoT)
- **Hiện trạng**: Chỉ hỗ trợ plain DNS (UDP port 53) qua upstream IP
- **Cần làm**:
  - Implement DNS-over-HTTPS (DoH) client sử dụng Ktor
  - Implement DNS-over-TLS (DoT) client sử dụng TLS socket
  - UI để chọn DNS protocol (Plain / DoH / DoT)
  - Validate và test với các provider phổ biến
- **DNS Providers cần hỗ trợ**:
  - Google DoH: `https://dns.google/dns-query`
  - Cloudflare DoH: `https://cloudflare-dns.com/dns-query`
  - AdGuard DoH: `https://dns.adguard-dns.com/dns-query`
  - Quad9 DoH: `https://dns.quad9.net/dns-query`
  - Custom URL input
- **Impact**: Privacy tốt hơn nhiều, ISP không thể theo dõi DNS queries

#### 2.2 DNS Server Presets
- **Hiện trạng**: Chỉ có 1 text field nhập IP thủ công
- **Cần làm**:
  - Tạo danh sách DNS presets với logo, mô tả, tốc độ
  - Nhóm theo category: Standard / Privacy-focused / Family-safe / Custom
  - Danh sách gợi ý:
    | Provider | IP | DoH | Đặc điểm |
    |----------|-----|-----|-----------|
    | Google | 8.8.8.8 | ✅ | Nhanh, phổ biến |
    | Cloudflare | 1.1.1.1 | ✅ | Nhanh nhất |
    | AdGuard DNS | 94.140.14.14 | ✅ | Block ads built-in |
    | Quad9 | 9.9.9.9 | ✅ | Security-focused |
    | OpenDNS | 208.67.222.222 | ❌ | Family option |
    | NextDNS | — | ✅ | Customizable |
  - DNS speed test (ping) để recommend server nhanh nhất
  - Cho phép nhập custom DNS server
- **Impact**: UX tốt hơn nhiều, user không cần biết IP để chọn DNS

#### 2.3 Custom DNS Rules (User Rules)
- **Hiện trạng**: Chỉ có whitelist domain, không có custom block rules
- **Cần làm**:
  - Thêm "Custom Rules" screen cho phép user nhập rules thủ công
  - Hỗ trợ syntax:
    - Block: `||example.com^` hoặc `example.com`
    - Allow: `@@||example.com^`
    - Comment: `! This is a comment`
  - Rules được apply trước filter lists (ưu tiên cao hơn)
  - Import/Export custom rules
  - Gợi ý rules từ DNS log (1-tap block/unblock)
- **Impact**: Power users có thể fine-tune filtering theo ý muốn

#### 2.4 DNS Response Customization
- **Hiện trạng**: Chỉ return NXDOMAIN cho blocked domains
- **Cần làm**:
  - Cho user chọn response type: NXDOMAIN / REFUSED / Custom IP (0.0.0.0)
  - REFUSED thường tương thích tốt hơn với một số app
  - Custom IP hữu ích cho debug
- **Impact**: Tăng khả năng tương thích với các app khác nhau

---

### Phase 3 — UI/UX Cải Thiện 🟢 Medium Priority

> **Mục tiêu**: App đẹp hơn, dễ dùng hơn, hiển thị thông tin hữu ích hơn.
> **Timeline ước tính**: 3–4 tuần

#### 3.1 Dashboard Redesign (Home Screen)
- **Hiện trạng**: Home screen có toggle VPN, stats tổng, chart 24h, recent blocked
- **Cần cải thiện**:
  - **Protection status card**: Hiển thị rõ ràng trạng thái ON/OFF với animation
  - **Quick toggles**: Bật/tắt nhanh các module (DNS Filtering, App Whitelist)
  - **Stats cards**: Hiển thị "Ads blocked today", "Trackers blocked", "DNS queries"
  - **Data saved estimation**: Ước tính data tiết kiệm được (based on avg ad size)
  - **Weekly/Monthly chart**: Thêm option xem chart theo tuần/tháng (không chỉ 24h)
  - **Top blocked domains**: Hiển thị top 10 domain bị block nhiều nhất
  - **Protection uptime**: Hiển thị thời gian app đã bảo vệ liên tục
- **Tham khảo**: AdGuard dashboard với protection status prominently displayed

#### 3.2 Statistics Screen (Mới)
- **Hiện trạng**: Chỉ có basic stats trên Home và DNS logs
- **Cần làm**:
  - Tạo dedicated Statistics tab/screen
  - **Overview tab**: Tổng ads blocked, trackers blocked, DNS queries (all time + today)
  - **Charts**:
    - Hourly chart (24h) — đã có
    - Daily chart (7 ngày)
    - Weekly chart (4 tuần)
    - Monthly chart (12 tháng)
  - **Per-app statistics**: App nào tạo nhiều DNS queries nhất, app nào bị block nhiều nhất
  - **Per-domain statistics**: Domain nào bị block nhiều nhất
  - **Top companies**: Nhóm domains theo company (Google, Facebook, etc.)
  - **Filter effectiveness**: Filter list nào block nhiều nhất
  - **Export stats**: Xuất CSV/PDF cho power users
- **Impact**: User thấy rõ giá trị app mang lại → tăng retention

#### 3.3 Improved DNS Log Screen
- **Hiện trạng**: Có log với search/filter, copy domain, add to whitelist
- **Cần cải thiện**:
  - **Color coding**: Xanh = allowed, Đỏ = blocked, Vàng = whitelisted
  - **Domain info**: Tap vào domain → hiện thông tin chi tiết (IP resolved, response time, which filter blocked it, query type A/AAAA/CNAME)
  - **Quick actions**: Block/Unblock domain trực tiếp từ log (1-tap)
  - **Real-time mode**: Auto-scroll khi có query mới (toggle on/off)
  - **Filter by app**: Hiển thị app nào tạo DNS query đó (cần thêm UID tracking)
  - **Time range filter**: Lọc log theo khoảng thời gian
  - **Bulk actions**: Chọn nhiều domain để block/whitelist cùng lúc
- **Impact**: Debug dễ hơn, user hiểu app đang làm gì

#### 3.4 Better Onboarding Flow
- **Hiện trạng**: 3-step pager (Welcome → VPN Permission → Done)
- **Cần cải thiện**:
  - **Step 1**: Giới thiệu app + privacy promise (animation)
  - **Step 2**: Chọn mức độ bảo vệ (Basic / Standard / Strict) — auto-select filter lists
  - **Step 3**: Chọn DNS server (preset list với recommendation)
  - **Step 4**: VPN permission request (giải thích rõ tại sao cần)
  - **Step 5**: Notification permission (Android 13+)
  - **Step 6**: Battery optimization exclude (để VPN không bị kill)
  - **Completion**: Animation chúc mừng + hiện stats "You're now protected!"
  - **Skip option**: Cho phép skip để dùng default settings
- **Impact**: First-time experience tốt hơn, user hiểu app, ít confusion

#### 3.5 Settings Screen Reorganization
- **Hiện trạng**: Flat list settings khá dài
- **Cần cải thiện**:
  - **Nhóm settings** thành categories với section headers:
    - 🛡️ **Bảo vệ**: DNS server, protocol, auto-reconnect
    - 🎨 **Giao diện**: Theme, language, compact mode
    - 📱 **Ứng dụng**: App whitelist, per-app settings
    - 🌐 **Bộ lọc**: Filter management, auto-update, custom rules
    - 💾 **Dữ liệu**: Export/Import, clear logs, clear stats
    - ℹ️ **Thông tin**: About, changelog, feedback, rate app
  - Mỗi category có icon và description ngắn
  - Search trong settings
- **Impact**: Dễ tìm setting, không bị overwhelm

#### 3.6 App Management Screen (Mới)
- **Hiện trạng**: Chỉ có app whitelist (exclude from VPN)
- **Cần làm**:
  - Tạo dedicated "App Management" screen
  - Hiển thị tất cả installed apps với:
    - App icon, name, package name
    - DNS queries count
    - Blocked count
    - Data usage (nếu có thể track)
  - Per-app options:
    - Route through VPN (on/off)
    - Block all internet access (Firewall — Phase 4)
  - Search và filter apps
  - Sort by: Name / Queries / Blocked / Data usage
  - Highlight "problematic" apps (banking, system apps)
- **Impact**: Quản lý chi tiết từng app, giải quyết compatibility issues

---

### Phase 4 — Tính Năng Mới (New Features) 🔵 Nice to Have

> **Mục tiêu**: Thêm tính năng differentiator để cạnh tranh.
> **Timeline ước tính**: 4–6 tuần

#### 4.1 Firewall (Per-App Internet Control)
- **Mô tả**: Cho phép user chặn internet cho từng app
- **Cần làm**:
  - Extend VPN service để track traffic per-app (sử dụng `VpnService.Builder.addDisallowedApplication()`)
  - UI để toggle Wi-Fi / Mobile Data / All cho từng app
  - Schedule rules (ví dụ: block TikTok 22:00–06:00)
  - Notification khi app bị block truy cập internet
- **Impact**: Feature rất hữu ích cho parents và productivity users

#### 4.2 Browsing Security (Phishing/Malware Protection)
- **Mô tả**: Cảnh báo khi truy cập website nguy hiểm
- **Cần làm**:
  - Thêm malware/phishing filter lists:
    - URLHaus Malicious URL Blocklist
    - PhishTank blocklist
    - Malware Domain List
  - Hiển thị warning khi domain nằm trong security list
  - Tách biệt "blocked because ad" vs "blocked because dangerous"
  - Security stats riêng trên dashboard
- **Impact**: User cảm thấy an toàn hơn, thêm lý do giữ app

#### 4.3 Home Screen Widget
- **Mô tả**: Widget hiển thị stats và toggle nhanh trên home screen
- **Cần làm**:
  - **Small widget (2x1)**: Toggle on/off + blocked count today
  - **Medium widget (4x2)**: Toggle + stats + mini chart
  - **Large widget (4x4)**: Full dashboard mini
  - Sử dụng Glance (Jetpack Compose for widgets)
  - Update widget real-time (hoặc mỗi 15 phút)
- **Impact**: User nhìn thấy app mỗi ngày → tăng engagement & retention

#### 4.4 Notification Improvements
- **Hiện trạng**: Chỉ có foreground notification "VPN is running"
- **Cần cải thiện**:
  - **Persistent notification**: Hiển thị real-time stats (X ads blocked today)
  - **Action buttons**: Pause 1h / Stop / Open app
  - **Daily summary**: Notification cuối ngày "Today we blocked 1,234 ads for you!"
  - **Milestone notifications**: "You've blocked 10,000 ads!" (gamification)
  - **Custom notification channels**: User chọn notification nào muốn nhận
- **Impact**: User aware giá trị app mang lại, gamification tăng retention

#### 4.5 Protection Profiles
- **Mô tả**: Preset cấu hình cho từng use case
- **Cần làm**:
  - **Default**: Chặn ads & trackers cơ bản
  - **Strict**: Chặn tất cả ads, trackers, analytics
  - **Family**: Chặn ads + adult content + gambling
  - **Gaming**: Chặn ads nhưng whitelist game servers
  - **Custom**: User tự tạo profile
  - Quick switch giữa các profiles
  - Schedule profiles (ví dụ: Family mode 18:00–08:00)
- **Impact**: Onboarding nhanh hơn, phù hợp nhiều đối tượng user

#### 4.6 Accessibility & Localization
- **Hiện trạng**: English + Vietnamese
- **Cần làm**:
  - Thêm ngôn ngữ: Japanese, Korean, Chinese, Thai, Spanish
  - Crowdsource translations qua Crowdin/Weblate
  - Accessibility improvements (TalkBack support, content descriptions)
  - Dynamic text sizing
  - High contrast mode
- **Impact**: Mở rộng thị trường, app inclusive hơn

---

### Phase 5 — Advanced & Long-term 🟣 Future

> **Mục tiêu**: Tính năng nâng cao cho power users.
> **Timeline ước tính**: Ongoing

#### 5.1 DNS Cache
- Cache DNS responses locally để giảm latency
- Hiển thị cache hit rate trên statistics
- Clear cache option

#### 5.2 HTTPS Filtering (Advanced)
- Certificate installation flow
- Per-app HTTPS filtering
- Rất phức tạp → cần research kỹ

#### 5.3 Sync Settings Across Devices
- Cloud sync (optional) qua Google Drive hoặc custom server
- Export/Import via QR code

#### 5.4 Parental Controls
- PIN lock để prevent thay đổi settings
- Schedule protection (không cho tắt trong giờ nhất định)
- Activity report cho parents

#### 5.5 Community Features
- Share custom filter lists
- Report false positives
- Request filter additions
- In-app feedback system

---

## 🎯 Ưu tiên tổng quan

| Ưu tiên | Phase | Tính năng | Lý do |
|----------|-------|-----------|-------|
| 🔴 P0 | 1 | IPv6 support | DNS leak = app không hoạt động đúng |
| 🔴 P0 | 1 | VPN reconnection | User mất bảo vệ khi đổi mạng |
| 🔴 P0 | 1 | DNS timeout/fallback | App treo khi DNS fail |
| 🔴 P0 | 1 | Auto-update filters | Filters cũ = không chặn ads mới |
| 🟡 P1 | 2 | Encrypted DNS (DoH/DoT) | Privacy feature quan trọng |
| 🟡 P1 | 2 | DNS server presets | UX improvement lớn |
| 🟡 P1 | 3 | Dashboard redesign | First impression, daily engagement |
| 🟡 P1 | 3 | Statistics screen | User thấy giá trị app |
| 🟢 P2 | 2 | Custom DNS rules | Power user feature |
| 🟢 P2 | 3 | Better onboarding | First-time experience |
| 🟢 P2 | 3 | Improved DNS logs | Debug & transparency |
| 🟢 P2 | 3 | App management | Per-app control |
| 🔵 P3 | 4 | Firewall | Differentiator feature |
| 🔵 P3 | 4 | Widget | Retention & engagement |
| 🔵 P3 | 4 | Notifications | Daily engagement |
| 🔵 P3 | 4 | Protection profiles | Onboarding & use cases |
| 🟣 P4 | 5 | HTTPS filtering | Phức tạp, ít user cần |
| 🟣 P4 | 5 | Parental controls | Niche feature |
| 🟣 P4 | 5 | Cloud sync | Nice to have |

---

## 📐 UI/UX Design Guidelines

### Navigation Structure (Đề xuất mới)

```
Bottom Navigation (5 tabs):
├── 🏠 Home (Dashboard)
│   ├── Protection status (ON/OFF toggle lớn)
│   ├── Quick stats cards
│   ├── Activity chart (24h/7d/30d)
│   └── Top blocked domains
│
├── 📊 Statistics
│   ├── Overview (ads/trackers/queries)
│   ├── Charts (hourly/daily/weekly/monthly)
│   ├── Per-app breakdown
│   └── Per-domain breakdown
│
├── 📋 Activity (DNS Logs)
│   ├── Real-time log feed
│   ├── Search & filters
│   ├── Quick block/unblock
│   └── Detail view per query
│
├── 🛡️ Protection
│   ├── DNS Settings (server, protocol)
│   ├── Filter Lists (manage, update)
│   ├── Custom Rules
│   ├── App Management
│   └── Whitelist (domains)
│
└── ⚙️ Settings
    ├── General (theme, language)
    ├── VPN (auto-reconnect, always-on)
    ├── Notifications
    ├── Backup (export/import)
    └── About & Help
```

### Design Principles
1. **Status luôn rõ ràng**: User nhìn vào là biết app đang bảo vệ hay không
2. **Thông tin có ý nghĩa**: Hiển thị stats theo cách user hiểu được (không chỉ số raw)
3. **Action dễ thực hiện**: 1-tap để block/unblock, toggle on/off
4. **Feedback tức thì**: Animation khi toggle, real-time update stats
5. **Progressive disclosure**: Thông tin cơ bản → tap để xem chi tiết

### Color Palette Additions
- 🟢 `#4CAF50` — Protected / Allowed / Active
- 🔴 `#F44336` — Blocked / Danger / Unprotected
- 🟡 `#FFC107` — Warning / Whitelisted / Paused
- 🔵 `#2196F3` — Info / Link / Action
- ⚪ `#9E9E9E` — Disabled / Inactive

---

## 📏 KPIs & Metrics theo dõi

| Metric | Hiện tại | Target Phase 1 | Target Phase 3 |
|--------|----------|----------------|----------------|
| Day 1 Retention | Chưa đo | 70% | 80% |
| Day 7 Retention | Chưa đo | 50% | 60% |
| Day 30 Retention | Chưa đo | 30% | 45% |
| Avg. daily active time | Chưa đo | 2 min | 5 min |
| VPN uptime % | Chưa đo | 95% | 99% |
| Crash-free rate | Chưa đo | 99% | 99.9% |
| Play Store rating | Chưa có | 4.0+ | 4.5+ |
| Filter update freshness | Manual | < 24h | < 12h |

---

## 🔧 Technical Debt cần xử lý

1. **DnsPacketParser**: Chỉ handle IPv4/UDP, cần refactor để support IPv6
2. **AdBlockVpnService**: File lớn, cần tách thành modules (PacketRouter, DnsResolver, BlocklistMatcher)
3. **FilterListRepository**: Cần thêm caching layer tốt hơn (ETag support, conditional download)
4. **Database migrations**: Cần plan migration strategy khi thêm tables mới (per-app stats, custom rules)
5. **Error handling**: Cần centralized error handling và crash reporting (Firebase Crashlytics hoặc Sentry)
6. **Testing**: Cần thêm unit tests cho DNS parsing, filter matching, VPN packet processing
7. **CI/CD**: Thêm automated UI tests, performance tests, memory leak detection

---

## 📝 Ghi chú

- Plan này được tạo dựa trên phân tích codebase hiện tại và so sánh với AdGuard Android
- Thứ tự ưu tiên có thể thay đổi dựa trên user feedback và analytics
- Mỗi phase nên có release riêng để đo lường impact
- Nên set up analytics (Firebase Analytics hoặc tương đương privacy-friendly) trước khi bắt đầu Phase 1 để có baseline metrics
