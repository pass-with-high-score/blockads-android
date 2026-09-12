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
- [x] **Task 1.1: Chuyển IP Interface TUN sang dải CGNAT (100.64.100.2/32)** — Đã thực hiện trong `VpnTunnelBuilder.kt`.
- [x] **Task 1.2: Chuẩn hóa MTU an toàn ở mức 1350** — Đã thực hiện trong `VpnTunnelBuilder.kt`.
- [x] **Task 1.3: Ngăn Android bóp băng thông tải nền (`setMetered(false)`)** — Đã thực hiện trong `VpnTunnelBuilder.kt`.
- [x] **Task 1.4: Tích hợp DoH Blacklist (Dứt điểm Issue #145)** — Đã tích hợp mmap zero-copy trong Go tunnel (`engine_blocklist.go`), chặn DoH Direct-IP & DoT port 853.

---

### 🛡️ GIAI ĐOẠN 2: TINH CHỈNH BỘ LỌC TRÌNH DUYỆT (Học từ AdGuard)
- [x] **Task 2.1: Danh sách Trình duyệt được phép lọc HTTPS (Browser Inclusions)** — Đã tạo `app/src/main/assets/preset/browsers.txt` và tích hợp auto-select trong `HttpsFilteringViewModel.kt`.
- [x] **Task 2.2: Tự động gỡ bỏ Tracking Headers & URL Parameters** — Đã tạo `tunnel/internal/mitm/privacy.go` xóa `X-Client-Data`, bóc tách `utm_*`, `fbclid`, sanitize Referer, và gán `DNT/Sec-GPC`.
- [x] **Task 2.3: Tự động chuyển chứng chỉ CA vào System Store cho máy Root** — Đã tạo `app/src/main/java/app/pwhs/blockads/utils/SystemCertificateInstaller.kt` tương thích Magisk, KernelSU, APatch.

---

### ⚙️ GIAI ĐOẠN 3: ĐỘ ỔN ĐỊNH HỆ THỐNG & DIRECT BOOT (Học từ ExpressVPN)
- [x] **Task 3.1: Non-blocking TUN File Descriptor** — Đã cấu hình `fcntl(O_NONBLOCK)` và `setBlocking(false)` trong `VpnTunnelBuilder.kt`.
- [x] **Task 3.2: Hỗ trợ Direct Boot (`directBootAware="true"`)** — Đã khai báo Manifest, tạo `DirectBootPreferences.kt` và lắng nghe `LOCKED_BOOT_COMPLETED` trong `BootReceiver.kt`.
- [x] **Task 3.3: Split Kill Switch & Thuật toán loại trừ LAN (CIDR Decomposition)** — Đã tạo `SubnetDecomposer.kt` loại trừ các dải private class A/B/C và multicast mDNS.

---

### 🎨 GIAI ĐOẠN 4: TRẢI NGHIỆM NGƯỜI DÙNG & GIỮ CHÂN (UX & Retention)
- [x] **Task 4.1: Tính năng "Snooze / Pause VPN" (Tạm dừng 1 giờ / hẹn giờ)** — Đã thực hiện qua `VpnResumeWorker` và `ACTION_PAUSE_1H`.
- [x] **Task 4.2: Actionable Notification (Tương tác 2 chiều)** — Đã bổ sung Action buttons Stop, Pause, Retry trong `VpnNotificationManager.kt`.
- [ ] **Task 4.3: Bảng thống kê tổng hợp theo ngày (Room Aggregator)** — Đang tối ưu bảng dữ liệu thống kê.
- [ ] **Task 4.4: Protection Milestone Popup (BottomSheet chúc mừng)** — Sắp ra mắt.

---

## III. TIÊU CHÍ NGHIỆM THU (VERIFICATION CRITERIA)
1. **Kiểm tra kết nối LAN:** Bật VPN, mở ứng dụng Google Home / SmartThings, đảm bảo vẫn tìm thấy TV và Chromecast trong mạng Wi-Fi.
2. **Kiểm tra 4G/5G MTU:** Mở các trang web nặng (Shopee, Facebook, Báo mới) trên sóng 4G/5G, không có hiện tượng nghẽn mạng hay timeout.
3. **Kiểm tra DoH Leak:** Bật tính năng Secure DNS trong Chrome (`Settings -> Privacy -> Use secure DNS -> Cloudflare`), truy cập trang test quảng cáo, xác nhận quảng cáo vẫn bị chặn 100%.
4. **Kiểm tra Reboot:** Khởi động lại máy, xác nhận VPN tự động chạy ngầm trước khi mở khóa màn hình mà không crash.
