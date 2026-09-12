# Kế Hoạch Nâng Cấp Kiến Trúc VPN & Bộ Lọc Trình Duyệt BlockAds
**Ngày lập:** 12-09-2026  
**Nguồn cảm hứng:** Reverse Engineering ExpressVPN (`com.expressvpn.vpn`) & AdGuard Android (`com.adguard.android`)

---

## I. MỤC TIÊU DỰ ÁN
1. **Khắc phục triệt để lỗi mất mạng ngầm (Network Drops):** Tinh chỉnh IP TUN, MTU, và cơ chế Non-blocking TUN.
2. **Chặn đứng rò rỉ DNS qua DoH (Issue #145):** Ép trình duyệt và app fallback về DNS cục bộ của BlockAds.
3. **Nâng cấp độ an toàn HTTPS Filtering (Browser-targeted MITM):** Chỉ lọc HTTPS trên các trình duyệt được cấp phép (tránh lỗi Certificate Pinning trên app ngân hàng).
4. **Bảo vệ toàn diện 24/7:** Hỗ trợ Direct Boot (khởi động VPN trước khi mở khóa PIN) và Split Kill Switch (cho phép traffic LAN).
5. **Đột phá về trải nghiệm (UX):** Tính năng Snooze (Tạm dừng VPN 5-15 phút) và Action Notification thông minh.

---

## II. LỘ TRÌNH TRIỂN KHAI CHI TIẾT (PHASED EXECUTION)

```
┌─────────────────────────────────────────────────────────────┐
│ PHASE 1: TUN Hardening & Fix DoH Bypass (Quick Wins)        │
│ 1.1 CGNAT IP (100.64.100.2)  1.2 MTU 1350  1.3 DoH Blacklist│
├─────────────────────────────────────────────────────────────┤
│ PHASE 2: Tinh chỉnh Bộ Lọc Trình Duyệt (AdGuard Insights)   │
│ 2.1 Browser Inclusions List  2.2 Strip Tracking Headers     │
│ 2.3 Move CA to System Store (KernelSU/Magisk)               │
├─────────────────────────────────────────────────────────────┤
│ PHASE 3: Độ Ổn Định & Khởi Động Ngầm (Reliability)          │
│ 3.1 Non-blocking TUN (fcntl) 3.2 Direct Boot Aware          │
│ 3.3 Split Kill Switch (CIDR LAN decomposition)              │
├─────────────────────────────────────────────────────────────┤
│ PHASE 4: Trải Nghiệm Người Dùng (UX & Smart Retention)      │
│ 4.1 VPN Snooze (5-15 min)    4.2 Actionable Notification    │
│ 4.3 Daily Stats Aggregator   4.4 Protection Milestone       │
└─────────────────────────────────────────────────────────────┘
```

---

### 🚀 GIAI ĐOẠN 1: TUN HARDENING & CHỐNG BYPASS DOH (Ưu tiên cao nhất)

#### Task 1.1: Chuyển IP Interface TUN sang dải CGNAT
* **File cần sửa:** `app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt`
* **Vấn đề:** Đang dùng `10.0.0.2` hoặc `10.255.255.2` dễ đụng độ với router Wi-Fi/mạng nội bộ công ty dùng subnet `10.x.x.x`.
* **Thực thi:**
  * Đổi địa chỉ interface: `builder.addAddress("100.64.100.2", 32)` (RFC 6598).
  * Đổi fake DNS IP cục bộ: `100.64.100.1` hoặc `100.64.100.3`.
  * Cập nhật đồng bộ trong Go engine (`tunnel/resolver.go`, `tunnel/engine.go`).

#### Task 1.2: Chuẩn hóa MTU an toàn ở mức 1350
* **File cần sửa:** `app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt`
* **Vấn đề:** MTU 1500 gây phân mảnh gói tin và rớt mạng (blackhole) trên sóng di động 4G/5G Viettel/Vina/Mobi.
* **Thực thi:** Khóa `builder.setMtu(1350)`.

#### Task 1.3: Ngăn Android bóp băng thông tải nền
* **File cần sửa:** `AdBlockVpnService.kt`
* **Thực thi:**
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
  }
  ```

#### Task 1.4: Tích hợp DoH Blacklist (Dứt điểm Issue #145)
* **File cần sửa:** `tunnel/blocker.go`, `tunnel/resolver.go`
* **Thực thi:**
  * Bổ sung danh sách các endpoint DoH nổi tiếng (`cloudflare-dns.com`, `dns.google`, `dns.quad9.net`, `doh.mullvad.net`...).
  * Khi client query các domain này, Go resolver tự động trả về `NXDOMAIN` hoặc `127.0.0.1`. Trình duyệt buộc phải fallback về DNS port 53 cục bộ của VPN.

---

### 🛡️ GIAI ĐOẠN 2: TINH CHỈNH BỘ LỌC TRÌNH DUYỆT (Học từ AdGuard)

#### Task 2.1: Danh sách Trình duyệt được phép lọc HTTPS (Browser Inclusions)
* **File tạo mới:** `app/src/main/assets/preset/browsers.txt`
* **File cần sửa:** `app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt`, `tunnel/mitm_filter.go`
* **Thực thi:**
  * Chỉ kích hoạt MITM HTTPS Filtering cho các package trình duyệt đã kiểm chứng (Chrome, Edge, Firefox, Brave, Samsung Internet, Kiwi...).
  * Mặc định bypass HTTPS cho 100% app còn lại để tránh lỗi Certificate Pinning của app ngân hàng.

#### Task 2.2: Tự động gỡ bỏ Tracking Headers & URL Parameters
* **File cần sửa:** `tunnel/mitm_handler.go`
* **Thực thi:**
  * Khi proxy inspect request từ trình duyệt:
    * Gỡ bỏ header định danh Chrome: `X-Client-Data`.
    * Gỡ bỏ HTTP `Referer` trên các request bên thứ ba (Third-party requests).
    * Tước bỏ các tracking query params trên URL: `utm_source`, `utm_medium`, `utm_campaign`, `fbclid`, `gclid`.

#### Task 2.3: Tự động chuyển chứng chỉ CA vào System Store cho máy Root
* **File cần sửa:** `app/src/main/java/app/pwhs/blockads/service/RootProxyService.kt`, `app/src/main/java/app/pwhs/blockads/util/CertificateManager.kt`
* **Thực thi:**
  * Kiểm tra quyền root (Magisk / KernelSU / APatch).
  * Nếu có root, hỗ trợ 1 chạm mount read-write và copy CA cert vào thư mục `/system/etc/security/cacerts/` với định dạng hash `.0` (OpenSSL subject hash).

---

### ⚙️ GIAI ĐOẠN 3: ĐỘ ỔN ĐỊNH HỆ THỐNG & DIRECT BOOT (Học từ ExpressVPN)

#### Task 3.1: Non-blocking TUN File Descriptor
* **File cần sửa:** `tunnel/tun_wrapper.go` hoặc JNI bridge
* **Thực thi:** Sử dụng syscall `fcntl(fd, F_SETFL, O_NONBLOCK)` trên TUN FD để vòng lặp đọc/ghi packet không bao giờ bị treo (hang thread).

#### Task 3.2: Hỗ trợ Direct Boot (`directBootAware="true"`)
* **File cần sửa:** `app/src/main/AndroidManifest.xml`, `app/src/main/java/app/pwhs/blockads/service/BootReceiver.kt`
* **Thực thi:**
  * Khai báo `android:directBootAware="true"` trong Service và `BootReceiver`.
  * Lắng nghe action `android.intent.action.LOCKED_BOOT_COMPLETED`.
  * Dùng `context.createDeviceProtectedStorageContext()` để lưu và đọc trạng thái `isVpnEnabled`.

#### Task 3.3: Split Kill Switch & Thuật toán loại trừ LAN (CIDR Decomposition)
* **File cần sửa:** `AdBlockVpnService.kt`
* **Thực thi:**
  * Tách 2 tùy chọn trong Settings: "Kill Switch" và "Cho phép truy cập mạng LAN".
  * Thay vì gọi `excludeRoute()` (hay crash trên Android cũ), sử dụng thuật toán phân rã subnet CIDR để exclude: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `224.0.0.0/24` (Multicast mDNS cho Chromecast).

---

### 🎨 GIAI ĐOẠN 4: TRẢI NGHIỆM NGƯỜI DÙNG & GIỮ CHÂN (UX & Retention)

#### Task 4.1: Tính năng "Snooze / Pause VPN" (Tạm dừng 5 / 15 phút)
* **File tạo mới:**
  * `app/src/main/java/app/pwhs/blockads/service/snooze/VpnPauseWorker.kt`
  * `app/src/main/java/app/pwhs/blockads/service/snooze/ResumeVpnReceiver.kt`
* **Thực thi:**
  * Người dùng chọn tạm dừng 5p/15p -> VPN ngắt tạm thời, lên lịch `OneTimeWorkRequest`.
  * Sau khi hết thời gian, WorkManager tự động kết nối lại VPN ngầm.
  * Notification hiển thị nút "Tiếp tục ngay" (Resume Now).

#### Task 4.2: Actionable Notification (Tương tác 2 chiều)
* **File cần sửa:** `AdBlockVpnService.kt` (Notification builder)
* **Thực thi:** Bổ sung các nút hành động trực tiếp trên thanh thông báo:
  * Trạng thái Connected: `Disconnect`, `Tạm dừng 5 phút`.
  * Trạng thái Error: `Thử lại (Retry)`.

#### Task 4.3: Bảng thống kê tổng hợp theo ngày (Room Aggregator)
* **File cần sửa:** Room DB schema (`app/src/main/java/app/pwhs/blockads/data/database/`)
* **Thực thi:** Tạo bảng `DailyProtectionSummary` và một Worker chạy lúc 00:00 hàng ngày để gộp số lượng query đã chặn, dọn dẹp các dòng log chi tiết cũ.

#### Task 4.4: Protection Milestone Popup (BottomSheet chúc mừng)
* **Thực thi:** Khi số lượt chặn đạt 1.000 / 10.000 / 50.000, hiển thị một BottomSheet chúc mừng kèm số dung lượng data ước tính đã tiết kiệm.

---

## III. TIÊU CHÍ NGHIỆM THU (VERIFICATION CRITERIA)
1. **Kiểm tra kết nối LAN:** Bật VPN, mở ứng dụng Google Home / SmartThings, đảm bảo vẫn tìm thấy TV và Chromecast trong mạng Wi-Fi.
2. **Kiểm tra 4G/5G MTU:** Mở các trang web nặng (Shopee, Facebook, Báo mới) trên sóng 4G/5G, không có hiện tượng nghẽn mạng hay timeout.
3. **Kiểm tra DoH Leak:** Bật tính năng Secure DNS trong Chrome (`Settings -> Privacy -> Use secure DNS -> Cloudflare`), truy cập trang test quảng cáo, xác nhận quảng cáo vẫn bị chặn 100%.
4. **Kiểm tra Reboot:** Khởi động lại máy, xác nhận VPN tự động chạy ngầm trước khi mở khóa màn hình mà không crash.
