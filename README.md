# Edit Teks Foto

Aplikasi Android untuk mengubah tulisan yang ada di dalam foto.

## Cara kerja

1. Pilih foto yang ada tulisannya.
2. Aplikasi membaca semua teks di foto (OCR ML Kit, jalan offline) beserta posisi, ukuran, sudut, warna teks, dan warna latarnya.
3. Ketuk teks pada foto (atau buka **Daftar Teks**), lalu ketik teks baru dengan keyboard.
4. Teks lama dihapus dengan mengisi latar di sekitarnya, teks baru digambar dengan ukuran, warna, dan rotasi yang mirip.
5. Tekan **Simpan** untuk menyimpan hasilnya ke Galeri (`Pictures/FotoTeks`).

Hasil terbaik didapat pada latar polos atau gradasi halus. Latar bertekstur atau gambar yang ramai bisa terlihat kasar.

## Download APK

APK dibangun otomatis oleh GitHub Actions setiap ada push ke `main`:

- Halaman **Releases**, tag `latest`, file `FotoTeks.apk`
- Atau tab **Actions**, pilih run terbaru, bagian Artifacts, `FotoTeks-apk`

Untuk install, izinkan "Install dari sumber tidak dikenal" di Android. Minimal Android 10.

## Build sendiri

```
./gradlew assembleDebug
```

Hasilnya ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Teknologi

Kotlin, Jetpack Compose (Material 3), ML Kit Text Recognition.
