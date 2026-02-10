---
description: Chiến lược marketing toàn diện để đẩy app BlockAds thành công lên Google Play Store
---

# 🚀 Marketing App BlockAds lên Google Play Store

## Tổng quan

App **BlockAds** (`app.pwhs.blockads`) là ứng dụng chặn quảng cáo Android dùng công nghệ VPN-based DNS filtering. App được xây dựng với Jetpack Compose, hỗ trợ nhiều filter list, DNS logging, và auto-start khi boot.

---

## PHẦN 1: CHUẨN BỊ TRƯỚC KHI PUBLISH

### 1.1. Tuân thủ chính sách Google Play

> [!CAUTION]
> VPN apps chịu kiểm duyệt nghiêm ngặt hơn trên Play Store. Phải tuân thủ 100% chính sách.

**Yêu cầu bắt buộc cho VPN apps:**

- [ ] **Khai báo VPN trong AndroidManifest** ✅ (đã có `VpnService`)
- [ ] **Không thu thập dữ liệu người dùng** qua VPN tunnel
- [ ] **Privacy Policy** bắt buộc – phải host trên web (ví dụ: GitHub Pages)
- [ ] **Data Safety form** trên Play Console – khai báo chính xác dữ liệu thu thập
- [ ] **Content Rating Questionnaire** – trả lời đầy đủ
- [ ] **Target audience** – KHÔNG target trẻ em (VPN apps không được phép)
- [ ] **Khuyến nghị**: Thêm trang web landing page cho app

**Checklist chính sách quan trọng:**

```
✅ App chỉ chặn quảng cáo, KHÔNG thu thập/gửi dữ liệu người dùng
✅ DNS queries chỉ lưu local (Room database), không gửi lên server
✅ VPN chỉ route DNS traffic (10.0.0.1/32), không route toàn bộ traffic
✅ Có nút stop rõ ràng trong notification
✅ Không bypass security features của hệ thống
```

### 1.2. Tạo Privacy Policy

Tạo Privacy Policy và host lên GitHub Pages hoặc website riêng:

```markdown
# Privacy Policy – BlockAds

Last updated: [DATE]

## Data We Collect
- **DNS Query Logs**: Stored locally on your device only. We do NOT transmit
  any browsing data to external servers.
- **Filter Lists**: Downloaded from public sources. No personal data is sent.

## VPN Service
- BlockAds uses Android VPN Service solely for DNS-based ad blocking.
- Only DNS traffic is routed through the local VPN tunnel.
- No internet traffic is intercepted, logged, or transmitted.

## Third-Party Services
- We do NOT use third-party analytics, advertising, or tracking SDKs.

## Data Retention
- All data is stored locally and can be cleared from Settings > Clear All Logs.

## Contact
- Email: [YOUR_EMAIL]
```

### 1.3. Production Build Checklist

// turbo-all

```bash
# 1. Bật minify và R8 trong build.gradle.kts
# Sửa isMinifyEnabled = true trong release block

# 2. Tạo signing key (chỉ làm 1 lần)
keytool -genkey -v -keystore blockads-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias blockads

# 3. Build release APK/AAB
./gradlew bundleRelease

# 4. Test release build trên thiết bị thật
./gradlew installRelease
```

**Cần làm trong `build.gradle.kts`:**

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true   // ← BẬT
        isShrinkResources = true // ← THÊM
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
}
```

---

## PHẦN 2: TỐI ƯU PLAY STORE LISTING (ASO)

### 2.1. App Name & Metadata

| Trường | Giá trị đề xuất |
|--------|-----------------|
| **App Name** | `BlockAds – Chặn Quảng Cáo & Bảo Vệ Quyền Riêng Tư` |
| **App Name (EN)** | `BlockAds – Ad Blocker & Privacy Shield` |
| **Package** | `app.pwhs.blockads` |
| **Category** | Tools |
| **Tags** | ad blocker, privacy, vpn, dns, no ads |

### 2.2. Short Description (80 ký tự)

**Tiếng Việt:**
> Chặn quảng cáo, bảo vệ quyền riêng tư. Miễn phí, không root, dễ dùng!

**English:**
> Block ads system-wide. No root needed. Protect your privacy effortlessly!

### 2.3. Full Description (4000 ký tự)

```
🛡️ BlockAds – Chặn Quảng Cáo Thông Minh

Bạn mệt mỏi với quảng cáo phiền phức trên điện thoại? BlockAds giúp bạn
chặn quảng cáo trên TOÀN BỘ ứng dụng và trình duyệt chỉ với MỘT nút bấm!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✨ TÍNH NĂNG NỔI BẬT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🚫 CHẶN QUẢNG CÁO TOÀN HỆ THỐNG
• Chặn quảng cáo trong mọi ứng dụng và trình duyệt
• Sử dụng công nghệ DNS filtering thông minh
• Không cần root thiết bị

🔒 BẢO VỆ QUYỀN RIÊNG TƯ
• Chặn tracker và phần mềm theo dõi
• Mọi dữ liệu được lưu trữ cục bộ trên thiết bị
• Không thu thập thông tin cá nhân

📊 THỐNG KÊ CHI TIẾT
• Xem số lượng quảng cáo đã chặn
• Tỷ lệ chặn theo thời gian thực
• Nhật ký DNS chi tiết

⚙️ TÙY CHỈNH LINH HOẠT
• Nhiều bộ lọc quảng cáo có sẵn (ABPVN, AdGuard, EasyList...)
• Thêm bộ lọc tùy chỉnh theo URL
• Tự chọn DNS server (Google, Cloudflare, custom)
• Tự động kết nối lại khi khởi động

🔋 TIẾT KIỆM PIN & DATA
• Chỉ lọc DNS, không ảnh hưởng hiệu suất
• Giảm tải dữ liệu quảng cáo không cần thiết
• Hoạt động nhẹ nhàng trong nền

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📱 CÁCH SỬ DỤNG
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1️⃣ Mở BlockAds
2️⃣ Nhấn nút nguồn để bật chặn quảng cáo
3️⃣ Cho phép kết nối VPN
4️⃣ Tận hưởng internet không quảng cáo! 🎉

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🤔 CÂU HỎI THƯỜNG GẶP
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

❓ BlockAds có an toàn không?
✅ Hoàn toàn an toàn! App chỉ lọc DNS để chặn quảng cáo, không can thiệp
   vào dữ liệu cá nhân của bạn.

❓ Tại sao cần quyền VPN?
✅ BlockAds sử dụng VPN cục bộ để lọc DNS. Không có dữ liệu nào được gửi
   ra server bên ngoài.

❓ App có miễn phí không?
✅ Hoàn toàn miễn phí, không có quảng cáo trong app!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📧 Liên hệ: [YOUR_EMAIL]
🌐 Website: [YOUR_WEBSITE]
```

### 2.4. Screenshots (Rất quan trọng!)

Cần tạo **ít nhất 4 screenshots**, khuyến khích **8 screenshots**:

| # | Nội dung | Mô tả |
|---|----------|-------|
| 1 | **Home – Protected** | Nút nguồn xanh lá, "Protected", stats hiện số liệu |
| 2 | **Home – Unprotected** | Nút nguồn đỏ, "Unprotected", hướng dẫn bật |
| 3 | **Stats Dashboard** | Hiển thị Total Queries, Blocked, Block Rate |
| 4 | **Settings** | Filter Lists, DNS config, Auto-reconnect |
| 5 | **Filter Lists** | Nhiều bộ lọc bật/tắt |
| 6 | **DNS Logs** | Chi tiết queries bị chặn/cho phép |
| 7 | **Add Custom Filter** | Dialog thêm filter tùy chỉnh |
| 8 | **Notification** | Notification "VPN đang hoạt động" |

**Kích thước screenshots:**
- Phone: 1080 x 1920px (hoặc 1440 x 2560px)
- Format: PNG hoặc JPEG

**Tips chụp screenshot đẹp:**
1. Dùng Android Studio Emulator Pixel 7 Pro
2. Đặt dark mode (app đã có dark theme)
3. Tạo dữ liệu demo (queries, blocked count)
4. Thêm frame mockup bằng tool như [Previewed](https://previewed.app/) hoặc [AppMockUp](https://app-mockup.com/)
5. Thêm text overlay mô tả tính năng (bằng Figma/Canva)

### 2.5. Feature Graphic (1024 x 500px)

Tạo banner feature graphic với:
- Logo app ở giữa
- Tagline: "Chặn quảng cáo. Bảo vệ riêng tư."
- Gradient background (dark theme phù hợp với app)
- Màu neon green (#00E676) làm accent

### 2.6. App Icon

Checklist icon:
- [ ] Icon tròn (adaptive icon) đã có trong `ic_launcher`
- [ ] Icon 512x512px cho Play Store listing
- [ ] Icon phải rõ ràng, nhận diện được ở kích thước nhỏ
- [ ] Nên dùng biểu tượng Shield + Block để thể hiện tính năng

---

## PHẦN 3: CHIẾN LƯỢC RA MẮT

### 3.1. Pre-launch Checklist

```
Phase 1 – Trước 2 tuần:
├── [ ] Hoàn thiện tất cả tính năng
├── [ ] Fix tất cả bugs đã biết
├── [ ] Test trên ≥3 thiết bị/version Android khác nhau
├── [ ] Viết Privacy Policy & host lên web
├── [ ] Tạo email support riêng
└── [ ] Chuẩn bị tài khoản Google Play Console ($25 một lần)

Phase 2 – Trước 1 tuần:
├── [ ] Build release AAB (Android App Bundle)
├── [ ] Tạo tất cả screenshots + feature graphic
├── [ ] Viết app description (VI + EN)
├── [ ] Điền Data Safety form
├── [ ] Điền Content Rating questionnaire
└── [ ] Upload lên Internal Testing track

Phase 3 – Ra mắt:
├── [ ] Promote từ Internal → Closed Testing (mời 20+ testers)
├── [ ] Thu thập feedback từ testers
├── [ ] Fix issues từ feedback
├── [ ] Promote lên Open Testing hoặc Production
└── [ ] Submit for review
```

### 3.2. Kênh Marketing

#### 🇻🇳 Kênh Việt Nam (Ưu tiên)

| Kênh | Hành động | Chi phí |
|------|-----------|---------|
| **Tinhte.vn** | Đăng bài review app, mục Ứng dụng | Miễn phí |
| **Voz.vn** | Chia sẻ ở mục Phần Mềm & Game | Miễn phí |
| **Facebook Groups** | Đăng trong nhóm Android VN, Thủ thuật Android | Miễn phí |
| **YouTube VN** | Liên hệ reviewer tech VN (Tinh tế, Schannel...) | Có thể có phí |
| **Zalo Groups** | Chia sẻ trong nhóm công nghệ | Miễn phí |
| **Reddit r/Vietnam** | Post giới thiệu (nếu nhiều traffic) | Miễn phí |

#### 🌍 Kênh Quốc Tế

| Kênh | Hành động | Chi phí |
|------|-----------|---------|
| **Reddit** | r/androidapps, r/privacy, r/pihole | Miễn phí |
| **Product Hunt** | Launch app, chuẩn bị assets | Miễn phí |
| **XDA Developers** | Post trong App Development forum | Miễn phí |
| **AlternativeTo** | Đăng ký là alternative cho AdGuard/Blokada | Miễn phí |
| **GitHub** | Open source (nếu muốn), tăng trust | Miễn phí |
| **Twitter/X** | Hashtags: #adblock #android #privacy | Miễn phí |
| **Hacker News** | Show HN post | Miễn phí |

### 3.3. Chiến Lược Differentiation (Điểm khác biệt)

So sánh với đối thủ để highlight:

| Tính năng | BlockAds | AdGuard | Blokada | DNS66 |
|-----------|----------|---------|---------|-------|
| Miễn phí | ✅ | ❌ (Pro) | ✅ (giới hạn) | ✅ |
| Multi filter lists | ✅ | ✅ | ✅ | ✅ |
| DNS Logging | ✅ | ✅ | ❌ | ❌ |
| Custom DNS | ✅ | ✅ | ✅ | ✅ |
| No root | ✅ | ✅ | ✅ | ✅ |
| Open source | ❓ | ❌ | ✅ | ✅ |
| ABPVN (VN) | ✅ Mặc định | Phải thêm | ❌ | ❌ |
| Lightweight | ✅ | ❌ (nặng) | ✅ | ✅ |
| Modern UI | ✅ Material 3 | ✅ | ❌ | ❌ |

**Unique Selling Points (USP):**
1. 🇻🇳 **Tối ưu cho người Việt** – ABPVN filter mặc định, chặn quảng cáo VN hiệu quả
2. 🎨 **Giao diện hiện đại** – Material 3 / Jetpack Compose, dark mode đẹp
3. 🪶 **Siêu nhẹ** – Chỉ route DNS traffic, pin yếu
4. 📊 **Thống kê chi tiết** – Xem chính xác gì bị chặn
5. 🆓 **Hoàn toàn miễn phí** – Không quảng cáo, không in-app purchase

---

## PHẦN 4: SAU KHI PUBLISH

### 4.1. Theo dõi Performance

- **Google Play Console** → Statistics → theo dõi Install, Uninstall, Ratings
- **Android Vitals** → theo dõi crash rate, ANR rate
- Mục tiêu: **< 1% crash rate**, **> 4.0 ⭐ rating**

### 4.2. Respond Reviews

Trả lời MỌI review, đặc biệt review tiêu cực:
- Cảm ơn feedback
- Giải thích cách fix nếu là bug
- Hứa update trong version tương lai

### 4.3. Update Roadmap

Lên kế hoạch update thường xuyên (tối thiểu mỗi tháng):

```
v1.1 – Tuần 2-3 sau launch:
├── Bug fixes từ feedback
├── Thêm filter lists phổ biến
└── Cải thiện UX

v1.2 – Tháng 2:
├── Whitelist apps (cho phép quảng cáo cho app cụ thể)
├── Widget on/off nhanh
└── Export/import settings

v1.3 – Tháng 3:
├── DoH (DNS over HTTPS) support
├── Scheduled blocking (lên lịch chặn)
└── Battery usage optimization

v2.0 – Tháng 4-5:
├── Firewall mode (chặn internet cho từng app)
├── Pro features (nếu muốn monetize)
└── Tablet UI optimization
```

### 4.4. ASO Optimization Liên Tục

- **A/B test** screenshots và descriptions trên Play Console
- Theo dõi **search keywords** đang mang lại traffic
- Update screenshots khi có tính năng mới
- Localize cho nhiều ngôn ngữ (EN, VI, JA, KO...)

---

## PHẦN 5: QUICK COMMANDS

### Tạo release build

```bash
# Clean và build AAB
./gradlew clean bundleRelease

# File output tại:
# app/build/outputs/bundle/release/app-release.aab
```

### Tạo APK cho testing

```bash
./gradlew assembleRelease

# File output tại:
# app/build/outputs/apk/release/app-release.apk
```

### Test trên nhiều devices

```bash
# Chạy test
./gradlew connectedAndroidTest

# Lint check
./gradlew lint
```

---

## PHẦN 6: PLAY CONSOLE SUBMISSION GUIDE

### Bước từng bước trên Google Play Console:

1. **Tạo app** → Android app → Free
2. **Store Listing** → Điền title, description, screenshots
3. **Content Rating** → Trả lời questionnaire
4. **Pricing & Distribution** → Free, chọn countries
5. **Data Safety** → Khai báo:
   - ❌ Không thu thập device ID
   - ❌ Không thu thập location
   - ❌ Không share dữ liệu với bên thứ 3
   - ✅ Dữ liệu DNS logs lưu local (user-generated content)
   - ✅ Dữ liệu có thể xóa bởi user
6. **App Access** → Không cần login/tài khoản
7. **Ads** → App KHÔNG chứa quảng cáo
8. **Target Audience** → 18+ (VPN apps)
9. **Upload AAB** → Release track → Production
10. **Submit for review**

> [!IMPORTANT]
> Review VPN apps thường mất **3-7 ngày làm việc**. Chuẩn bị trước câu trả lời
> cho các câu hỏi từ review team về VPN usage.

---

## PHẦN 7: POTENTIAL REJECTION REASONS & FIXES

| Lý do bị reject | Cách fix |
|-----------------|----------|
| Thiếu Privacy Policy | Host privacy policy lên web, thêm link trong app và listing |
| VPN unclear purpose | Thêm mô tả rõ ràng "local DNS filtering only" |
| Data Safety không khớp | Kiểm tra lại form, đảm bảo consistent với code |
| Missing foreground notification | ✅ Đã có notification khi VPN chạy |
| Target children | Đặt target 18+, không target trẻ em |
| Misleading claims | Không claim "100% block all ads", dùng "reduce ads" |

---

*Skill này được tạo cho app `app.pwhs.blockads` – BlockAds Ad Blocker*
