---
name: browser-adblock-agent
description: Autonomous ad and anti-adblock analyzer using browser subagent with reports from blockads-reports MCP. Fetches pending user-reported websites, navigates with mobile emulation, extracts intrusive ad elements, anti-adblock modals, and bait scripts, synthesizes rules for BlockAds Android, and updates report status to resolved. Use when the user says "xử lý report", "check report mới", "dùng browser check", "check ad web bằng browser", "tự động duyệt reports", or asks to analyze ads from reported sites.
---

# Browser AdBlock Subagent Skill (Tích hợp blockads-reports MCP)

Skill chuyên dụng cho phép AI tự động:
1. **Lấy danh sách website người dùng báo cáo** từ cơ sở dữ liệu thông qua MCP Server `blockads-reports`.
2. **Khởi chạy `browser_subagent`** để giả lập thiết bị di động, tự động truy cập URL và phân tích cơ chế quảng cáo / anti-adblock.
3. **Tổng hợp bộ quy tắc (rules)** 4 tầng cho BlockAds Android.
4. **Cập nhật trạng thái report sang `resolved`** trong database thông qua MCP `update_report_status`.

Toàn bộ quy trình diễn ra tự động trên máy tính, **không cần cắm cáp hay kết nối điện thoại qua ADB**.

---

## 1. Nguồn Dữ liệu: `blockads-reports` MCP

MCP Server `blockads-reports` cung cấp các công cụ quản lý báo cáo từ người dùng BlockAds:

### A. Lấy danh sách báo cáo chưa xử lý (`pending`)
Gọi tool qua `call_mcp_tool`:
```json
{
  "ServerName": "blockads-reports",
  "ToolName": "list_reports",
  "Arguments": {
    "status": "pending",
    "category": "ads_not_blocked",
    "limit": 10
  }
}
```
*Gợi ý filter*:
- `category`: `"ads_not_blocked"` (quảng cáo chưa chặn được), `"popup_redirect"` (nhảy tab/popup), `"site_broken"` (vỡ trang/anti-adblock).

### B. Lấy chi tiết báo cáo
```json
{
  "ServerName": "blockads-reports",
  "ToolName": "get_report_details",
  "Arguments": {
    "report_id": "<UUID>"
  }
}
```

### C. Đánh dấu đã xử lý xong (`resolved`) hoặc bỏ qua (`ignored`)
Sau khi đã thêm rule và bump version:
```json
{
  "ServerName": "blockads-reports",
  "ToolName": "update_report_status",
  "Arguments": {
    "report_id": "<UUID>",
    "status": "resolved"
  }
}
```

---

## 2. Quy trình Thực thi Tự động (End-to-End Workflow)

### Bước 1: Thu thập URL từ MCP
1. Gọi `list_reports` để lấy danh sách URL đang `pending`.
2. Lọc ra các domain hợp lệ (bỏ qua domain rác hoặc URL nội bộ).

### Bước 2: Chẩn đoán bằng Browser Subagent (`browser_subagent`)
Với mỗi URL cần xử lý, khởi tạo `browser_subagent`:
1. **Thiết lập**:
   - Khởi tạo viewport di động (`resize_browser_window` về `390x844`).
   - Điều hướng tới URL (`open_browser_url`).
   - Đợi 3-5 giây để các script quảng cáo, popunder và anti-adblock modal xuất hiện.
2. **Chạy trích xuất DOM** (sử dụng snippet từ `.agents/skills/browser-adblock-agent/scripts/extract_ads_dom.js`):
   - **Overlays / Popups**: Tìm div `position: fixed | absolute`, `z-index >= 999`, che màn hình.
   - **Khóa cuộn (Scroll Lock)**: Kiểm tra `document.body.style.overflow === 'hidden'`.
   - **Bait Elements**: Các class/id chứa `adsbox`, `pub_300x250`, `ad-container`, `adblock`.
   - **Ad Scripts / Iframes**: Script bên thứ ba (`pagead`, `doubleclick`, `adnxs`, `aclib`, `chatmate`, `cathaytrash`, `/ads.js`, v.v.).
   - **Floating Videos**: `#vid-sld-cont`, `[data-shb]`, v.v.
3. **Output**: Trả về dữ liệu text JSON có cấu trúc (tiết kiệm token, không chụp ảnh màn hình thừa).

---

## 3. Chiến lược Sửa lỗi 4 Tầng cho BlockAds

Cập nhật mã nguồn dự án dựa trên dữ liệu thu thập được:

### Tầng 1: Chặn Network & Mock Surrogates
- **Tập tin**:
  - `app/src/main/java/app/pwhs/blockads/ui/browser/rules/BrowserRuleDefaults.kt`
  - `rules/browser_rules.json`
  - `app/src/main/java/app/pwhs/blockads/ui/browser/interceptor/BrowserAdBlocker.kt`
- **Quy tắc**:
  - Thêm domain quảng cáo/tracker mới vào danh sách `AD_HOST_SUFFIXES` và `adDomains`.
  - **Surrogates cho Anti-AdBlock**: Với script mà website dùng để test adblock (như `/ads.js`, `adsbygoogle.js`, `inplayer-adx`), trả về stub `HTTP 200` rỗng trong `BrowserAdBlocker.getSurrogateResponse()` để kích hoạt `onload`, tránh trigger modal anti-adblock.

### Tầng 2: Scriptlets & Anti-AdBlock Defuser
- **Tập tin**: `app/src/main/assets/browser/adguard_scriptlets.js`
- **Quy tắc**:
  - Stub hàm phát hiện quảng cáo (ví dụ: `window.showAdblockModal = function() {}`, `window.aclib = { runPop: () => {} }`).
  - Đánh lừa kích thước phần tử mồi (Geometry Spoofing): Proxy `window.getComputedStyle` và `offsetHeight` đối với các phần tử bait (`adsbox`, `pub_300x250`).
  - Gỡ bỏ overlay và phục hồi cuộn trong `purgeAdArtifacts()`.

### Tầng 3: Cosmetic CSS Filtering
- **Tập tin**: `app/src/main/assets/browser/adblock_cosmetic.css`
- **Quy tắc**:
  - Ẩn triệt để class/id popup, overlay và slot quảng cáo:
    ```css
    #siteNotice,
    .ad-slot,
    .pub_300x250,
    #vid-sld-cont {
        display: none !important;
        visibility: hidden !important;
        height: 0 !important;
        pointer-events: none !important;
    }
    ```
  - Mở khóa cuộn nếu trang bị khóa:
    ```css
    html, body {
        overflow: auto !important;
    }
    ```

### Tầng 4: Tăng Version Rules & Cập nhật MCP Status
1. **Tăng Version**: Tăng `const val INITIAL_VERSION` trong `BrowserRuleDefaults.kt` và `"version"` trong `rules/browser_rules.json` (ví dụ từ `4L` lên `5L`).
2. **Cập nhật Database MCP**:
   - Gọi `update_report_status` với `report_id` và `status: "resolved"`.

---

## 4. Tiêu chuẩn Mã nguồn & Kiểm thử

1. **Giới hạn số dòng**: Mỗi file sửa đổi (`BrowserRuleDefaults.kt`, `BrowserAdBlocker.kt`, `adblock_cosmetic.css`, `adguard_scriptlets.js`) **không vượt quá 500 dòng**.
2. **Build Verification**:
   ```bash
   ./gradlew :app:assembleDebug -Pabi=arm64-v8a
   ```
3. **Commit chuẩn mực**:
   ```bash
   git commit -m "feat(browser): resolve ad report for <Domain>"
   ```
