# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Delegated: aplikasi native Android dengan Kotlin dan Android Views agar APK kecil, mudah diaudit, dan tidak memerlukan framework lintas platform.

## Users

Pengguna utama adalah pemilik GoPay Merchant yang menjalankan ponsel Android miliknya sendiri dan ingin mengetahui apakah notifikasi pembayaran QRIS terbaca dengan benar sebelum sistem dihubungkan ke dashboard web.

## Product Purpose

QRIS Listener membaca notifikasi pembayaran dari aplikasi resmi GoPay Merchant, mengambil nominal dan nama merchant, lalu menyimpan riwayatnya secara lokal. Versi pertama dianggap berhasil bila pengguna dapat melihat status akses notifikasi, status listener, transaksi terakhir, dan riwayat pembayaran langsung di ponsel.

## Positioning

Mekanismenya sengaja lokal dan minimal: data pembayaran berasal dari notifikasi sistem pada ponsel merchant, bukan dari login tidak resmi, penyimpanan token GoPay, atau pengiriman data ke server.

## Operating Context

- Ponsel Android merchant harus menyala dan aplikasi GoPay Merchant harus dapat menampilkan notifikasi.
- Pengguna mengaktifkan Notification Access melalui pengaturan sistem Android.
- Pengguna membuka QRIS Listener untuk memeriksa kesiapan listener dan riwayat hasil pembacaan.
- Pengujian pembayaran nyata menggunakan notifikasi berjudul `Pembayaran QRIS statis diterima` dengan isi seperti `Rp 1 di CiganStore, Digital & Kreatif.`

## Capabilities and Constraints

- Hanya menerima notifikasi dari package resmi GoPay Merchant `com.gojek.gopaymerchant`.
- Hanya menerima pola notifikasi QRIS yang disepakati dan nominal rupiah bulat positif.
- Mencegah transaksi yang sama tersimpan dua kali.
- Menyimpan data hanya pada SQLite internal aplikasi.
- Tidak meminta izin internet dan belum terhubung ke web atau API apa pun.
- Menyediakan hapus riwayat dengan konfirmasi.
- Pencocokan harus gagal secara aman bila format notifikasi berubah.
- Asumsi yang belum terverifikasi: package dan teks notifikasi identik pada perangkat pengguna saat APK diuji langsung.

## Evidence on Hand

- Tangkapan layar notifikasi GoPay Merchant yang diberikan pengguna.
- Spesifikasi yang disetujui di `docs/superpowers/specs/2026-08-24-qris-notification-listener-design.md`.
- Belum ada identitas visual, logo, data produksi, atau bukti pengujian pada ponsel fisik.

## Product Principles

- Privasi lokal lebih penting daripada otomatisasi server pada versi pertama.
- Status sistem harus terlihat dan dapat dipahami tanpa istilah teknis yang berlebihan.
- Notifikasi yang meragukan tidak boleh dianggap sebagai pembayaran valid.
- Riwayat harus tetap ada setelah aplikasi ditutup atau ponsel dihidupkan ulang.
- Izin aplikasi harus sesedikit mungkin dan mudah diaudit.

## Accessibility & Inclusion

Antarmuka harus mengikuti ukuran teks sistem Android, mempertahankan target sentuh minimal 48 dp, memberi status melalui teks selain warna, dan tetap dapat digunakan pada mode gelap serta font scale besar.
