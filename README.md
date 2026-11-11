# DNS Changer

Aplikasi Android pengubah DNS melalui VPN lokal tanpa root.

## Fitur
- DNS bawaan: Cloudflare, Google, Quad9, OpenDNS, AdGuard DNS, dan CleanBrowsing Family.
- Tambah profil DNS sendiri menggunakan nama dan alamat IPv4.
- Profil DNS kustom disimpan lokal di perangkat dan tetap tersedia setelah aplikasi ditutup.
- Pilih profil, sambungkan, dan putuskan VPN DNS.
- GitHub Actions membangun APK debug pada setiap push ke branch `main`.

## Menambahkan DNS kustom
1. Isi nama DNS (opsional).
2. Masukkan alamat IPv4, misalnya `1.1.1.1`.
3. Tekan **SIMPAN DNS**.
4. Profil akan langsung masuk daftar dan terpilih.
5. Tekan **SAMBUNGKAN** untuk menggunakannya.

Profil kustom dapat dihapus dengan tombol **HAPUS** saat koneksi sedang terputus. Data profil disimpan di SharedPreferences aplikasi dan tidak dikirim ke server.

## Build
Persyaratan: JDK 17, Android SDK 35, dan Gradle 8.9.

```bash
gradle --no-daemon assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Batasan teknis
- Implementasi penerusan paket saat ini menangani DNS IPv4 melalui UDP.
- DNS melalui TCP dan IPv6 belum ditangani.
- Aplikasi menggunakan slot VPN Android sehingga tidak dapat berjalan bersamaan dengan VPN lain.
- Pengujian pada perangkat fisik tetap diperlukan untuk memvalidasi DNS dari masing-masing penyedia.
