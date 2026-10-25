package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.notification.PaymentNotificationParser
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_AMOUNT
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_BODY
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_ID
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_OCCURRED_AT_MILLIS
import id.ciganstore.qrislistener.upload.ListenerTestVectors.MERCHANT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerJsonTest {
    private val event = PaymentEvent(
        eventId = EVENT_ID,
        type = PaymentEvent.TYPE_QRIS_STATIC_PAYMENT,
        amount = EVENT_AMOUNT,
        merchant = MERCHANT,
        postedAt = EVENT_OCCURRED_AT_MILLIS,
        // Captured a minute later (listener replay after reconnect); occurredAt must not use it.
        capturedAt = EVENT_OCCURRED_AT_MILLIS + 60_000,
        sourcePackage = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE,
    )

    @Test
    fun `event body is byte for byte the Worker test vector`() {
        assertEquals(EVENT_BODY, ListenerJson.eventBody(event))
        assertEquals(
            EVENT_BODY,
            ListenerJson.eventBody(EVENT_ID, EVENT_AMOUNT, MERCHANT, EVENT_OCCURRED_AT_MILLIS),
        )
    }

    @Test
    fun `body always sends the exact title and package the Worker compares`() {
        val body = MiniJson.parseObject(ListenerJson.eventBody(event))!!

        assertEquals(listOf("eventId", "amount", "merchant", "occurredAt", "packageName", "title"), body.keys.toList())
        assertEquals("com.gojek.gopaymerchant", body["packageName"])
        assertEquals("Pembayaran QRIS statis diterima", body["title"])
        assertEquals(10_101.0, body["amount"])
    }

    @Test
    fun `occurredAt is UTC ISO-8601 with milliseconds`() {
        assertEquals("2026-10-25T03:00:00.000Z", ListenerJson.isoUtcMillis(EVENT_OCCURRED_AT_MILLIS))
        assertEquals("2026-10-25T03:00:00.007Z", ListenerJson.isoUtcMillis(EVENT_OCCURRED_AT_MILLIS + 7))
        assertEquals("2026-10-25T10:00:00.000Z", ListenerJson.isoUtcMillis(EVENT_OCCURRED_AT_MILLIS + 7 * 3_600_000))
    }

    @Test
    fun `large amounts are written as plain integers`() {
        val body = ListenerJson.eventBody(EVENT_ID, 1_000_000_000L, MERCHANT, EVENT_OCCURRED_AT_MILLIS)

        assertEquals(true, body.contains("\"amount\":1000000000,"))
    }

    @Test
    fun `strings are escaped like JSON stringify`() {
        assertEquals("\"Toko \\\"A\\\" \\\\ B\"", ListenerJson.quote("Toko \"A\" \\ B"))
        assertEquals("\"a\\nb\\tc\\rd\\be\\ff\"", ListenerJson.quote("a\nb\tc\rd\be\u000Cf"))
        assertEquals("\"\\u0001\\u001f\"", ListenerJson.quote("\u0001\u001F"))
        // Non-ASCII, emoji (a valid surrogate pair) and "/" stay as they are.
        assertEquals("\"Kafé 😀 a/b\"", ListenerJson.quote("Kafé 😀 a/b"))
        // A lone surrogate cannot be UTF-8 encoded, so it is escaped (lowercase, as JSON.stringify does).
        assertEquals("\"\\ud800x\"", ListenerJson.quote("\uD800x"))
    }

    @Test
    fun `merchant with quotes stays valid JSON`() {
        val body = ListenerJson.eventBody(EVENT_ID, 1, "Toko \"Satu\" & Dua", EVENT_OCCURRED_AT_MILLIS)

        assertEquals("Toko \"Satu\" & Dua", MiniJson.parseObject(body)!!["merchant"])
    }

    @Test
    fun `reads the server time of a heartbeat`() {
        assertEquals(1_792_900_800_123L, ListenerJson.parseIsoMillis("2026-10-25T04:00:00.123Z"))
        assertNull(ListenerJson.parseIsoMillis("bukan waktu"))
    }
}
