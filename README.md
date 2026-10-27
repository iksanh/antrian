# Cetak Antrian (Android)

Aplikasi tablet Android untuk mencetak nomor antrian ke printer thermal Bluetooth (ESC/POS).

Loket: Informasi dan Pengaduan (I), Pembayaran (B), Prioritas (P), Pendaftaran (A), Penyerahan (LP).
Format nomor: `<kode>001`, reset otomatis setiap hari.

## Build APK tanpa Android Studio (GitHub Actions)

1. Buat repository baru di GitHub, upload seluruh isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions** → workflow **Build APK** berjalan otomatis (atau klik **Run workflow**).
3. Setelah selesai (±3–5 menit), buka hasil run → bagian **Artifacts** → unduh `CetakAntrian-apk`.
4. Ekstrak zip-nya, salin `app-debug.apk` ke tablet, lalu instal (izinkan "Instal dari sumber tidak dikenal").

## Pemakaian pertama di tablet

1. Nyalakan printer, lalu pairing lewat **Pengaturan > Bluetooth** tablet (PIN umumnya `0000` atau `1234`).
2. Buka aplikasi, izinkan akses **Perangkat sekitar / Bluetooth**.
3. Tekan **Pengaturan** → **Pilih Printer Bluetooth** → pilih printer → **Tes Cetak** → **Simpan**.

## Fitur

- Tombol **CETAK** per loket; nomor hanya naik jika cetak berhasil.
- **Samakan No.** untuk menyamakan dengan nomor di layar antrian.
- **Cetak Ulang** nomor terakhir (kertas macet).
- Judul dan catatan bawah tiket bisa diubah di Pengaturan.
- Layar tetap menyala selama aplikasi dibuka.
