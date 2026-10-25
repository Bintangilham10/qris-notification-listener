package id.ciganstore.qrislistener.notification

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.data.PaymentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationProcessorTest {
    private val validPayload = NotificationPayload(
        packageName = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE,
        notificationKey = "key-17",
        postedAt = 1_777_000_000_000,
        title = PaymentNotificationParser.STATIC_QRIS_TITLE,
        body = "Rp 25.000 di CiganStore, Digital & Kreatif.",
    )

    @Test
    fun `first valid notification is accepted with parsed fields`() {
        val repository = FakePaymentRepository()
        val captureTime = validPayload.postedAt + 90_000
        val result = PaymentNotificationProcessor(repository) { captureTime }.process(validPayload)

        assertTrue(result is ProcessResult.Accepted)
        val event = (result as ProcessResult.Accepted).event
        assertEquals(25_000L, event.amount)
        assertEquals("CiganStore, Digital & Kreatif", event.merchant)
        // The notification post time is what the server receives as occurredAt.
        assertEquals(validPayload.postedAt, event.postedAt)
        assertEquals(captureTime, event.capturedAt)
        assertEquals(PaymentEvent.TYPE_QRIS_STATIC_PAYMENT, event.type)
        assertEquals(PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE, event.sourcePackage)
    }

    @Test
    fun `duplicate notification is inserted only once`() {
        val repository = FakePaymentRepository()
        val processor = PaymentNotificationProcessor(repository)

        assertTrue(processor.process(validPayload) is ProcessResult.Accepted)
        assertEquals(ProcessResult.Duplicate, processor.process(validPayload))
        assertEquals(1, repository.events.size)
    }

    @Test
    fun `invalid notification is ignored without repository insertion`() {
        val repository = FakePaymentRepository()
        val result = PaymentNotificationProcessor(repository).process(
            validPayload.copy(packageName = "com.example.unrelated"),
        )

        assertEquals(ProcessResult.Ignored, result)
        assertTrue(repository.events.isEmpty())
    }

    private class FakePaymentRepository : PaymentRepository {
        val events = linkedMapOf<String, PaymentEvent>()

        override fun insertIfNew(event: PaymentEvent): Boolean =
            events.putIfAbsent(event.eventId, event) == null

        override fun listNewest(limit: Int): List<PaymentEvent> =
            events.values.sortedByDescending { it.postedAt }.take(limit)

        override fun clear() {
            events.clear()
        }
    }
}
