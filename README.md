# ProCam — Kamera Manual untuk Android

**Unduh APK:** [Releases → app-debug.apk](https://github.com/Ugwap/ProCam/releases/latest/download/app-debug.apk)

Aplikasi kamera buatan sendiri (Kotlin + CameraX) untuk pengguna yang butuh **Pro Mode** (ISO, shutter, fokus manual), **target FPS 60/120**, dan UI bergaya iOS.

Dibuat untuk Poco X7 Pro (HyperOS / Android 17), tetapi berjalan di perangkat Android 8.0+ (minSdk 26) yang mendukung Camera2.

## Fitur

- **Pro Mode manual**
  - ISO manual (rentang dari `SENSOR_INFO_SENSITIVITY_RANGE`)
  - Shutter manual (dari `SENSOR_INFO_EXPOSURE_TIME_RANGE`), termasuk long exposure
  - Fokus manual dalam dioptri / meter (dari `LENS_INFO_MINIMUM_FOCUS_DISTANCE`)
  - Perubahan langsung (live) saat slider digeser, tanpa rebind/restart preview
  - Diterapkan lewat Camera2Interop + `Camera2CameraControl` (`CONTROL_MODE_OFF`, `AE_MODE_OFF`, `AF_MODE_OFF`)
- **Stabilisasi video (EIS)** — chip `STAB ON/OFF` di mode VIDEO, default **OFF**
  - Memakai `CONTROL_VIDEO_STABILIZATION_MODE` (`PREVIEW_STABILIZATION` bila tersedia, jika tidak `ON`)
- **Target FPS**
  - Daftar FPS diambil dari `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`
  - Default = rentang dengan `upper` tertinggi yang dilaporkan kamera
  - Ada opsi **60 fps (paksa)** dan **120 fps (paksa)** yang mengirim `CONTROL_AE_TARGET_FPS_RANGE` langsung; bila HAL menolak, aplikasi otomatis kembali ke rentang yang didukung
  - HUD menandai `paksa` bila memakai target yang tidak diiklankan kamera
- **Rasio foto** 1:1, 4:3, 16:9
  - 4:3 dan 16:9 memakai `setTargetAspectRatio` CameraX
  - 1:1 diambil 4:3 lalu di-crop persegi di tengah setelah capture
- **Thumbnail hasil terakhir** di kiri bawah (gaya iOS) — ketuk untuk membuka foto di galeri
- Foto (JPEG, `DCIM/ProCam`) + watermark opsional
- Video (MP4, `DCIM/ProCam`) + audio
- Grid 3x3, flash, zoom 1x/2x/5x + pinch, tap-to-focus
- HUD menampilkan resolusi, FPS target, dan nilai pro aktif
- Panel atas dan bawah memakai lapisan hitam 30% (bukan blur) agar ringan

## Batasan yang perlu dipahami

FPS dan kamera tambahan **tidak bisa melampaui yang diekspos HAL/ROM**:

- Pada Poco X7 Pro (HyperOS), kamera belakang yang tersedia untuk aplikasi pihak ketiga hanya **satu** (id 0, zoom 1x–10x). Kamera ultrawide disembunyikan ROM, sehingga chip `0.6x` tidak muncul. Menampilkannya butuh pipeline Camera2 mentah dengan physical camera id.
- `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` perangkat ini maksimum **30 fps**, jadi opsi 60/120 hanya bersifat upaya paksa; hasil rekaman tetap 30 fps. Stock camera bisa 60 fps lewat jalur/vendor tag khusus.
- Pro Mode (AE/AF off) dapat menurunkan FPS karena exposure time manual (mis. 1/15 s membatasi ke ±15 fps).
- Perekaman 120/240 fps (slow-motion) butuh `CONSTRAINED_HIGH_SPEED_SESSION` yang belum dipakai di versi ini.
- Warna preview bisa sedikit lebih jenuh saat EIS aktif; ini perilaku tuning HAL MediaTek, bukan filter aplikasi. Karena itu default STAB = OFF.

## Tanpa Android Studio

Build hanya butuh JDK 17 + Android SDK command-line tools + Gradle.

### Cara termudah: unduh APK dari GitHub Release

[**app-debug.apk**](https://github.com/Ugwap/ProCam/releases/latest/download/app-debug.apk) — ditandatangani debug key.

Setiap push ke `main` menghasilkan artifact APK. Tag `v*` otomatis membuat Release berisi `app-debug.apk`.

### Build lokal (Windows/Linux/macOS)

```bash
# 1. Pastikan JDK 17 aktif
java -version

# 2. Arahkan ke Android SDK (cmdline-tools + platform 35)
export ANDROID_HOME=$HOME/Android/Sdk      # Windows: setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"

# 3. Build
./gradlew assembleDebug    # atau: gradle assembleDebug
```

Hasil: `app/build/outputs/apk/debug/app-debug.apk`

### Pasang di HP

1. Salin APK ke HP (kabel USB, Google Drive, dll).
2. Buka APK → izinkan **Install unknown apps** untuk aplikasi file manager/browser.
3. Jalankan ProCam → izinkan **Kamera** dan **Mikrofon**.

> APK ini ditandatangani dengan debug key. Untuk distribusi publik, buat keystore sendiri dan konfigurasi `signingConfigs`.

## Struktur

- `app/src/main/java/com/ugwap/procam/MainActivity.kt` — logika kamera, Pro Mode, FPS, rasio, thumbnail, foto/video/watermark
- `app/src/main/java/com/ugwap/procam/GridOverlay.kt` — overlay grid
- `app/src/main/res/layout/activity_main.xml` — UI kamera
- `.github/workflows/build.yml` — CI build APK + release

## Lisensi

Kode milik sendiri (MIT). Tidak memuat kode atau aset dari aplikasi kamera OEM mana pun.
