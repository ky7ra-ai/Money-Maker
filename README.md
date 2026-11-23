# Edit Teks Foto

Aplikasi Android untuk mengubah tulisan di dalam foto, dengan hasil yang berusaha sama dengan aslinya: font, ukuran, warna, ketajaman, kecerahan latar, dan butiran noise.

## Cara kerja

1. Pilih foto yang ada tulisannya.
2. **OCR** (ML Kit, jalan offline) membaca tiap baris teks beserta posisi, sudut, dan perspektifnya.
3. Tiap baris **dianalisis**:
   - baris diluruskan (rotasi dan perspektif dikoreksi),
   - latar dimodelkan dari sisi kiri dan kanan teks (gradasi ikut terbaca),
   - **font dicocokkan** dengan merender teks asli memakai ratusan font terbuka, lalu membandingkannya piksel demi piksel (jenis, ketebalan, miring atau tidak, ukuran, lebar huruf, posisi baseline),
   - ketajaman tepi (blur), warna teks, dan noise foto diukur.
4. Ketuk teks pada foto, lalu ketik teks baru. Font hasil deteksi tampil lengkap dengan alternatif yang mirip, dan bisa diganti dari daftar semua font.
5. Teks lama dihapus (hanya piksel tinta yang diganti, tekstur di sekitarnya tetap), teks baru digambar dengan gaya yang sama, lalu dihaluskan dan diberi noise yang sama.
6. **Simpan** ke Galeri (`Pictures/FotoTeks`).

Semua proses berjalan di dalam HP, tanpa internet dan tanpa mengirim foto ke mana pun.

## Hasil uji

Ada uji otomatis di emulator (`PipelineTest`, berjalan di GitHub Actions) yang membuat foto sintetis dengan font yang diketahui, menjalankan seluruh pipeline, lalu menyimpan gambar sebelum dan sesudah. Pada 17 baris uji, font terdeteksi persis di 12 baris, sebagian besar sisanya font yang sangat mirip. Hasil uji terbaru ada di branch `ci-visual`.

## Batasan yang perlu diketahui

- Font hanya bisa dikenali bila ada di pustaka (sekitar 1.000 varian dari Google Fonts, ditambah font sistem Android). Font komersial atau font khusus akan dicocokkan ke yang paling mirip.
- OCR menghapus spasi ganda, jadi teks dengan spasi lebar (misalnya kolom struk) sulit dicocokkan persis.
- Teks yang sangat kecil, sangat buram, atau di atas latar yang ramai (foto orang, pemandangan) bisa menghasilkan isian latar yang terlihat.
- Satu baris diedit sebagai satu kesatuan dengan satu gaya.

## Download APK

APK dibangun otomatis oleh GitHub Actions setiap ada push ke `main`:

- Halaman **Releases**, tag `latest`, file `FotoTeks.apk`
- Atau tab **Actions**, pilih run terbaru, bagian Artifacts, `FotoTeks-apk`

Untuk install, izinkan "Install dari sumber tidak dikenal" di Android. Minimal Android 10.

## Build sendiri

```
bash scripts/fetch-fonts.sh   # unduh font terbuka ke app/src/main/assets/fonts (sekali saja)
./gradlew assembleDebug
```

Hasilnya ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Lisensi font

Font diunduh dari repo [google/fonts](https://github.com/google/fonts) dan berlisensi SIL Open Font License, Apache 2.0, atau Ubuntu Font License. Lisensi masing-masing keluarga ada di folder asalnya di repo tersebut.

## Teknologi

Kotlin, Jetpack Compose (Material 3), ML Kit Text Recognition.
