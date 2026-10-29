# ProCam — Kamera Manual untuk Android

Aplikasi kamera buatan sendiri (Kotlin + CameraX) untuk pengguna yang butuh **Pro Mode** (ISO, shutter, fokus manual) dan **FPS tidak dikunci di 30**.

Dibuat untuk Poco X7 Pro (Android 15 / HyperOS 2), tetapi berjalan di perangkat Android 8.0+ (minSdk 26) yang mendukung Camera2.

## Fitur

- **Pro Mode manual**
  - ISO manual (rentang dari `SENSOR_INFO_SENSITIVITY_RANGE`)
  - Shutter manual (dari `SENSOR_INFO_EXPOSURE_TIME_RANGE`), termasuk long exposure
  - Fokus manual dalam dioptri / meter (dari `LENS_INFO_MINIMUM_FOCUS_DISTANCE`)
  - Diterapkan lewat Camera2Interop: `CONTROL_MODE_OFF`, `AE_MODE_OFF`, `AF_MODE_OFF`
- **Target FPS**
  - Daftar FPS diambil dari `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`
  - Default = rentang dengan `upper` tertinggi (60/120 fps bila perangkat melaporkannya)
  - Bisa diganti ke `Auto` atau rentang lain lewat tombol `FPS`
- Foto (JPEG, `DCIM/ProCam`) + watermark opsional
- Video (MP4, `DCIM/ProCam`) + audio
- Grid 3x3, flash, zoom 1x/2x/5x + pinch, tap-to-focus
- HUD menampilkan resolusi, FPS target, dan nilai pro aktif

## Batasan yang perlu dipahami

FPS **tidak bisa dilampaui melebihi kemampuan hardware/HAL**. Aplikasi hanya memilih rentang tertinggi yang dilaporkan kamera. Beberapa hal yang memengaruhi:

- Pada banyak HP, 60 fps hanya tersedia di resolusi 1080p (bukan 4K). Karena itu saat target >30 fps aplikasi memprioritaskan FHD/HD untuk video.
- Pro Mode (AE/AF off) dapat menurunkan FPS karena exposure time manual (mis. 1/15 s membatasi ke ±15 fps).
- Kamera depan biasanya hanya melaporkan 30 fps.
- Perekaman 120/240 fps (slow-motion) butuh jalur khusus `CONSTRAINED_HIGH_SPEED_SESSION` yang belum dipakai di versi ini.

## Tanpa Android Studio

Build hanya butuh JDK 17 + Android SDK command-line tools + Gradle.

### Cara termudah: unduh APK dari GitHub Actions

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

- `app/src/main/java/com/ugwap/procam/MainActivity.kt` — logika kamera, Pro Mode, FPS, foto/video/watermark
- `app/src/main/java/com/ugwap/procam/GridOverlay.kt` — overlay grid
- `app/src/main/res/layout/activity_main.xml` — UI kamera
- `.github/workflows/build.yml` — CI build APK + release

## Lisensi

Kode milik sendiri (MIT). Tidak memuat kode atau aset dari aplikasi kamera OEM mana pun.
