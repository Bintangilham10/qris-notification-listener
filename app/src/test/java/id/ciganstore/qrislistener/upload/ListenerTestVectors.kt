package id.ciganstore.qrislistener.upload

/**
 * The Worker contract's published test vectors. [SECRET] is a dummy test value (32 bytes of 0x03),
 * never a real listener secret.
 */
internal object ListenerTestVectors {
    const val SECRET = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"
    const val DEVICE_ID = "primary-android"
    const val TIMESTAMP = "1792900800"
    const val TIMESTAMP_MILLIS = 1_792_900_800_000L

    const val EVENT_NONCE = "abcdefghijklmnopQRSTUV"
    const val EVENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val EVENT_AMOUNT = 10_101L
    const val MERCHANT = "CiganStore, Digital & Kreatif"

    /** 2026-10-25T03:00:00.000Z */
    const val EVENT_OCCURRED_AT_MILLIS = 1_792_897_200_000L

    /** Pinned byte for byte: the signature covers the exact body bytes. */
    const val EVENT_BODY = "{\"eventId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
        "\"amount\":10101,\"merchant\":\"CiganStore, Digital & Kreatif\"," +
        "\"occurredAt\":\"2026-10-25T03:00:00.000Z\",\"packageName\":\"com.gojek.gopaymerchant\"," +
        "\"title\":\"Pembayaran QRIS statis diterima\"}"
    const val EVENT_BODY_SHA256 = "8c967ece71c82b2ac09c218f2d43523acdcf434c38117f9e3a8340ee1ce46e1a"
    const val EVENT_SIGNATURE = "i39WS-zSJM_vBfZkJRsKOjl8CCzityoFx3wXbZGS8j0"

    const val HEARTBEAT_NONCE = "heartbeatNonceValue12345"
    const val HEARTBEAT_BODY_SHA256 = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    const val HEARTBEAT_SIGNATURE = "_f0IdkblKOeNTSk4Jyoqw9wAFY_b2IfkOdy3wIEiCkw"

    fun secretKey(): ByteArray = requireNotNull(ListenerSettingsValidator.decodeSecret(SECRET))
}
