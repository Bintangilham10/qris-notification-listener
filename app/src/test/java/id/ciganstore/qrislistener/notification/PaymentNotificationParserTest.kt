package id.ciganstore.qrislistener.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(95_500L, PaymentNotificationParser.parse(payload(body = "Rp 95.500 di CiganStore."))?.amount)
    }

    @Test
    fun `accepts amount without a space after rupiah prefix`() {
        val result = PaymentNotificationParser.parse(
            payload(body = "Rp95.500 di CiganStore."),
        )

        assertEquals(95_500L, result?.amount)
        assertEquals("CiganStore", result?.merchant)
    }

    @Test
    fun `normalizes unicode whitespace and multiline body`() {
        val result = PaymentNotificationParser.parse(
            payload(
                title = "Pembayaran\u00A0QRIS\nstatis\u202Fditerima",
                body = "Rp\u00A095.500\u202Fdi\nCiganStore,\nDigital & Kreatif.",
            ),
        )

        assertEquals(95_500L, result?.amount)
        assertEquals("CiganStore, Digital & Kreatif", result?.merchant)
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
    fun `payment-like title does not loosen the accepted title`() {
        val changedTitle = "Pembayaran QRIS diterima"

        assertTrue(PaymentNotificationParser.isPaymentLikeTitle(changedTitle))
        assertNull(PaymentNotificationParser.parse(payload(title = changedTitle)))
    }

    @Test
    fun `identifies only payment-like diagnostic titles`() {
        assertTrue(PaymentNotificationParser.isPaymentLikeTitle("  PEMBAYARAN\nDITERIMA  "))
        assertTrue(PaymentNotificationParser.isPaymentLikeTitle("Info pembayaran QRIS"))
        assertFalse(PaymentNotificationParser.isPaymentLikeTitle("Promo menarik hari ini"))
        assertFalse(PaymentNotificationParser.isPaymentLikeTitle("QRIS sedang dipelihara"))
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
