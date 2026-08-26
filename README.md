# QRIS Listener

QRIS Listener adalah aplikasi Android privat untuk mendeteksi notifikasi pembayaran QRIS statis dari aplikasi resmi GoPay Merchant. Versi pertama ini hanya membuktikan pembacaan notifikasi secara lokal. Belum ada website, API, atau pengubahan status pesanan.

## Batas privasi

- Sumber yang diterima hanya package `com.gojek.gopaymerchant`.
- Format awal yang diterima adalah judul `Pembayaran QRIS statis diterima` dan isi seperti `Rp 1 di CiganStore, Digital & Kreatif.`
- Aplikasi tidak memiliki izin internet, SMS, kontak, file/media, Accessibility Service, VPN, device administrator, atau root.
- Aplikasi tidak meminta nomor telepon, OTP, PIN, kata sandi, cookie, atau token GoPay.
- Android memang memberikan metadata notifikasi kepada listener yang diaktifkan. Kode aplikasi memeriksa package pengirim terlebih dahulu dan langsung mengabaikan package lain sebelum membaca judul atau isi.
- Data yang lolos validasi disimpan di database privat milik QRIS Listener. Isi notifikasi mentah dan notifikasi aplikasi lain tidak disimpan.

## Build APK

Gunakan Android Studio JBR dan Android SDK lokal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

APK debug dihasilkan di:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Instalasi melalui USB

1. Aktifkan Developer options dan USB debugging di ponsel.
2. Sambungkan ponsel lalu izinkan dialog debugging USB.
3. Periksa koneksi dan pasang APK tanpa menghapus data lama:

```powershell
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

## Mengaktifkan listener

1. Buka QRIS Listener.
2. Tekan **Buka pengaturan akses**.
3. Aktifkan **QRIS Listener** pada halaman Notification access milik Android.
4. Setujui peringatan Android, lalu kembali ke aplikasi.
5. Pastikan **Akses notifikasi** menampilkan **Aktif** dan **Koneksi listener** menampilkan **Terhubung**.

Aktivitas QRIS Listener boleh ditutup. Android tetap mengikat layanan notifikasi selama aksesnya aktif. Ponsel tetap harus menyala dan GoPay Merchant harus benar-benar memunculkan notifikasi pembayaran.

## Pengujian Rp1

1. Pastikan kanal notifikasi pembayaran GoPay Merchant tidak dimatikan. Mode senyap masih dapat bekerja selama notifikasinya tetap diposting oleh Android.
2. Tutup layar QRIS Listener untuk membuktikan listener bekerja di latar belakang.
3. Lakukan satu pembayaran QRIS statis sebesar Rp1.
4. Tunggu notifikasi GoPay Merchant muncul.
5. Buka kembali QRIS Listener.
6. Pastikan nominal, merchant, dan waktu muncul tepat satu kali pada riwayat lokal.

Jika pembayaran tidak muncul, periksa kembali akses notifikasi. Pada ponsel yang agresif membatasi aplikasi latar belakang, buka pengaturan baterai aplikasi dan ubah penggunaan baterai GoPay Merchant serta QRIS Listener menjadi **Tidak dibatasi** atau **Unrestricted**, lalu ulangi tes.

Pada Xiaomi, Redmi, atau POCO dengan MIUI/HyperOS, aktifkan juga **Mulai otomatis/Autostart**, kunci QRIS Listener di layar aplikasi terbaru, dan jangan menutupnya melalui **Pembersih/One-tap clean**. Pembersih HyperOS dapat mematikan listener meskipun halaman akses notifikasi masih menunjukkan izin aktif. Setelah pembersihan atau restart ponsel, buka QRIS Listener dan pastikan status berubah dari **Menghubungkan ulang** menjadi **Terhubung** sebelum menerima pembayaran.

## Riwayat lokal

- Tombol **Hapus** menghapus seluruh riwayat yang disimpan QRIS Listener setelah konfirmasi.
- Menghapus riwayat QRIS Listener tidak mengubah transaksi di GoPay Merchant.
- Menghapus instalasi APK juga menghapus database lokal aplikasi.
- Notifikasi QRIS dengan format yang berubah akan diabaikan secara aman dan hanya menambah penghitung diagnostik tanpa menyimpan isinya.

## Batas versi pertama

- Pencocokan format notifikasi masih berdasarkan contoh nyata yang diberikan pengguna dan harus diverifikasi pada ponsel merchant.
- Pengiriman HTTPS, HMAC, pencocokan pesanan, web dashboard, stok, checkout, dan status otomatis `PAID` belum dibuat.
- APK debug menggunakan kunci debug lokal dan hanya ditujukan untuk pengujian pribadi.
