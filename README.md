# CropGuard Android App

App Android dạng WebView wrapper cho website CropGuard (https://cropnlu.duckdns.org).

## 🚀 Cách lấy file APK nhanh nhất — dùng GitHub Actions (miễn phí, không cần cài gì)

Project này đã kèm sẵn file `.github/workflows/build-apk.yml` để GitHub tự build APK cho bạn.

**Bước 1 — Tạo repo GitHub và đẩy code lên:**
```bash
cd cropguard-android
git init
git add .
git commit -m "Initial CropGuard Android app"
git branch -M main
git remote add origin https://github.com/<TEN_TAI_KHOAN>/cropguard-android.git
git push -u origin main
```
(Tạo repo trống trước tại https://github.com/new, không cần README/license khi tạo)

**Bước 2 — Theo dõi build:**
Vào tab **Actions** trên trang GitHub repo → sẽ thấy workflow "Build CropGuard APK" tự chạy (khoảng 3-5 phút).

**Bước 3 — Tải file APK:**
Khi workflow chạy xong (dấu ✅ xanh), bấm vào lần chạy đó → kéo xuống mục **Artifacts** → tải file `cropguard-debug-apk.zip` → giải nén ra sẽ có `app-debug.apk`.

**Bước 4 — Cài lên điện thoại:**
Chuyển file `app-debug.apk` vào điện thoại (qua USB, Google Drive, Zalo gửi cho mình...) → mở file đó trên điện thoại → bấm Cài đặt (cần cho phép "Install from unknown sources" nếu được hỏi).

> Không cần chạy lần thứ 2 thủ công — mỗi lần bạn `git push` code mới, GitHub tự build lại APK mới.

---

## Tính năng

- Tải toàn bộ giao diện CropGuard hiện có (Next.js) trong WebView native
- Hỗ trợ chụp ảnh bằng camera HOẶC chọn ảnh từ thư viện khi upload (input type="file" trên web)
- Splash screen logo xanh khi khởi động
- Pull-to-refresh (kéo xuống để tải lại trang)
- Progress bar khi tải trang
- Màn hình báo lỗi riêng khi mất mạng, có nút "Thử lại"
- Nút Back của Android lùi lại lịch sử trình duyệt trước khi thoát app
- Link ngoài domain CropGuard (OAuth Google, GitHub...) tự mở bằng Chrome thật
- Cookie/session được giữ lại giữa các lần mở app (đăng nhập 1 lần)
- Chặn mixed content (HTTP trong trang HTTPS) và không bỏ qua lỗi SSL — an toàn cho người dùng

## Cách build trên máy/server riêng (thay thế nếu không muốn dùng GitHub Actions)



- Android Studio (Hedgehog 2023.1.1 trở lên khuyến nghị)
- JDK 17
- Android SDK: minSdk 24 (Android 7.0), targetSdk 34 (Android 14)

## Cách build

### 1. Mở project
Mở Android Studio → Open → chọn thư mục `cropguard-android` (thư mục chứa file `settings.gradle.kts`).

Android Studio sẽ tự động sync Gradle lần đầu (cần Internet để tải dependencies).

### 2. Build APK debug (để test nhanh)
```bash
./gradlew assembleDebug
```
File APK xuất ra tại: `app/build/outputs/apk/debug/app-debug.apk`

Cài trực tiếp lên điện thoại qua USB:
```bash
./gradlew installDebug
```

### 3. Build APK release (để phát hành)
Trước tiên cần tạo keystore để ký app (chỉ làm 1 lần):
```bash
keytool -genkey -v -keystore cropguard-release.keystore \
  -alias cropguard -keyalg RSA -keysize 2048 -validity 10000
```

Thêm vào `app/build.gradle.kts` phần `signingConfigs` (xem comment trong file), sau đó:
```bash
./gradlew assembleRelease
```
File APK ký sẵn tại: `app/build/outputs/apk/release/app-release.apk`

### 4. Build AAB để đăng Google Play (khuyến nghị thay vì APK)
```bash
./gradlew bundleRelease
```
File AAB tại: `app/build/outputs/bundle/release/app-release.aab`

## Cấu hình quan trọng

File `app/src/main/java/com/cropguard/app/Config.kt`:
```kotlin
const val BASE_URL = "https://cropnlu.duckdns.org"
```
Đổi URL này nếu domain CropGuard thay đổi.

`ALLOWED_HOSTS`: danh sách domain được phép hiển thị trong WebView. Mọi link dẫn ra ngoài domain này sẽ tự mở bằng Chrome thay vì load trong app — quan trọng cho các luồng OAuth (đăng nhập Google) không bị WebView Google chặn.

## Những điều cần kiểm tra trước khi phát hành lên Google Play

1. **Đổi `applicationId`** trong `app/build.gradle.kts` nếu cần namespace khác `com.cropguard.app`
2. **Thay icon** — hiện đang dùng vector lá đơn giản, nên thay bằng icon thiết kế chính thức (xuất từ Figma/Canva, dùng Android Studio's Image Asset Studio: Right-click `res` → New → Image Asset)
3. **Privacy Policy URL** — Google Play yêu cầu link chính sách bảo mật vì app dùng Camera + Internet
4. **Test kỹ luồng upload ảnh** trên nhiều dòng máy Android (Samsung, Xiaomi có thể xử lý file chooser khác nhau)
5. **Test offline** — đảm bảo màn hình lỗi mạng hiển thị đúng khi tắt WiFi/4G

## Cấu trúc project

```
cropguard-android/
├── build.gradle.kts              # Gradle root config
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts          # Dependencies, SDK versions
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   # Permissions, activity declaration
        ├── java/com/cropguard/app/
        │   ├── CropGuardApp.kt   # Application class
        │   ├── Config.kt         # BASE_URL, allowed domains
        │   └── MainActivity.kt   # WebView logic chính
        └── res/
            ├── layout/activity_main.xml   # WebView + error screen UI
            ├── values/                    # colors, strings, themes
            ├── drawable/                   # icons, splash background
            └── xml/file_paths.xml         # FileProvider config cho camera
```

## Nâng cấp sau này (gợi ý)

- Thêm push notification (Firebase Cloud Messaging) để báo kết quả chẩn đoán bệnh
- Thêm JavaScript Bridge để web gọi trực tiếp camera native (UX mượt hơn input file thông thường)
- Cache trang đã xem để có trải nghiệm offline cơ bản
- Tích hợp Google Play App Signing khi publish
