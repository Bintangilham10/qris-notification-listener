package id.ciganstore.qrislistener.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentEventIdTest {
    private val payload = NotificationPayload(
        packageName = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE,
        notificationKey = "key-17",
        postedAt = 1_777_000_000_000,
        title = PaymentNotificationParser.STATIC_QRIS_TITLE,
        body = "Rp 1 di CiganStore, Digital & Kreatif.",
    )

    @Test
    fun `same notification has a stable lowercase sha256 id`() {
        val first = PaymentEventId.from(payload)
        val second = PaymentEventId.from(payload.copy())

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `changing post time changes the event id`() {
        assertNotEquals(
            PaymentEventId.from(payload),
            PaymentEventId.from(payload.copy(postedAt = payload.postedAt + 1)),
        )
    }
}

