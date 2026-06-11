# Báo cáo tổng hợp Issues — BlockAds Android

**Ngày lập:** 11/06/2026
**Repo:** pass-with-high-score/blockads-android
**Tổng quan:** 43 issues đang mở (92 tổng, 49 đã đóng). Trong đó: **18 bug**, **24 feature request**, 1 thông báo của maintainer (#168).

---

## 1. Tóm tắt nhanh theo mức ưu tiên

| Ưu tiên | Issues | Lý do |
|---------|--------|-------|
| 🔴 P1 — Lỗi core, nhiều người gặp | #171, #166, #162, #179, #170 | Mất kết nối/nhận diện mạng sai, kill-switch chết toàn bộ mạng, không tự khởi động sau reboot, filter không tự cập nhật |
| 🟠 P2 — Lỗi chức năng cụ thể | #177, #172, #156, #150, #130, #137, #163, #145, #104, #148, #152, #158, #85 | Lỗi từng tính năng (root mode, wireguard, firewall, uptime…) |
| 🟡 P3 — Feature request phổ biến | #175, #164+#56, #157, #107, #74, #142+#154 | Nhiều người yêu cầu / có người trùng yêu cầu |
| ⚪ P4 — Feature request khác | #169, #167, #165, #161, #153, #151, #149, #146, #147, #133, #123, #119, #111, #95, #89, #73 | Mở rộng, độ ưu tiên thấp hơn |

---

## 2. Nhóm BUG theo chủ đề (root cause liên quan nhau)

### 2.1. 🔴 Nhóm "App nghĩ là không có mạng/WiFi" — nghi cùng một root cause
| # | Tiêu đề | Thiết bị | Ngày |
|---|---------|----------|------|
| #171 | Phone does not recognize wifi connection (Storytell, Speedtest, Flud+ báo không có WiFi) | Xiaomi Redmi Note 15 Pro | 11/05 |
| #166 | IP đổi thành 10.0.0.2 sau khi mở app (vỡ script MacroDroid dựa trên IP) | POCO F6, HyperOS 3 | 09/05 |
| #156 | OneDrive camera backup không chạy khi bật BlockAds (log không hề ghi block) | Moto G, Android 11/12 | 29/04 |
| #177 | Audible không download được khi bật BlockAds | — | 04/06 |

**Phân tích sơ bộ:** Đây nhiều khả năng KHÔNG phải do filter chặn nhầm (log không ghi block). Khi VPN bật, app khác query `ConnectivityManager` thấy network active là TRANSPORT_VPN (IP 10.0.0.2) thay vì TRANSPORT_WIFI → các app có logic "chỉ chạy khi có WiFi" (OneDrive backup, Audible download, Flud) từ chối hoạt động. Hướng fix: kiểm tra `VpnService.Builder` — cần gọi `setUnderlyingNetworks(null)` (để hệ thống tự map mạng nền), xem xét `setMetered(false)`, và kiểm tra lại route/địa chỉ TUN. Một issue tương tự xảy ra ở app Rethink (người dùng #171 xác nhận). **Đây là cụm bug đáng fix nhất vì 4 issues cùng triệu chứng.**

### 2.2. 🔴 #162 — "Block connections without VPN" (kill switch) làm mất toàn bộ mạng
- Samsung OneUI 8, Android 16. Người dùng thứ 2 xác nhận trên Redmagic 10 (NetGuard cũng bị, Athena thì không).
- **Phân tích sơ bộ:** BlockAds chỉ route DNS qua TUN (không phải full-tunnel). Khi bật kill switch của Android, mọi traffic không đi qua VPN bị hệ thống chặn → mất mạng toàn bộ. Muốn hỗ trợ kill switch phải route toàn bộ traffic qua TUN và forward (như Athena/Vanguard làm). Cần quyết định: hỗ trợ full-route hay ghi rõ trong tài liệu là không tương thích với "Block connections without VPN".

### 2.3. 🔴 #179 — Root proxy mode không tự khởi động sau reboot ✅ ĐÃ PHÂN TÍCH XONG
- Pixel 6, LineageOS 23.2 (Android 16), v6.4.0.
- **Root cause đã xác định (mức tin cậy cao):** libsu `Shell.cmd()` cache main shell cho cả vòng đời process. Lúc boot, lệnh shell đầu tiên trong `IptablesManager` chạy trước khi daemon Magisk/KernelSU sẵn sàng → libsu cache shell `sh` KHÔNG có root. Cả 10 lần retry trong `RootProxyService.startProxy()` (tổng backoff chỉ ~37 giây — `VpnRetryManager` bước 1,1,2,3,5,5…s) dùng lại shell hỏng đó → iptables fail 10/10 → service tự tắt **không có thông báo lỗi** → app hiện "Unprotected".
- **Đã comment phân tích + xin log:** https://github.com/pass-with-high-score/blockads-android/issues/179#issuecomment-4681830361
- **Kế hoạch fix (sau khi reporter xác nhận log):**
  1. Tạo lại libsu shell giữa các lần retry khi `Shell.getShell().isRoot == false` (đóng shell cache, build lại).
  2. Kéo dài cửa sổ retry khi `EXTRA_STARTED_FROM_BOOT` (vài phút thay vì 37s).
  3. Hiện notification "Không thể khởi động sau reboot — bấm để thử lại" thay vì fail im lặng.
- File liên quan: `service/RootProxyService.kt` (vòng retry ~dòng 204), `service/IptablesManager.kt`, `service/VpnRetryManager.kt`, `service/BootReceiver.kt`.

### 2.4. 🔴 #170 — Filter không tự cập nhật khi app bị lock chạy nền
- Người dùng lock app khỏi bị kill → filter không auto-update nhiều ngày; đổi mạng cũng không trigger update. Regression 4-5 bản gần đây. Người dùng đang tag trực tiếp @nqmgaming.
- Hướng điều tra: WorkManager job auto-update — kiểm tra constraint, ExistingPeriodicWorkPolicy (có thể bị `KEEP` với config cũ), hoặc job chỉ chạy lúc app process restart.

### 2.5. 🟠 Nhóm Root Proxy mode còn lại
| # | Vấn đề | Ghi chú |
|---|--------|---------|
| #150 | App whitelist không hoạt động trong root mode (Pixel 8 Pro, Android 17 Beta, Wild KSU) | Có file log đính kèm — nên đọc log trước |
| #130 | Root Proxy không chặn gì cả trong khi VPN mode chạy tốt (Pixel 8 Pro, KernelSU, Android 16) | 8 comments, đã có debug build gửi cho user; liên quan fix `3b4d591` |
| #123 | Root Proxy không bật được khi VPN khác đang chạy | Bản chất là FR: root mode nên độc lập với VPN slot |

### 2.6. 🟠 Nhóm WireGuard
| # | Vấn đề | Ghi chú |
|---|--------|---------|
| #137 | Internal domain không truy cập được ở wireguard mode dù đã thêm split DNS zones | Follow-up của #129, user xác nhận RethinkDNS làm được; 4 comments |
| #142, #154 | FR: nhiều tunnel WireGuard + chọn app cho từng tunnel (split tunnel) | Hai issue trùng nhau — nên merge #154 vào #142 (người dùng cũng đề nghị vậy) |

### 2.7. 🟠 Bug khác
| # | Vấn đề | Ghi chú |
|---|--------|---------|
| #172 | Một số app kết nối rất chậm/timeout dù firewall tắt (S23, OneUI 7) | Có thể liên quan cụm 2.1 hoặc hiệu năng resolver |
| #163 | Uptime hiển thị sai, khác nhau giữa notification và app | `startTimestamp` reset khi service restart? Liên quan network switch (user mô tả đổi mạng làm uptime nhảy) — có thể chỉ ra service bị restart ngầm |
| #145 | DNS leak: vẫn thấy DNS của ISP dù đã đặt DoH RethinkDNS | Kiểm tra fallback DNS + IPv6 leak |
| #104 | Không truy cập được bằng IP trực tiếp (internal/external) | User xác nhận 6.3.0 vẫn lỗi; 4 comments |
| #148 | Không chạy được trong Work Profile khi profile cá nhân có VPN khác (trước đây chạy được) | Regression — cần bisect các bản gần đây |
| #152 | Firewall block WhatsApp nhưng WhatsApp vẫn có mạng | Có thể do push qua Google Play Services, hoặc firewall DNS-level không chặn connection sẵn có |
| #158 | Không dùng được trên Smart TV — nút Start không focus được bằng remote (D-pad) | Lỗi Compose focus navigation; fix nhỏ: thêm focusable/D-pad support |
| #85 | Không chặn quảng cáo trên thiết bị của user | Thiếu thông tin, 3 comments, cần hỏi thêm cấu hình |
| #151 | Thiết lập kết nối VPN chậm hơn app cùng loại (tiếng Trung) | Tối ưu thời gian khởi động — một phần do load filter trước khi establish |
| #156→ xem 2.1 | | |

---

## 3. FEATURE REQUESTS

### 3.1. 🟡 Đáng ưu tiên (nhiều người yêu cầu / giá trị cao)
| # | Yêu cầu | Ghi chú |
|---|---------|---------|
| #175 | **Query Logs** — xem từng DNS request, filter nào chặn, blacklist/whitelist trực tiếp từ log | Có người thứ 2 ủng hộ. App đã có DnsLogDao — chủ yếu là làm UI |
| #164 + #56 | **Hỗ trợ whitelist filter lists** (dạng list như AdAway/DNS66) | #56 đã được phân tích: backend coi whitelist như blocklist; cần detect loại filter và parse vào WhitelistDomainDao |
| #157 | Loại trừ app khỏi adblock (không phải firewall) | Per-app VPN exclusion — `addDisallowedApplication` |
| #107 | Block/unblock domain theo từng app | FirewallManager đã có nhưng chỉ block toàn app |
| #74 | Firewall: chặn internet mặc định cho app mới cài | Kèm option bật/tắt |
| #111 | Build filter local thay vì VPS, có switch toàn cục | Đã trao đổi trên Telegram, user đang test, xin thêm "global switch" |

### 3.2. ⚪ Còn lại
| # | Yêu cầu |
|---|---------|
| #167 | Validate custom DNS trước khi lưu + ô test domain với tất cả filter đang bật |
| #165 | Firewall: toggle chặn Roaming |
| #161 | Tự điền tên blocklist từ trường Title của file |
| #153 | Firewall chặn app truy cập mạng LAN (như AFWall) |
| #149 | Hỗ trợ userscripts/extensions (như AdGuard) |
| #146 | Proxy mode không cần VPN/root (như pDNSF) |
| #147 | Tùy chỉnh nội dung notification (tắt số liệu để tiết kiệm pin) |
| #133 | DNS-over-HTTP/3 và DNS-over-QUIC (cần thư viện QUIC cho Go tunnel) |
| #119 | Chế độ Shizuku/ADB + lỗi log khi bật HTTPS filter |
| #95 | Issue tổng hợp 8 yêu cầu (DPI bypass, nhiều custom DNS, tùy biến nav bar, icon…) — nên tách nhỏ |
| #89 | Bypass DPI / spoof SNI RST — ngoài scope DNS blocker, cân nhắc đóng |
| #73 | Tabs Queries/Apps, per-app stats, Profiles (như NextDNS) — effort lớn |
| #169 | Tắt WiFi khi tắt màn hình (như Athena) |

### 3.3. Meta
- **#168** — Thông báo của chính maintainer (nqmgaming): issues đang nhiều, sẽ xử lý vào Chủ nhật.

---

## 4. Kế hoạch xử lý đề xuất

### Đợt 1 (tuần này) — Bugs P1
1. **#179** — Chờ log xác nhận từ reporter → fix libsu shell recreate + kéo dài retry + notification lỗi. *(Đã phân tích xong, comment đã đăng)*
2. **Cụm #171/#166/#156/#177** — Tái hiện trên máy thật: bật BlockAds → kiểm tra `ConnectivityManager.getActiveNetwork()` từ app khác. Xem `AdBlockVpnService.establishVpn()`: `setUnderlyingNetworks`, `setMetered`, route config. Một fix có thể đóng cả 4 issues.
3. **#170** — Kiểm tra WorkManager auto-update filter: constraints + policy + trigger khi đổi mạng.
4. **#162** — Quyết định scope: hỗ trợ full-tunnel cho kill switch (effort lớn) hay document hạn chế + thông báo trong app khi phát hiện kill switch bật.

### Đợt 2 — Bugs P2
5. #150 (đọc log đính kèm), #130 (theo dõi kết quả debug build), #163 (uptime/service restart), #137 (split DNS wireguard), #148 (regression work profile), #145 (DNS leak), #104 (truy cập bằng IP), #158 (D-pad focus — fix nhanh, dễ).

### Đợt 3 — Dọn dẹp + FR
6. Merge #154 vào #142 (trùng); cân nhắc đóng #89 (ngoài scope); tách #95 thành các issue con; gắn label cho 24 issues chưa có label.
7. FR ưu tiên: #175 (Query Logs), #164+#56 (whitelist lists), #157 (app exclusion).

### Quy trình (theo workflow đã thống nhất)
- Mỗi bug: phân tích code → comment trên issue xin log/xác nhận → chỉ fix khi lỗi được xác minh.

---

## 5. Phụ lục — Danh sách đầy đủ 43 issues đang mở

| # | Loại | Tiêu đề | Tác giả | Ngày | Cmt |
|---|------|---------|---------|------|-----|
| 179 | 🐛 Bug | Auto-reconnect không restart sau reboot (root proxy) | wh0isit | 10/06 | 1 |
| 177 | 🐛 Bug | Audible không download được khi bật BlockAds | cimax06-ops | 04/06 | 0 |
| 175 | ✨ FR | Query Logs | Codingkingsman | 27/05 | 1 |
| 172 | 🐛 Bug | Apps kết nối chậm/timeout | RAbhilash | 14/05 | 0 |
| 171 | 🐛 Bug | Phone không nhận diện WiFi | novw | 11/05 | 1 |
| 170 | 🐛 Bug | Filter không auto-update khi app lock chạy nền | vdbhb59 | 11/05 | 3 |
| 169 | ✨ FR | Chặn WiFi khi tắt màn hình (như Athena) | Maomaoswife | 10/05 | 0 |
| 168 | 📋 Meta | Thông báo của maintainer về backlog | nqmgaming | 09/05 | 2 |
| 167 | ✨ FR | Validate DNS khi lưu + test domain tổng hợp | InLem | 09/05 | 0 |
| 166 | 🐛 Bug | IP đổi thành 10.0.0.2 khi mở app | InLem | 09/05 | 0 |
| 165 | ✨ FR | Firewall: chặn Roaming | nightznero | 08/05 | 0 |
| 164 | ✨ FR | Hỗ trợ whitelist lists | nightznero | 05/05 | 0 |
| 163 | 🐛 Bug | Uptime hiển thị sai/lệch nhau | Poonsta | 03/05 | 3 |
| 162 | 🐛 Bug | "Block connections without VPN" làm mất mạng toàn bộ | Poonsta | 03/05 | 1 |
| 161 | ✨ FR | Auto-fill tên blocklist từ Title | NooB9496 | 03/05 | 0 |
| 158 | 🐛 Bug | Không dùng được trên Smart TV (D-pad focus) | SergioSikoni1 | 01/05 | 1 |
| 157 | ✨ FR | App exclusion cho adblock | LinuxGuy-cyber | 30/04 | 0 |
| 156 | 🐛 Bug | OneDrive camera backup bị chặn | Robio | 29/04 | 0 |
| 154 | ✨ FR | Nhiều WireGuard tunnel (trùng #142) | holdit | 25/04 | 1 |
| 153 | ✨ FR | Firewall chặn truy cập LAN | vPrapo | 24/04 | 0 |
| 152 | 🐛 Bug | Firewall không chặn được WhatsApp | TheGuru8 | 23/04 | 0 |
| 151 | ✨ FR | Tối ưu tốc độ thiết lập VPN | wq-zzz | 23/04 | 0 |
| 150 | 🐛 Bug | App whitelist không hoạt động ở root mode (có log) | Klusek1983 | 18/04 | 0 |
| 149 | ✨ FR | Hỗ trợ userscripts/extensions | zealstallion | 18/04 | 0 |
| 148 | 🐛 Bug | Work Profile + VPN khác ở Personal Profile (regression) | tuqueque | 16/04 | 0 |
| 147 | ✨ FR | Tùy chỉnh nội dung notification | scepterus | 13/04 | 0 |
| 146 | ✨ FR | Proxy mode không cần VPN/root | zputnyq | 13/04 | 0 |
| 145 | 🐛 Bug | DNS leak — vẫn thấy DNS của ISP | Username-android55 | 13/04 | 0 |
| 142 | ✨ FR | WireGuard: split tunnel, multi-config, auto theo WiFi | abdess47 | 11/04 | 0 |
| 137 | 🐛 Bug | Internal domain không truy cập được (wireguard mode) | 725525 | 11/04 | 4 |
| 133 | ✨ FR | DoH3 / DNS-over-QUIC | sivajipro | 10/04 | 1 |
| 130 | 🐛 Bug | Root Proxy không chặn, VPN mode thì được (KernelSU) | Neutrovertido | 06/04 | 8 |
| 123 | ✨ FR | Root Proxy chạy song song VPN khác | innit86 | 03/04 | 1 |
| 119 | ✨ FR | Chế độ Shizuku/ADB + lỗi log HTTPS filter | Username-android55 | 31/03 | 1 |
| 111 | ✨ FR | Build filter local + switch toàn cục | scepterus | 26/03 | 3 |
| 107 | ✨ FR | (Un)block domain theo từng app | nightznero | 22/03 | 1 |
| 104 | 🐛 Bug | Không truy cập được bằng IP address | f3vkx | 20/03 | 4 |
| 95 | ✨ FR | Tổng hợp 8 yêu cầu (nên tách nhỏ) | AXEN-hub | 18/03 | 2 |
| 89 | ✨ FR | Bypass DPI / SNI RST (ngoài scope) | app/ | 15/03 | 3 |
| 85 | 🐛 Bug | Không chặn quảng cáo (thiếu thông tin) | raihan-emon | 15/03 | 3 |
| 74 | ✨ FR | Chặn mạng mặc định cho app mới cài | walrus543 | 11/03 | 2 |
| 73 | ✨ FR | Tabs Queries/Apps, Profiles (effort lớn) | Subbarao6338 | 11/03 | 1 |
| 56 | 🐛/✨ | Whitelist filter lists load sai số rule | nightznero | 09/03 | 4 |

---
*Báo cáo được tạo tự động từ dữ liệu GitHub ngày 11/06/2026. Issue #179 đã có phân tích root cause chi tiết và comment trên GitHub; các issue khác là đánh giá sơ bộ từ mô tả, cần xác minh code/log trước khi fix (theo quy trình: phân tích → comment xin xác nhận → fix).*
