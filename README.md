# QRIS Listener

QRIS Listener adalah aplikasi Android privat untuk mendeteksi notifikasi pembayaran QRIS statis dari aplikasi resmi GoPay Merchant. Setiap pembayaran yang dikenali disimpan di HP, lalu dikirim ke server CiganStore (Cloudflare Worker) lewat HTTPS bertanda tangan HMAC agar pesanan dapat dicocokkan. Pencocokan pesanan dan perubahan status dilakukan oleh server, bukan oleh aplikasi ini.

## Batas privasi

- Sumber yang diterima hanya package `com.gojek.gopaymerchant`.
- Format awal yang diterima adalah judul `Pembayaran QRIS statis diterima` dan isi seperti `Rp 1 di CiganStore, Digital & Kreatif.`
- Izin internet hanya dipakai untuk mengirim pembayaran dan heartbeat ke alamat server yang Anda isi sendiri. APK rilis hanya mau memakai `https://`. WorkManager menambahkan izin `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, dan `RECEIVE_BOOT_COMPLETED` agar antrean menunggu internet dan tetap terjadwal setelah HP restart.
- Yang dikirim ke server hanya ID event (hash SHA-256 notifikasi), nominal, nama merchant, waktu notifikasi, nama package, dan judul notifikasi. Isi notifikasi mentah dan notifikasi aplikasi lain tidak pernah dikirim.
- Aplikasi tidak memiliki izin SMS, kontak, file/media, Accessibility Service, VPN, device administrator, atau root.
- Aplikasi tidak meminta nomor telepon, OTP, PIN, kata sandi, cookie, atau token GoPay.
- Android memang memberikan metadata notifikasi kepada listener yang diaktifkan. Kode aplikasi memeriksa package pengirim terlebih dahulu dan langsung mengabaikan package lain sebelum membaca judul atau isi. Notifikasi ringkasan grup (group summary) juga diabaikan agar satu pembayaran tidak tercatat dua kali.
- Data yang lolos validasi disimpan di database privat milik QRIS Listener. Isi notifikasi mentah dan notifikasi aplikasi lain tidak disimpan.

## Build APK

Gunakan JDK 17 dan Android SDK lokal. Contoh JDK 17 yang tersedia di komputer pengembang utama:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

Unit test JVM mencakup tanda tangan HMAC (vektor uji kontrak Worker), body JSON, klasifikasi respons server, validasi pengaturan, keputusan antrean/backoff, dan konfigurasi WorkManager.

APK debug dihasilkan di `app\build\outputs\apk\debug\app-debug.apk`. APK ini hanya untuk emulator atau perangkat uji.

## Instalasi APK debug melalui USB (perangkat uji)

1. Aktifkan Developer options dan USB debugging di ponsel.
2. Sambungkan ponsel lalu izinkan dialog debugging USB.
3. Periksa koneksi dan pasang APK tanpa menghapus data lama:

```powershell
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

## Build rilis untuk HP merchant

HP merchant wajib memakai APK rilis, yaitu APK yang tidak `debuggable` dan ditandatangani kunci milik Anda sendiri.

1. Buat keystore sekali saja dan simpan di luar folder repo. Catat password-nya di password manager, lalu cadangkan file `.jks` bersama password tersebut:

   ```powershell
   New-Item -ItemType Directory -Force "$env:USERPROFILE\.android-keystores" | Out-Null
   & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\.android-keystores\qris-listener-release.jks" -alias qris-listener -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Salin `keystore.properties.example` menjadi `keystore.properties` (diabaikan Git), lalu isi path dan password-nya. Sebagai alternatif, isi variabel lingkungan `QRIS_RELEASE_STORE_FILE`, `QRIS_RELEASE_STORE_PASSWORD`, `QRIS_RELEASE_KEY_ALIAS`, dan `QRIS_RELEASE_KEY_PASSWORD`.
3. Build APK, lalu periksa tanda tangannya:

   ```powershell
   .\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease
   & "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs "app\build\outputs\apk\release\app-release.apk"
   ```

Jika keystore belum dikonfigurasi, Gradle hanya menghasilkan `app-release-unsigned.apk` yang tidak dapat dipasang. Naikkan `versionCode` di `app/build.gradle.kts` setiap merilis pembaruan. Jika keystore hilang, pembaruan berikutnya harus diawali uninstall.

## Pindah dari APK debug ke APK rilis

Android menolak memperbarui aplikasi yang ditandatangani dengan kunci berbeda, jadi APK debug harus dihapus lebih dulu.

1. Catat atau screenshot riwayat lokal yang masih dibutuhkan. Uninstall menghapus database QRIS Listener, tetapi transaksi di GoPay Merchant tidak terpengaruh.
2. Hapus APK debug, lalu pasang APK rilis:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb uninstall id.ciganstore.qrislistener
   & $adb install "app\build\outputs\apk\release\app-release.apk"
   ```

3. Aktifkan ulang akses notifikasi, Mulai otomatis/Autostart, dan baterai **Tidak dibatasi**. Kunci juga QRIS Listener di layar aplikasi terbaru (lihat bagian **Pengujian Rp1**).
4. Lakukan tes Rp1 dan pastikan status **Terhubung**.

## Keamanan HP merchant

- Matikan **Debugging USB** setelah selesai memasang APK, lewat Setelan → Setelan tambahan → Opsi pengembang. Pilih juga **Cabut otorisasi debug USB** agar komputer yang pernah diizinkan tidak bisa terhubung lagi.
- Selama Debugging USB aktif, komputer yang diizinkan dapat membaca isi notifikasi semua aplikasi, termasuk OTP. Pada APK debug, komputer itu juga dapat mengubah database listener.
- Jangan memasang APK debug di HP merchant.

## Mengaktifkan listener

1. Buka QRIS Listener.
2. Tekan **Buka pengaturan akses**.
3. Aktifkan **QRIS Listener** pada halaman Notification access milik Android.
4. Setujui peringatan Android, lalu kembali ke aplikasi.
5. Pastikan **Akses notifikasi** menampilkan **Aktif** dan **Koneksi listener** menampilkan **Terhubung**.

Aktivitas QRIS Listener boleh ditutup. Android tetap mengikat layanan notifikasi selama aksesnya aktif. Ponsel tetap harus menyala dan GoPay Merchant harus benar-benar memunculkan notifikasi pembayaran.

## Pengiriman ke server CiganStore

QRIS Listener mengirim setiap pembayaran yang dikenali ke `POST /api/v1/listener/events` dan mengirim heartbeat setiap 15 menit ke `POST /api/v1/listener/heartbeat`. Admin CiganStore menganggap listener **terhubung** bila heartbeat atau pembayaran diterima dalam 20 menit terakhir.

Alurnya:

1. Notifikasi GoPay Merchant dibaca, lalu pembayaran disimpan dulu di database HP sebagai antrean. Antrean tetap ada saat HP offline, saat HyperOS mematikan aplikasi, atau setelah HP restart.
2. WorkManager mengirim antrean, pembayaran terlama lebih dulu, begitu ada internet. Setiap permintaan ditandatangani HMAC-SHA256 dengan timestamp dan nonce baru.
3. Gangguan sementara (tidak ada internet, server sibuk, jam HP salah) diulang otomatis dengan jeda yang makin panjang, mulai 30 detik hingga maksimal 5 jam, ditambah sapuan setiap 15 menit.
4. Server membuang duplikat berdasarkan ID event, jadi pengiriman ulang tidak membuat pembayaran ganda.

### Mengisi pengaturan

Buka QRIS Listener, lalu pada panel **Pengiriman ke server** tekan **Ubah pengaturan server** (form terbuka sendiri bila belum diatur).

| Kolom | Isi | Sumber |
|---|---|---|
| Alamat server | `https://ciganstore.ai.id` | Domain Worker (route `custom_domain` di `wrangler.jsonc` repo `Ciganstore-web`). Hanya origin `https://`, tanpa path. |
| ID perangkat | `primary-android` | Variabel `LISTENER_DEVICE_ID` di `wrangler.jsonc` repo `Ciganstore-web`. Harus sama persis. |
| Secret listener | 43 karakter base64url | `LISTENER_SHARED_SECRET` yang dibuat oleh `npm run secrets:production -- --listener` di repo `Ciganstore-web`. |

Membuat secret, dari folder repo `Ciganstore-web` di komputer:

```powershell
npm run secrets:production -- --listener
```

Script mengunggah secret ke Worker lalu menampilkannya **satu kali**. Simpan di password manager, lalu tempel ke kolom **Secret listener** di HP. Jika Worker sudah punya secret, script menolak menimpanya. Untuk mengganti secret, jalankan `npm run secrets:production -- --listener --rotate`, lalu segera masukkan secret baru di HP; sampai saat itu server menolak HP dan statusnya **Berhenti: periksa pengaturan**.

Setelah menekan **Simpan pengaturan**:

- Secret dienkripsi dengan kunci AES-256-GCM di Android Keystore dan tidak pernah ditampilkan lagi. Aplikasi hanya menulis **Secret tersimpan.** Kosongkan kolom secret bila tidak ingin menggantinya.
- Secret tidak ikut backup Android dan tidak pernah ditulis ke log. Jangan memotret layar saat secret masih terketik.
- Tekan **Tes koneksi**. Hasil yang benar: **Terhubung. Server menerima heartbeat dari HP ini.** Halaman status listener di admin web juga menjadi terhubung.

Untuk menguji server lokal (`npm run dev` di repo web), APK **debug** juga menerima `http://10.0.2.2:8787` (emulator) serta `http://127.0.0.1:8787` atau `http://localhost:8787` (HP lewat USB setelah `adb reverse tcp:8787 tcp:8787`). Pakai `LISTENER_SHARED_SECRET` dari `.dev.vars` lokal. APK rilis menolak semua alamat `http://`.

### Arti status pengiriman

| Status | Arti | Yang perlu dilakukan |
|---|---|---|
| **Aktif** | Pengaturan lengkap dan pengiriman terakhir tidak bermasalah. | Tidak ada. |
| **Belum diatur** | Alamat server atau secret belum disimpan. Pembayaran tetap disimpan di HP dan dikirim setelah pengaturan diisi. | Isi pengaturan, lalu **Tes koneksi**. |
| **Mencoba ulang otomatis** | Ada gangguan sementara; antrean dikirim ulang sendiri. | Baca **Masalah terakhir**. |
| **Berhenti: periksa pengaturan** | Server menolak ID perangkat atau secret. Pengiriman otomatis dihentikan supaya HP tidak terus mengirim permintaan yang pasti ditolak. | Periksa ID perangkat dan secret, simpan ulang, lalu **Tes koneksi**. |
| **Secret perlu diisi ulang** | Secret tersimpan tidak dapat dibuka oleh Keystore HP. | Masukkan ulang secret, lalu simpan. |

Arti **Masalah terakhir**:

| Pesan di aplikasi | Respons server | Yang dilakukan aplikasi |
|---|---|---|
| Tidak ada internet atau server tidak dapat dihubungi | Tidak ada koneksi, DNS/TLS gagal, atau timeout | Dikirim ulang otomatis saat koneksi kembali. |
| Jam HP salah | `401 stale_listener_request`: jam HP selisih lebih dari 5 menit | Dikirim ulang otomatis. Aktifkan **Tanggal & waktu otomatis**. Selisih kecil dikoreksi sendiri oleh server; aplikasi sudah memperingatkan bila selisihnya lebih dari 1 menit. |
| Server menolak ID perangkat atau secret | `401 invalid_listener_auth` | Berhenti sampai pengaturan disimpan ulang, **Kirim ulang sekarang** ditekan, atau **Tes koneksi** berhasil. |
| Server sedang sibuk atau gangguan | `429`, `5xx`, atau halaman error Cloudflare | Dikirim ulang otomatis dengan jeda yang makin panjang. |
| Alamat server tidak menjawab seperti server CiganStore | Redirect, `404`, atau jawaban yang bukan dari Worker | Dikirim ulang otomatis. Periksa alamat server. |
| Server menganggap permintaan terkirim dua kali | `409 replayed_request` | Langsung ditandatangani ulang dengan nonce baru. |
| Server menolak notifikasi pembayaran | `413`, `415`, atau `422` (`invalid_event`, `untrusted_notification`, `invalid_event_time`, dan sejenisnya) | Pembayaran itu ditandai **Ditolak server**, tidak dikirim ulang, dan tetap tampil di riwayat. Cocokkan manual di admin. |

Alasan penolakan yang paling mungkin:

- `untrusted_notification`: nama merchant di notifikasi berbeda dari `EXPECTED_MERCHANT` di `wrangler.jsonc` (saat ini `CiganStore, Digital & Kreatif`).
- `invalid_event_time`: notifikasi sudah lebih dari 24 jam saat sampai di server (misalnya HP mati atau offline lebih dari sehari) atau jam HP sangat salah. Riwayat lama dari sebelum pengiriman diaktifkan juga ikut dikirim sekali dan bisa ditolak dengan alasan ini; itu wajar.

Setiap baris riwayat lokal menampilkan statusnya: **Menunggu dikirim**, **Menunggu dikirim ulang**, **Tertahan** (ID perangkat atau secret ditolak), **Terkirim** (beserta hasil pencocokan server: cocok dengan pesanan, perlu dicek di admin, belum cocok, atau terdeteksi ganda), atau **Ditolak server**.

Tombol di panel pengiriman:

- **Kirim ulang sekarang** mencabut status **Berhenti**, mengembalikan pembayaran yang tertahan ke antrean, lalu mengirim antrean begitu ada internet. Pembayaran yang **Ditolak server** tidak dikirim ulang.
- **Tes koneksi** mengirim satu heartbeat bertanda tangan dan langsung menampilkan hasilnya.

### Syarat HP merchant

Pengiriman hanya berjalan bila aplikasi tetap hidup di latar belakang:

1. Notifikasi pembayaran GoPay Merchant tidak dimatikan.
2. Akses notifikasi QRIS Listener aktif dan **Koneksi listener** menampilkan **Terhubung**.
3. Penggunaan baterai QRIS Listener dan GoPay Merchant diatur ke **Tidak dibatasi** atau **No restrictions**.
4. Pada Xiaomi, Redmi, atau POCO (MIUI/HyperOS): **Mulai otomatis/Autostart** QRIS Listener aktif, QRIS Listener dikunci di layar aplikasi terbaru, dan tidak dibersihkan lewat **Pembersih/One-tap clean**. HyperOS mematikan aplikasi latar belakang; tanpa langkah ini heartbeat 15 menit berhenti dan admin melihat listener terputus.
5. **Tanggal & waktu otomatis** aktif.
6. Ada internet. Pembayaran yang masuk saat offline tetap disimpan dan dikirim begitu online, selama belum lewat 24 jam.

## Pengujian Rp1

1. Pastikan kanal notifikasi pembayaran GoPay Merchant tidak dimatikan. Mode senyap masih dapat bekerja selama notifikasinya tetap diposting oleh Android.
2. Tutup layar QRIS Listener untuk membuktikan listener bekerja di latar belakang.
3. Lakukan satu pembayaran QRIS statis sebesar Rp1.
4. Tunggu notifikasi GoPay Merchant muncul.
5. Buka kembali QRIS Listener.
6. Pastikan nominal, merchant, dan waktu muncul tepat satu kali pada riwayat lokal.
7. Bila pengiriman ke server sudah diatur, pastikan baris itu berubah menjadi **Terkirim** dalam beberapa detik (selama ada internet) dan pembayaran Rp1 muncul di menu Pembayaran admin web sebagai pembayaran yang belum cocok dengan pesanan.

Jika pembayaran tidak muncul, periksa kembali akses notifikasi. Pada ponsel yang agresif membatasi aplikasi latar belakang, buka pengaturan baterai aplikasi dan ubah penggunaan baterai GoPay Merchant serta QRIS Listener menjadi **Tidak dibatasi** atau **Unrestricted**, lalu ulangi tes.

Pada Xiaomi, Redmi, atau POCO dengan MIUI/HyperOS, aktifkan juga **Mulai otomatis/Autostart**, kunci QRIS Listener di layar aplikasi terbaru, dan jangan menutupnya melalui **Pembersih/One-tap clean**. Pembersih HyperOS dapat mematikan listener meskipun halaman akses notifikasi masih menunjukkan izin aktif. Setelah pembersihan atau restart ponsel, buka QRIS Listener dan pastikan status berubah dari **Menghubungkan ulang** menjadi **Terhubung** sebelum menerima pembayaran.

## Riwayat lokal

- Tombol **Hapus** menghapus pembayaran yang sudah **Terkirim** atau **Ditolak server** setelah konfirmasi. Pembayaran yang masih menunggu dikirim atau tertahan selalu disimpan, jadi menghapus riwayat tidak pernah menghilangkan pembayaran yang belum sampai ke server. Cocokkan dulu pembayaran yang ditolak di admin sebelum menghapusnya.
- Menghapus riwayat QRIS Listener tidak mengubah transaksi di GoPay Merchant maupun data di server CiganStore.
- Menghapus instalasi APK juga menghapus database lokal aplikasi, termasuk antrean yang belum terkirim dan pengaturan server.
- Notifikasi QRIS dengan format yang berubah akan diabaikan secara aman dan hanya menambah penghitung diagnostik tanpa menyimpan isinya.

## Batas saat ini

- Pencocokan format notifikasi masih berdasarkan contoh nyata yang diberikan pengguna dan harus diverifikasi pada ponsel merchant.
- Aplikasi hanya mengirim kandidat pembayaran. Pencocokan pesanan, status `PAID`, dan konfirmasi manual dilakukan oleh server dan admin CiganStore.
- APK debug memakai kunci debug lokal dan hanya untuk perangkat uji. HP merchant memakai APK rilis (lihat **Build rilis untuk HP merchant**).
