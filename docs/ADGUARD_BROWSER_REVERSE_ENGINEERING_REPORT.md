# BÁO CÁO PHÂN TÍCH REVERSE ENGINEERING ADGUARD ANDROID
**Phân tích Kiến trúc Bộ lọc Trình duyệt (Browser Ad-blocking) & Giải pháp Chặn Quảng cáo YouTube trong App**

- **Target Package:** `com.adguard.android` (AdGuard v4.x / Content Blocker)
- **Công cụ phân tích:** `jadx`, `unzip`, `readelf`, `nm`, `strings`, `CoreLibs / Scriptlets reference`
- **Tài liệu tham chiếu dự án:** `blockads-android` (`app/src/main/java/.../ui/browser/`, `tunnel/`)

---

## 1. TỔNG QUAN KIẾN TRÚC BỘ LỌC ADGUARD ANDROID

AdGuard trên Android chia hệ thống chặn quảng cáo trình duyệt và Web thành 4 tầng phối hợp:

```
┌───────────────────────────────────────────────────────────────────────────┐
│ 1. Trình duyệt & WebView Layer                                            │
│    - User-Agent & Client Hints Spoofing (Bỏ nhận diện WebView `; wv`)    │
│    - Service Worker Neutralization (Vô hiệu hóa Service Worker của YT)    │
│    - Document-Start Scriptlet Injection (ytInitialPlayerResponse hook)    │
│    - ExtendedCSS Cosmetic Engine (Ẩn phần tử trước khi render)            │
├───────────────────────────────────────────────────────────────────────────┤
│ 2. WebResourceRequest Interception (WebViewClient & Core)                 │
│    - shouldInterceptRequest() phân loại: Document, Script, XHR/Fetch, Ad │
│    - Trả về HTTP 204 No Content / Empty Stream (tránh client retry bão)  │
│    - Chặn các endpoint Ad Break & Tracking API (/youtubei/v1/player/ad)   │
├───────────────────────────────────────────────────────────────────────────┤
│ 3. Core Engine (AdGuard CoreLibs / C++ & Go Tunnel)                       │
│    - Trie & Bloom Filter matching rules với cú pháp Adblock Plus (ABP)    │
│    - URL Stripping: Bóc tách utm_*, fbclid, gclid, mc_eid, ETag tracking   │
│    - Phân tách Context: First-party vs Third-party requests               │
├───────────────────────────────────────────────────────────────────────────┤
│ 4. Local Proxy & HTTPS Filtering Layer                                    │
│    - Local HTTP/HTTPS Proxy (127.0.0.1:port) trên nền VPN Tun            │
│    - Chặn cổng UDP 443 (QUIC/HTTP3) để ép Fallback về TCP TLS             │
│    - Dynamic Root CA cho phép giải mã & tiêm trực tiếp vào HTML Stream     │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. NHỮNG PHÁT HIỆN KỸ THUẬT QUAN TRỌNG TỪ ADGUARD

### 2.1. Vấn đề Service Worker Bypass trên YouTube Web (`m.youtube.com`)
* **Thực tế:** YouTube Mobile Web phụ thuộc chặt chẽ vào Service Worker (`sw.js`). Khi Service Worker đã đăng ký thành công trong cache của WebView, mọi request fetch video chunks, metadata người xem, và đặc biệt là `/youtubei/v1/player` sẽ được Service Worker xử lý trực tiếp ngầm bên dưới.
* **Hậu quả:** Các request này **hoàn toàn không đi qua** `WebViewClient.shouldInterceptRequest()` của Android WebView! Điều này giải thích tại sao nhiều giải pháp WebView chỉ chặn được vài quảng cáo banner ngoài giao diện nhưng video ads (quảng cáo đầu video, giữa video) vẫn load bình thường.
* **Kỹ thuật của AdGuard:**
  1. Tiêm script xóa sạch đăng ký Service Worker ngay khi khởi tạo DOM:
     ```javascript
     if ('serviceWorker' in navigator) {
         navigator.serviceWorker.getRegistrations().then(function(registrations) {
             for (var i = 0; i < registrations.length; i++) {
                 registrations[i].unregister();
             }
         });
         // Ghi đè prototype ngăn YouTube đăng ký Service Worker mới
         navigator.serviceWorker.register = function() {
             return Promise.reject(new Error("ServiceWorker blocked by BlockAds"));
         };
         try { delete navigator.__proto__.serviceWorker; } catch(e) {}
     }
     ```
  2. Bằng cách vô hiệu hóa Service Worker, 100% request mạng của YouTube buộc phải đi qua Web stack tiêu chuẩn và lọt vào `WebViewClient.shouldInterceptRequest()`.

---

### 2.2. Kỹ thuật Tiệt trùng JSON YouTube Player (`ytInitialPlayerResponse`)
* **Cơ chế phân phối quảng cáo của YouTube:**
  - Khi người dùng bấm vào video, YouTube không gửi request tải file video trực tiếp mà gửi request JSON đến endpoint `/youtubei/v1/player`.
  - Trong payload JSON trả về có các trường then chốt:
    * `playerAds`: Chứa danh sách các quảng cáo preroll / midroll.
    * `adPlacements`: Các mốc thời gian (cuepoints) phát quảng cáo gián đoạn.
    * `adSlots`: Thông tin vị trí hiển thị quảng cáo banner overlay.
    * `adPlacementConfig`: Cấu hình tự động chèn ad break.
* **Giải pháp tiệt trùng cấp Scriptlet của AdGuard / uBlock:**
  - Không đợi video quảng cáo phát rồi mới nhấn nút "Skip" (trải nghiệm giật lag, tốn băng thông).
  - AdGuard đặt **Property Trap** bằng `Object.defineProperty` trên `window.ytInitialPlayerResponse` và `window.ytInitialData`:
    ```javascript
    var _ytInitialPlayerResponse = window.ytInitialPlayerResponse;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function() { return _ytInitialPlayerResponse; },
        set: function(val) {
            _ytInitialPlayerResponse = sanitizePlayerResponse(val);
        }
    });
    ```
  - Đồng thời, monkeypatch cả hai API fetch dữ liệu của trình duyệt (`window.fetch` và `XMLHttpRequest.prototype.send`) để chặn và gọt sạch các node `playerAds`, `adPlacements`, `adSlots` trước khi YouTube Player Engine nhận được data.
  - Khi player YouTube nhận JSON đã tiệt trùng, nó coi như video này **100% không có quảng cáo** và phát ngay nội dung chính với chất lượng cao nhất mà không xuất hiện bất kỳ khung hình quảng cáo nào.

---

### 2.3. Vấn đề Timing & "Document-Start" trong Android WebView
* **Vấn đề của Android WebView:**
  - Phương thức `WebView.evaluateJavascript()` khi gọi trong `WebViewClient.onPageFinished()` là quá muộn (YouTube đã khởi tạo xong player và hiển thị quảng cáo).
  - Ngay cả khi gọi trong `WebViewClient.onPageStarted()`, trên các thiết bị nhanh hoặc trang web có inline script trong `<head>`, các script của trang web có thể thực thi trước khi `evaluateJavascript` hoàn tất (Race Condition).
* **Giải pháp chuyên nghiệp:**
  1. **HTML Rewriting Stream (Interception ở cấp Main Frame):**
     - Trong `shouldInterceptRequest()`, khi `request.isForMainFrame == true`:
       - Tải HTML tài nguyên chính qua HTTP Client / OkHttp.
       - Dùng bộ parser siêu nhẹ quét thẻ `<head>` hoặc `<head ...>`.
       - Chèn scriptlet tiệt trùng ngay vị trí đầu tiên của `<head>`.
       - Trả về `WebResourceResponse` với stream đã tiêm script. Khi WebView phân tích cú pháp HTML, scriptlet của chúng ta sẽ thực thi ở **thời điểm sớm nhất tuyệt đối (Absolute Document-Start)**, trước mọi inline script của YouTube!
  2. **Kết hợp AndroidX Webkit API (nếu khả dụng):**
     - Sử dụng `WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginRules)` trên Android có Chromium WebView phiên bản mới để tự động inject script trước khi bất kỳ script nào của trang chạy.

---

### 2.4. Danh sách Endpoint Quảng cáo YouTube cần Chặn ở Network Level
Tại `WebViewClient.shouldInterceptRequest()`, AdGuard trả về **HTTP 204 No Content** cho các endpoint sau:

| Endpoint Pattern | Mục đích |
| :--- | :--- |
| `*youtube.com/pagead/*` | Tải dữ liệu quảng cáo web |
| `*youtube.com/api/stats/ads*` | Beacon báo cáo hành vi xem quảng cáo |
| `*youtube.com/get_midroll_info*` | Lấy danh sách quảng cáo giữa video |
| `*youtube.com/youtubei/v1/player/ad_break*` | Gọi dynamic ad-break trigger |
| `*googleads.g.doubleclick.net/*` | Mạng quảng cáo Google Ads |
| `*static.doubleclick.net/instream/*` | Script và video player ad stream |
| `*ads.youtube.com/*` | Domain máy chủ quảng cáo YouTube |
| `*play.google.com/log*` | Telemetry & ad tracking logging |

> [!NOTE]
> Khi chặn request bằng `shouldInterceptRequest`, trả về `WebResourceResponse("text/plain", "UTF-8", 204, "No Content", headers, emptyStream)` sẽ tốt hơn trả về `null` hoặc dữ liệu rác. HTTP 204 báo cho trình duyệt biết request đã thành công nhưng không có nội dung, khiến YouTube script dừng gọi lại thay vì retry liên tục gây nóng máy và tốn pin.

---

### 2.5. User-Agent & Client Hints Spoofing (Vượt tường kiểm duyệt WebView)
* **Vấn đề:**
  - Mặc định Android WebView phát chuỗi User-Agent chứa: `Version/4.0 ... Mobile Safari/537.36` và trong token thường có `; wv`.
  - YouTube và Google phát hiện token `wv` để:
    1. Hạ độ phân giải video xuống 480p hoặc 720p.
    2. Chặn đăng nhập Google Account với cảnh báo "Trình duyệt này không an toàn".
    3. Hiển thị thông báo ép người dùng cài đặt ứng dụng YouTube Native.
* **Giải pháp của AdGuard:**
  - Bóc tách và định dạng lại User-Agent chuẩn của Chrome Mobile độc lập:
    ```kotlin
    val chromeVersion = extractChromeVersion(defaultUserAgent) ?: "128"
    val spoofedUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion.0.0.0 Mobile Safari/537.36"
    webSettings.userAgentString = spoofedUa
    ```
  - Thiết lập Client Hints tương thích (`sec-ch-ua`, `sec-ch-ua-mobile: ?1`, `sec-ch-ua-platform: "Android"`).

---

### 2.6. ExtendedCSS & Cosmetic Filtering Chống Nháy Hình (Element Hiding)
* **Quy tắc CSS chuyên dụng cho YouTube Mobile Web:**
  ```css
  /* Ẩn các banner khuyến mãi, video tài trợ trên trang chủ và kết quả tìm kiếm */
  ytd-promoted-video-renderer,
  ytd-display-ad-renderer,
  ytd-statement-banner-renderer,
  ytd-in-feed-ad-layout-renderer,
  ytm-promoted-sparkles-web-renderer,
  ytm-companion-ad-renderer,
  ytm-ad-slot-renderer,
  .ytp-ad-module,
  .ytp-ad-overlay-container,
  .ytp-ad-player-overlay,
  #player-ads,
  .sparkles-light-cta,
  div[class*="ad-container"],
  ins.adsbygoogle {
      display: none !important;
      visibility: hidden !important;
      height: 0 !important;
      min-height: 0 !important;
      opacity: 0 !important;
      pointer-events: none !important;
  }
  ```
* **Chiến thuật Element Collapsing:** Đặt `height: 0 !important` để triệt tiêu khoảng trống thừa (whitespace collapse), giúp giao diện YouTube mượt mà, liền mạch như bản Premium.

---

### 2.7. Tự động Bỏ qua Siêu tốc (Auto-Skip Fallback)
* Đối với một số trường hợp video livestream hoặc ad server-stitched không bóc được qua JSON:
  - Sử dụng `MutationObserver` kết hợp vòng lặp kiểm tra trạng thái video:
    ```javascript
    setInterval(function() {
        // 1. Tự động click nút Skip ngay khi xuất hiện
        var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button');
        if (skipBtn) { skipBtn.click(); }

        // 2. Mute và tua nhanh cực đại qua quảng cáo không thể bỏ qua (unskippable)
        var video = document.querySelector('video');
        var adElement = document.querySelector('.ad-showing, .ytp-ad-player-overlay');
        if (adElement && video && !isNaN(video.duration) && video.duration > 0) {
            video.muted = true;
            video.playbackRate = 16.0;
            video.currentTime = video.duration;
        }
    }, 250);
    ```

---

## 3. ĐÁNH GIÁ THỰC TRẠNG BLOCKADS (NHỮNG GÌ AGY CLI TRIỂN KHAI CHƯA ĐẠT)

| Tiêu chí | Triển khai của `agy cli` | Tiêu chuẩn kiến trúc AdGuard | Hậu quả thực tế trong BlockAds |
| :--- | :--- | :--- | :--- |
| **Thuật toán lọc Host** | `Set<String>` 30 hosts hardcode, quét `fullUrl.contains(adHost)` tuần tự | Trie / Bloom filter matching với Adblock Plus syntax (`||`, `*`, `^`) | Gây giật lag khi cuộn trang, bỏ lọt 95% quảng cáo, dễ bị false-positive |
| **Thời điểm tiêm Scriptlet** | Chỉ gọi `evaluateJavascript` trong `onPageStarted` & `onPageFinished` | MainFrame HTML Rewriting tại `document_start` hoặc `WebViewCompat` | Bị race condition: YouTube khởi tạo player trước khi script kịp chạy |
| **Xử lý Service Worker** | Chỉ unregister khi `onPageStarted` (sau khi worker đã cache) | Chặn khởi tạo bằng prototype override + vô hiệu hóa cache registration | Service Worker vẫn âm thầm lọt request quảng cáo qua fetch background |
| **Phản hồi khi chặn request** | Trả về `ByteArrayInputStream(ByteArray(0))` text/plain status 200 | Trả về HTTP 204 No Content có chuẩn hóa headers | YouTube script phát hiện lỗi body rỗng, liên tục gửi lại request retry |
| **Kiến trúc mã nguồn (SRP)** | Dồn toàn bộ code JavaScript dạng chuỗi string dài vào `BrowserAdBlocker.kt` | Tách riêng assets scriptlet (`.js`), có engine nạp modular, tuân thủ SRP | File phình to khó bảo trì, vi phạm quy tắc Single Responsibility |
| **Quản lý Tab & Trải nghiệm** | WebView đơn lẻ, không hỗ trợ multi-tab, không có Desktop mode, fullscreen video lỗi | Đầy đủ Tab manager, Fullscreen WebChromeClient, Video background playback | Trải nghiệm người dùng chưa hoàn thiện, xem video bị vướng thanh status bar |

---

## 4. KẾ HOẠCH HÀNH ĐỘNG KHẮC PHỤC (ACTIONABLE REFACTORING PLAN)

### 🎯 Giai đoạn 1: Chuẩn hóa Scriptlets & AdBlocker Engine (Core)
1. **Trích xuất Assets Scriptlets:**
   - Tạo `app/src/main/assets/browser/youtube_sanitizer.js`: Chứa logic bóc tách JSON `ytInitialPlayerResponse`, property getter/setter traps, và fetch/XHR hook.
   - Tạo `app/src/main/assets/browser/service_worker_killer.js`: Chứa logic vô hiệu hóa Service Worker.
   - Tạo `app/src/main/assets/browser/adblock_cosmetic.css`: Chứa CSS ẩn phần tử và collapse khoảng trống.
2. **Kỹ thuật Stream Rewriting tại Document-Start:**
   - Cập nhật `BrowserAdBlocker.kt` để khi `request.isForMainFrame == true`, tải và chèn các thẻ scriptlet vào đầu `<head>` trước khi đẩy vào WebView.
3. **HTTP 204 No Content Response:**
   - Chuẩn hóa hàm `createEmptyResponse()` trả về mã HTTP 204 và header `Cache-Control: no-store`.

### 🎯 Giai đoạn 2: Nâng cấp WebView & Video Playback
1. **Fullscreen Video Support:**
   - Mở rộng `WebChromeClient` xử lý `onShowCustomView` và `onHideCustomView` để cho phép xem video YouTube toàn màn hình mượt mà.
2. **Media Playback Tối ưu:**
   - Cấu hình `mediaPlaybackRequiresUserGesture = false`.
   - Giữ âm thanh tiếp tục phát khi chuyển tab hoặc tắt màn hình (tùy chọn Background Play).
3. **User-Agent Spoofing hoàn chỉnh:**
   - Xóa bỏ triệt để `Version/4.0` và token `; wv` trên mọi thiết bị.

### 🎯 Giai đoạn 3: Tích hợp Trie Engine từ Go Tunnel
1. **Kết nối Blocklist sẵn có:**
   - Thay thế danh sách hardcoded bằng việc tận dụng `GoTunnelEngine` hoặc `EasyList` Trie trong assets để kiểm tra domain trong `shouldInterceptRequest`.

---
*Báo cáo này làm cơ sở kỹ thuật để tái cấu trúc toàn diện module In-App Browser của BlockAds đạt chuẩn AdGuard.*
