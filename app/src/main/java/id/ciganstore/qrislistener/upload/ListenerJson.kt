package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.notification.PaymentNotificationParser
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** JSON written to and read from the listener API. */
object ListenerJson {
    private val isoUtcMillisFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)
    private val hexDigits = "0123456789abcdef".toCharArray()

    /**
     * The event body with exactly the Worker's field names and order and no whitespace. The title and
     * package are the canonical constants because the Worker compares them exactly, while the parser
     * matches the title case-insensitively. `occurredAt` is the raw phone post time; the Worker
     * corrects phone clock skew itself.
     */
    fun eventBody(event: PaymentEvent): String = eventBody(
        eventId = event.eventId,
        amount = event.amount,
        merchant = event.merchant,
        occurredAtMillis = event.postedAt,
    )

    fun eventBody(eventId: String, amount: Long, merchant: String, occurredAtMillis: Long): String =
        buildString {
            append("{\"eventId\":").append(quote(eventId))
            append(",\"amount\":").append(amount)
            append(",\"merchant\":").append(quote(merchant))
            append(",\"occurredAt\":").append(quote(isoUtcMillis(occurredAtMillis)))
            append(",\"packageName\":").append(quote(PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE))
            append(",\"title\":").append(quote(PaymentNotificationParser.STATIC_QRIS_TITLE))
            append('}')
        }

    /** ISO-8601 in UTC, always with milliseconds, e.g. `2026-10-25T03:04:05.123Z`. */
    fun isoUtcMillis(epochMillis: Long): String = isoUtcMillisFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun parseIsoMillis(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (error: DateTimeParseException) {
        null
    }

    /** A JSON string literal, escaped the way `JSON.stringify` escapes it. */
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == '\b' -> append("\\b")
                char == '\u000C' -> append("\\f")
                char < ' ' -> appendUnicodeEscape(char)
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    append(char).append(value[index + 1])
                    index++
                }
                Character.isSurrogate(char) -> appendUnicodeEscape(char)
                else -> append(char)
            }
            index++
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(char: Char) {
        val code = char.code
        append("\\u")
        append(hexDigits[(code shr 12) and 0xF])
        append(hexDigits[(code shr 8) and 0xF])
        append(hexDigits[(code shr 4) and 0xF])
        append(hexDigits[code and 0xF])
    }
}
