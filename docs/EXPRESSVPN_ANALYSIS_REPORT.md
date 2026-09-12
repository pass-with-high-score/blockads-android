# BÁO CÁO PHÂN TÍCH REVERSE ENGINEERING EXPRESSVPN ANDROID
**Định hướng nghiên cứu & Áp dụng cải tiến vào kiến trúc BlockAds**

- **Target Package:** `com.expressvpn.vpn` (bản phát hành arm64-v8a)
- **Công cụ phân tích:** `jadx`, `unzip`, `readelf`, `nm`, `strings`, `python3`
- **Tài liệu tham chiếu dự án:** `blockads-android` (`app/`, `tunnel/`)

---

## 1. TỔNG QUAN KIẾN TRÚC EXPRESSVPN

ExpressVPN trên Android được chia thành 3 tầng rõ rệt:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Android UI & Presentation (Jetpack Compose, M3, Room)    │
│    - AdvanceProtectionSettingActivity, Quick Shortcuts      │
├─────────────────────────────────────────────────────────────┤
│ 2. VPN Management & Service (XVVpnServiceImpl, Dagger Hilt) │
│    - VpnService.Builder wrapper (Routing, MTU, Metered)    │
│    - LinkQualityManager ("Connection Copilot" diagnostics)  │
│    - AdvanceProtectionStatWorker (Daily Aggregator)         │
├─────────────────────────────────────────────────────────────┤
│ 3. Core Native Engine (Rust + UniFFI + Lightway Core)       │
│    - liblightway_mobile.so (Core VPN protocol in C/Rust)    │
│    - libxvclient.so (13.7MB - Engine client & rule parsing)  │
│    - liblink-quality-jni.so (Probing network & latency)     │
│    - libvpnutils.so (Low-level fcntl O_NONBLOCK & AES test) │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. NHỮNG PHÁT HIỆN KỸ THUẬT QUAN TRỌNG & ĐỀ XUẤT ÁP DỤNG

### 2.1. Cấu hình VpnService & Tinh chỉnh TUN Interface

#### 🔹 A. Dùng dải IP CGNAT (`100.64.100.2/32`) thay vì Private Subnet (`10.0.0.2`)
* **Thực tế trong BlockAds:** Hiện tại BlockAds đang gán IP `10.0.0.2/32` hoặc `10.255.255.2/32` cho interface TUN.
* **Vấn đề tiềm ẩn:** Rất nhiều router doanh nghiệp, VPN công ty, hotspot phát từ điện thoại, hoặc mạng Docker/Kubernetes sử dụng dải `10.0.0.0/8`. Khi IP TUN trùng với subnet mạng nội bộ, hệ điều hành Android sẽ xảy ra xung đột bảng định tuyến (route conflict) làm rớt mạng hoặc mất kết nối LAN.
* **Giải pháp của ExpressVPN (`com.expressvpn.sharedandroid.vpn.b`):**
  ```kotlin
  builder.addAddress("100.64.100.2", 32)
  builder.addRoute("100.64.100.2", 32)
  ```
  ExpressVPN sử dụng dải **RFC 6598 (Carrier-Grade NAT: `100.64.0.0/10`)**. Dải này không bao giờ được cấp phát cho mạng gia đình (`192.168.x.x`), doanh nghiệp (`10.x.x.x`), hay hotspot (`172.16.x.x - 172.31.x.x`), loại bỏ 100% rủi ro đụng độ IP.

#### 🔹 B. Giảm MTU xuống `1350` để ổn định trên 4G/5G
* **Thực tế trong BlockAds:** BlockAds đang gán `.setMtu(1500)` trong direct mode.
* **Vấn đề tiềm ẩn:** Trên mạng di động (4G/5G) và một số kết nối PPPoE/VLAN, MTU vật lý thường chỉ là 1420 - 1460 bytes. Khi BlockAds bọc thêm packet hoặc người dùng kích hoạt WireGuard/TLS inspection, gói tin có tổng kích thước vượt quá 1500 bytes sẽ bị chia nhỏ (packet fragmentation) hoặc bị nhà mạng silently drop (MTU blackhole), gây hiện tượng "quay tròn" khi mở web.
* **Giải pháp của ExpressVPN:**
  ```kotlin
  builder.setMtu(1350)
  ```
  `1350` là kích thước tối ưu tuyệt đối cho mọi loại mạng viễn thông toàn cầu.

#### 🔹 C. Thiết lập `builder.setMetered(false)`
* **Vị trí trong ExpressVPN:**
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
  }
  ```
* **Ý nghĩa:** Từ Android 10 (API 29), VPN mặc định bị OS coi là mạng có tính cước (Metered). Nếu không set `false`, các ứng dụng như Google Drive, OneDrive, Play Store Auto-Update sẽ dừng đồng bộ chạy ngầm và báo "Chờ Wi-Fi không tính phí".

#### 🔹 D. Đưa TUN FileDescriptor về Non-Blocking (`O_NONBLOCK`)
* **Vị trí trong ExpressVPN (`com.expressvpn.sharedandroid.vpn.c` & `VpnUtils.java`):**
  ```java
  ParcelFileDescriptor parcelFileDescriptorEstablish = builder.establish();
  VpnUtils.setBlocking(parcelFileDescriptorEstablish.getFd(), false);
  ```
  `VpnUtils.setBlocking()` gọi hàm C native `fcntl(fd, F_SETFL, O_NONBLOCK)`.
* **Ý nghĩa:** Tránh trường hợp luồng đọc/ghi TUN trong Go/C bị treo cứng (blocking deadlock) khi buffer cạn hoặc khi thiết bị đổi mạng.

---

### 2.2. Cơ chế Threat Manager & Lọc Quảng cáo (Zero-Copy)

#### 🔹 A. Zero-Copy Blocklist Passing (`BlocklistInfo`)
* **Trong code ExpressVPN (`com.expressvpn.threatmanager.usecases.BlocklistInfo`):**
  ```kotlin
  data class BlocklistInfo(
      val fd: Int,
      val startOffset: Long,
      val length: Long
  )
  ```
* **Cơ chế:** Khi load file filter (blacklist/whitelist), app **không đọc toàn bộ nội dung file thành chuỗi String lớn trong Android JVM/Heap**. Thay vào đó, app chỉ mở file/asset dưới dạng `AssetFileDescriptor`, lấy `fd`, rồi truyền thẳng xuống native engine C/Rust.
* Native layer thực hiện `mmap()` trực tiếp vùng nhớ file đó.
* **Lợi ích:** 
  1. Startup time tức thì (<10ms) dù file danh sách lên tới hàng trăm MB.
  2. Không tốn dung lượng RAM của ứng dụng (zero-heap allocation).
  3. Tránh kích hoạt Android Garbage Collector gây giật lag khi tải rules.

#### 🔹 B. Chiến lược DoH Blacklist (`assets/blocklist_doh.txt`)
* **Phát hiện:** Trong assets của ExpressVPN có sẵn `blocklist_doh.txt`.
* **Mục đích:** Khắc phục triệt để hiện tượng ứng dụng thứ ba (Chrome, Firefox, quảng cáo tích hợp) hardcode DNS-over-HTTPS (DoH) đến Cloudflare (`cloudflare-dns.com`), Google (`dns.google`) để lách qua DNS nội bộ của VPN.
* **Cách vận hành:** VPN sẽ tự động intercept hoặc phân giải các domain DoH này về NXDOMAIN / Blackhole. Ứng dụng buộc phải fallback về DNS plaintext (port 53) của TUN, nơi BlockAds có thể chặn 100% quảng cáo và tracker (Giải quyết tận gốc **Issue #145**).

---

### 2.3. Hệ thống Chẩn đoán Mạng "Connection Copilot" (Link Quality Diagnostics)

Trong package `com.expressvpn.linkquality`, ExpressVPN xây dựng lớp chẩn đoán mạng tự động cực kỳ thông minh (`LinkQualityManagerImpl` + `liblink-quality-jni.so`).

#### 🔹 Ma trận kiểm tra 7 điểm (`TestResult`):
1. `resultPingOutsideVpn`: Socket ping ra ngoài Internet thực tế (sử dụng `VpnService.protect()`).
2. `resultPingToVpn`: Ping trực tiếp Gateway IP của VPN.
3. `resultPingThroughVpn`: Ping xuyên qua interface TUN.
4. `resultDownloadActive`: Thử tải một file nhị phân 128KB (`sample128k.bin`) qua đường hầm VPN.
5. `resultDownloadSystem`: Thử tải file nhị phân qua mạng gốc bên ngoài VPN.
6. `resultDnsActive`: Phân giải thử một tên miền qua DNS nội bộ của VPN.
7. `resultDnsSystem`: Phân giải thử một tên miền qua DNS của nhà mạng/Wi-Fi gốc.

#### 🔹 Bảng phân loại lỗi tự động (`CcbType`):
* `ClientNoInternet`: Thiết bị mất sóng Wi-Fi/4G hoàn toàn (không phải do VPN hỏng) -> Hiển thị thông báo "Chờ kết nối mạng" thay vì lỗi VPN.
* `TunnelBroken`: Socket mạng ngoài vẫn thông nhưng gói tin không qua được TUN -> Tự động kích hoạt cơ chế silent reconnect.
* `VpnDnsServer`: TUN thông nhưng truy vấn DNS timeout -> Tự động chuyển đổi DNS Upstream fallback.
* `Mtu`: Ping thông nhưng request tải file 128KB bị timeout -> Tự động hạ MTU của TUN xuống 1280.

---

### 2.4. Trải nghiệm Người dùng (UX/UI) & Quản lý Dữ liệu

#### 🔹 A. Notification 2 chiều có Action Buttons
Trong `XVVpnServiceImpl`:
* Notification không chỉ hiển thị text đơn thuần mà tích hợp trực tiếp các nút hành động (PendingIntent):
  * **Trạng thái Connected:** Nút `Disconnect` + `Pause 5 min`.
  * **Trạng thái Reconnecting / Failed:** Nút `Retry` trực tiếp trên notification mà không cần mở lại app.
* `InternetLossAlertTracker`: Khi phát hiện mất sóng Wi-Fi/4G, notification lập tức cập nhật: *"Mất kết nối Internet - Đang chờ mạng khả dụng"* để người dùng không đổ lỗi cho app.

#### 🔹 B. Quick App Shortcuts trên màn hình Connected
* Bảng SQLite `Shortcut` (`packageName`, `shortcutName`, `iconUrl`): Sau khi VPN kết nối thành công, màn hình chính hiển thị ngay danh sách các ứng dụng giải trí/lướt web phổ biến (YouTube, Chrome, Game...) để người dùng click mở ngay với 1 chạm.

#### 🔹 C. Room Database Aggregator (`AdvanceProtectionStats`)
* Không lưu trữ từng log request DNS chi tiết vĩnh viễn (gây nặng máy, phình SQLite).
* Bảng `AdvanceProtectionStats`:
  ```sql
  CREATE TABLE `AdvanceProtectionStats` (
      `connectionStartTime` INTEGER NOT NULL, 
      `connectionEndTime` INTEGER NOT NULL, 
      `numberOfBlock` INTEGER NOT NULL, 
      `cumulativeNumberOfBlock` INTEGER, 
      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
  );
  ```
* Sử dụng `AdvanceProtectionStatWorker` (`CoroutineWorker`) chạy định kỳ mỗi 24h để tổng hợp số lượng tracker/ads đã chặn theo ngày và xóa log chi tiết cũ. Màn hình báo cáo thống kê tải siêu nhanh.

---

## 3. LỘ TRÌNH ĐỀ XUẤT ÁP DỤNG VÀO BLOCKADS (ACTION PLAN)

Khi bạn chuyển qua **AGY IDE** để tạo Plan và triển khai, đây là thứ tự ưu tiên đề xuất:

### 🎯 Giai đoạn 1: Quick Wins (Ổn định kết nối & Fix Issue #145)
- [ ] **Task 1.1:** Đổi IP TUN trong `AdBlockVpnService.kt` sang `100.64.100.2/32` (CGNAT range).
- [ ] **Task 1.2:** Điều chỉnh MTU xuống `1350` (hoặc cấu hình adaptive MTU 1280-1350) để chống drop packet trên 4G/5G.
- [ ] **Task 1.3:** Thêm `builder.setMetered(false)` (trên Android 10+) để tránh bị bóp băng thông tải nền.
- [ ] **Task 1.4:** Tích hợp danh sách **DoH Blacklist** (chặn/NXDOMAIN các endpoint DoH nổi tiếng: Cloudflare, Google DNS, NextDNS) để triệt tiêu việc bypass DNS adblock.

### 🎯 Giai đoạn 2: Tối ưu hoá Engine & Quản lý Bộ nhớ
- [ ] **Task 2.1:** Thêm hàm native fcntl đưa TUN fd về `O_NONBLOCK` để ngăn ngừa tình trạng thread đọc TUN bị deadlock.
- [ ] **Task 2.2:** Nghiên cứu cơ chế nạp file filter list bằng `FileDescriptor` / `mmap` trong Go core (`tunnel/compiler.go`) thay vì cấp phát slice buffer lớn trên Go heap.

### 🎯 Giai đoạn 3: Hệ thống Chẩn đoán (Connection Quality Probe)
- [ ] **Task 3.1:** Xây dựng một module `ConnectionDiagnostics` nhỏ bằng Kotlin Coroutines:
  * Tạo socket được bảo vệ bằng `protect()` để ping kiểm tra mạng ngoài.
  * Thử truy vấn 1 domain test qua DNS cục bộ của BlockAds.
- [ ] **Task 3.2:** Nếu DNS cục bộ không phản hồi sau 3 giây (nhưng mạng ngoài vẫn sống), tự động restart Go resolver hoặc reload socket.

### 🎯 Giai đoạn 4: Nâng cấp UX/UI & Thống kê
- [ ] **Task 4.1:** Bổ sung các nút hành động (`Disconnect`, `Pause`) ngay trên thanh Thông báo (Notification).
- [ ] **Task 4.2:** Tách biệt rõ trạng thái "Mất mạng do Wi-Fi/4G rớt" vs "Lỗi đường hầm VPN".
- [ ] **Task 4.3:** Tối ưu hoá bảng thống kê Room DB theo dạng aggregated daily record.

---
*Tài liệu này được tạo tự động sau quá trình decompile và phân tích chuyên sâu mã nguồn ExpressVPN Android.*
