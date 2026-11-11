# DNS Changer

Aplikasi Android pengubah DNS melalui VPN lokal (tanpa root).

## Fitur
- Pilihan Cloudflare (1.1.1.1), Google (8.8.8.8), dan Quad9 (9.9.9.9).
- Tombol sambungkan dan putuskan.
- VPN lokal meneruskan permintaan DNS IPv4/UDP ke penyedia terpilih.
- Tidak mengumpulkan akun atau riwayat penjelajahan.
- GitHub Actions membangun APK debug pada setiap push ke branch `main`.

## Build
Persyaratan: Android Studio, JDK 17, Android SDK 35, dan Gradle 8.9.

Jalankan:
```bash
gradle --no-daemon assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Batasan implementasi awal
- DNS IPv4 melalui UDP didukung.
- DNS melalui TCP dan IPv6 belum ditangani.
- Aplikasi menggunakan slot VPN Android. VPN lain (misalnya VPN kantor atau aplikasi VPN lain) tidak dapat berjalan bersamaan.
- Setelah mengaktifkan DNS, uji akses internet pada perangkat dan jaringan yang digunakan.
