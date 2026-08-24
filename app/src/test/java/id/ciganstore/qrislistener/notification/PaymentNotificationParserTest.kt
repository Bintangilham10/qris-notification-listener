package id.ciganstore.qrislistener.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNotificationParserTest {
    private fun payload(
        packageName: String = "com.gojek.gopaymerchant",
        title: String = "Pembayaran QRIS statis diterima",
        body: String = "Rp 1 di CiganStore, Digital & Kreatif.",
    ) = NotificationPayload(
        packageName = packageName,
        notificationKey = "notification-key",
        postedAt = 1_777_000_000_000,
        title = title,
        body = body,
    )

    @Test
    fun `parses one rupiah from the confirmed notification`() {
        val result = PaymentNotificationParser.parse(payload())

        assertEquals(1L, result?.amount)
        assertEquals("CiganStore, Digital & Kreatif", result?.merchant)
    }

    @Test
    fun `parses valid Indonesian thousands separators`() {
        assertEquals(1_000L, PaymentNotificationParser.parse(payload(body = "Rp 1.000 di Toko Satu."))?.amount)
        assertEquals(25_000L, PaymentNotificationParser.parse(payload(body = "Rp 25.000 di CiganStore, Digital & Kreatif."))?.amount)
    }

    @Test
    fun `keeps merchant punctuation and accepts body without final period`() {
        val result = PaymentNotificationParser.parse(payload(body = "Rp 500 di Toko A, B & C"))

        assertEquals("Toko A, B & C", result?.merchant)
    }

    @Test
    fun `normalizes surrounding title whitespace and letter case`() {
        val result = PaymentNotificationParser.parse(
            payload(title = "  PEMBAYARAN QRIS STATIS DITERIMA  "),
        )

        assertEquals(1L, result?.amount)
    }

    @Test
    fun `rejects notification from a different package`() {
        assertNull(PaymentNotificationParser.parse(payload(packageName = "com.example.fake")))
    }

    @Test
    fun `rejects a different notification title`() {
        assertNull(PaymentNotificationParser.parse(payload(title = "Saldo bertambah")))
    }

    @Test
    fun `rejects empty merchant`() {
        assertNull(PaymentNotificationParser.parse(payload(body = "Rp 1 di ")))
    }

    @Test
    fun `rejects zero negative decimal and malformed amounts`() {
        listOf(
            "Rp 0 di CiganStore.",
            "Rp -1 di CiganStore.",
            "Rp 1,50 di CiganStore.",
            "Rp 25.00 di CiganStore.",
            "Rp satu di CiganStore.",
        ).forEach { body ->
            assertNull("Should reject: $body", PaymentNotificationParser.parse(payload(body = body)))
        }
    }
}
