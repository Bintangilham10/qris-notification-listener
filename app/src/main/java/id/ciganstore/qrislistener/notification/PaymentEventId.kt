package id.ciganstore.qrislistener.notification

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PaymentEventId {
    private val hex = "0123456789abcdef".toCharArray()

    fun from(payload: NotificationPayload): String {
        val digestInput = listOf(
            payload.packageName,
            payload.notificationKey,
            payload.postedAt.toString(),
            payload.title,
            payload.body,
        ).joinToString(separator = "\n")

        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(digestInput.toByteArray(StandardCharsets.UTF_8))

        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}

