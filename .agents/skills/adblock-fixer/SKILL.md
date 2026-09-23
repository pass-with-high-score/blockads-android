---
name: adblock-fixer
description: Diagnose and defeat website ads, anti-adblock modals, popunders, and floating video ads in the in-app browser. Use when the user says "check web hiện tại", "fix ad", "chặn quảng cáo trang này", "bị anti-adblock", or asks to remove ads from a specific website.
---

# Browser AdBlock & Anti-AdBlock Fixer

Skill chuyên dụng để chẩn đoán, loại bỏ quảng cáo, vượt qua các tường lửa Anti-AdBlock (AdBlock Detected modal, lock scroll), và chặn quảng cáo dạng video/popunder trong trình duyệt In-App Browser của BlockAds Android.

---

## 1. Quy trình Chẩn đoán (Diagnosis)

### Bước 1: Kết nối DevTools & Chẩn đoán bằng Text (Tiết kiệm Token)
Khi người dùng báo "check trang này" hoặc "check web hiện tại":
1. Tìm PID và chuyển tiếp cổng WebView DevTools:
   ```bash
   APP_PID=$(adb shell pidof app.pwhs.blockads)
   adb forward tcp:9222 localabstract:webview_devtools_remote_${APP_PID}
   curl -s http://127.0.0.1:9222/json
   ```
2. **Nguyên tắc tiết kiệm token (Smart Work)**:
   - **Ưu tiên 100% text qua DevTools**: Chạy script inspect DOM qua DevTools WebSocket (`.agent/skills/adblock-fixer/scripts/inspect_page.mjs` hoặc node one-liner) để lấy danh sách class, id, overlay, scripts. Việc này chỉ tốn vài chục token text, thay vì 1.000+ token cho mỗi bức ảnh.
   - **Không tự động chụp ảnh**: Bỏ qua bước chụp ảnh (`screencap`) và đọc ảnh trong turn chat, trừ khi người dùng chủ động yêu cầu xem ảnh. Để người dùng tự verify trực tiếp trên thiết bị thật.


### Bước 2: Phân tích DOM & Cơ chế Quảng cáo
Sử dụng Node.js hoặc DevTools WebSocket để quét:
- **Anti-AdBlock Modals & Overlays**: Tìm các div cố định toàn màn hình (`position: fixed`, `z-index: 99999+`, `.adblock-overlay`, `#siteNotice`, v.v.) và kiểm tra xem `document.body.style.overflow` có bị khóa `hidden` không.
- **Bait Detection (Mồi phát hiện)**: Kiểm tra các class mồi quen thuộc (`adsbox`, `pub_300x250`, `ad-placement`, `banner_ad`, `adsbygoogle`). Xác định xem trang đo `offsetHeight === 0` hay dùng `getComputedStyle(bait).display === 'none'`.
- **Script Testing**: Kiểm tra xem trang có thử nạp các file script kiểm tra như `pagead2.googlesyndication.com`, `/ads.js`, `/banner-ads.js`, `/no-adblock-access/detector.js` hay không.
- **Floating Ads / Popunders**: Tìm các container video trôi nổi (`#vid-sld-cont`, `[data-shb]`, `aclib`, `inpagepush`, adult video ads từ `cathaytrash`, `acscdn`, `chatmate`).

---

## 2. Chiến lược Sửa lỗi Đa tầng (Layered Defense)

Để xử lý triệt để mà không phá vỡ giao diện hay chức năng của trang web, luôn áp dụng mô hình 4 tầng:

### Tầng 1: Network Blocking & Mock Surrogates
- **Tập tin**:
  - `app/src/main/java/app/pwhs/blockads/ui/browser/rules/BrowserRuleDefaults.kt`
  - `rules/browser_rules.json`
  - `app/src/main/java/app/pwhs/blockads/ui/browser/interceptor/BrowserAdBlocker.kt`
- **Quy tắc**:
  - Thêm domain quảng cáo/tracker mới vào `AD_HOST_SUFFIXES` và `adDomains`.
  - Thêm đường dẫn script quảng cáo vào `AD_PATH_PATTERNS`.
  - **Quan trọng (Surrogates)**: Với các script được trang web dùng để test anti-adblock (ví dụ `/ads.js`, `/banner-ads.js`, `adsbygoogle.js`, `inplayer-adx`), **không** chặn trả lời `ERR_BLOCKED_BY_CLIENT` vì sẽ làm kích hoạt `onerror`. Thay vào đó, trả về **Surrogate Response HTTP 200 (Empty JS hoặc Mock Stub)** trong `BrowserAdBlocker.getSurrogateResponse()` để sự kiện `onload` được gọi thành công.

### Tầng 2: Scriptlets & Anti-AdBlock Defuser
- **Tập tin**: `app/src/main/assets/browser/adguard_scriptlets.js`
- **Quy tắc**:
  - **Hàm stub**: Tạo no-op stub cho các hàm phát hiện quảng cáo (ví dụ `window.showAdblockMessage = function() {}`, `window.aclib = { runPop: ..., runInPagePush: ... }`).
  - **Geometry Spoofing**: Proxy `window.getComputedStyle` và `HTMLElement.prototype.offsetHeight` đối với các phần tử bait (`adsbox`, `pub_300x250`, `banner_ad`) để luôn trả về `display: block`, `visibility: visible`, `offsetHeight: 250` khi trang web kiểm tra kích thước.
  - **MutationObserver & Purge**: Trong hàm `purgeAdArtifacts()`, bổ sung logic tìm và gỡ bỏ ngay các modal che phủ màn hình (`#siteNotice`, `#ad-popup`, `div[data-shb]`), đồng thời khôi phục lại `document.body.style.overflow = ''`.

### Tầng 3: Cosmetic CSS Filtering
- **Tập tin**: `app/src/main/assets/browser/adblock_cosmetic.css`
- **Quy tắc**:
  - Ẩn triệt để các banner, popup, và container quảng cáo:
    ```css
    #siteNotice,
    #ad-popup,
    .ad-popup,
    .ad-slot,
    .ad-slot-wrap,
    #vid-sld-cont,
    [data-shb] {
        display: none !important;
        visibility: hidden !important;
        height: 0 !important;
        min-height: 0 !important;
        margin: 0 !important;
        padding: 0 !important;
        opacity: 0 !important;
        pointer-events: none !important;
    }
    ```
  - Mở khóa cuộn trang nếu trang web cố tình thêm thuộc tính lock overflow:
    ```css
    body:has(#siteNotice),
    body:has(.adblock-overlay) {
        overflow: auto !important;
    }
    ```

### Tầng 4: Tăng Version Rules
- Mỗi lần cập nhật bộ rule, **bắt buộc** tăng `const val INITIAL_VERSION` trong `BrowserRuleDefaults.kt` và `"version"` trong `rules/browser_rules.json` (ví dụ từ `4L` lên `5L`).
- Điều này đảm bảo `BrowserRuleStorage` tự động dọn sạch cache cũ trên máy người dùng và nạp ngay bộ quy tắc mới nhất từ bundled assets.

---

## 3. Kiểm tra & Triển khai

1. **Kiểm tra giới hạn số dòng (File Length Constraint)**:
   - Tất cả các file sửa đổi (`BrowserRuleDefaults.kt`, `BrowserAdBlocker.kt`, `adblock_cosmetic.css`, `adguard_scriptlets.js`) phải luôn **<= 500 dòng**.
2. **Build APK**:
   ```bash
   ./gradlew :app:assembleDebug -Pabi=arm64-v8a
   ```
3. **Cài đặt lên thiết bị**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   ```
4. **Xác minh**:
   - Reload tab hoặc điều hướng tới trang web trên WebView.
   - Chụp ảnh màn hình kiểm tra: Modal đã biến mất, không còn khoảng trống quảng cáo vỡ layout, cuộn trang mượt mà, video/nội dung đọc bình thường.
5. **Git Commit**:
   - Tuân thủ Conventional Commits:
     ```bash
     git add <files>
     git commit -m "feat(browser): defeat anti-adblock overlay and hide ad slots on <Website>"
     git push origin main
     ```
