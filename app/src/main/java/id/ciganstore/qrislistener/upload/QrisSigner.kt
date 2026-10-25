package id.ciganstore.qrislistener.upload

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs listener requests with the Worker's `QRIS1` scheme:
 *
 * ```
 * canonical = "QRIS1\nPOST\n<path>\n<deviceId>\n<timestamp>\n<nonce>\n<lowercase hex SHA-256 of body>"
 * signature = base64url-without-padding(HMAC-SHA256(key = base64url-decoded secret, canonical as UTF-8))
 * ```
 */
class QrisSigner(secretKey: ByteArray) {
    private val key: SecretKeySpec

    init {
        require(secretKey.size >= ListenerProtocol.MIN_SECRET_BYTES) {
            "The listener secret must contain at least ${ListenerProtocol.MIN_SECRET_BYTES} bytes"
        }
        // SecretKeySpec keeps its own copy, so the caller can wipe secretKey afterwards.
        key = SecretKeySpec(secretKey, HMAC_ALGORITHM)
    }

    /** The 43-character base64url (no padding) HMAC-SHA256 of [canonical]. */
    fun sign(canonical: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return base64Url(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val NONCE_BYTES = 24
        private val hexDigits = "0123456789abcdef".toCharArray()
        private val secureRandom = SecureRandom()

        fun canonicalString(
            path: String,
            deviceId: String,
            timestamp: String,
            nonce: String,
            bodySha256Hex: String,
        ): String = listOf(
            ListenerProtocol.SIGNATURE_SCHEME,
            ListenerProtocol.METHOD,
            path,
            deviceId,
            timestamp,
            nonce,
            bodySha256Hex,
        ).joinToString(separator = "\n")

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hexDigits[value ushr 4])
                    append(hexDigits[value and 0x0f])
                }
            }
        }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** 24 random bytes, i.e. 32 base64url characters (the Worker accepts 16 to 86). */
        fun newNonce(): String {
            val bytes = ByteArray(NONCE_BYTES)
            secureRandom.nextBytes(bytes)
            return base64Url(bytes)
        }
    }
}

/** One signed request. Create a new one for every network attempt; never reuse one for a retry. */
class ListenerRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** The phone clock is so far off that the 10-digit Unix timestamp the Worker requires cannot be built. */
class InvalidDeviceClockException : Exception("The device clock is outside the supported range")

/**
 * Builds signed requests. Each [create] call reads the clock and draws a nonce at that moment, so a
 * retry is always signed again with a fresh timestamp, nonce and signature.
 */
class ListenerRequestFactory(
    private val baseUrl: String,
    private val deviceId: String,
    private val signer: QrisSigner,
    appVersion: String?,
    private val nowMillis: () -> Long,
    private val newNonce: () -> String = { QrisSigner.newNonce() },
) {
    private val appVersion: String? = appVersion?.takeIf { ListenerProtocol.APP_VERSION_PATTERN.matches(it) }

    init {
        require(ListenerProtocol.DEVICE_ID_PATTERN.matches(deviceId)) { "Invalid listener device ID" }
    }

    @Throws(InvalidDeviceClockException::class)
    fun create(path: String, body: ByteArray): ListenerRequest {
        val timestamp = unixTimestampSeconds(nowMillis())
        val nonce = newNonce()
        val canonical = QrisSigner.canonicalString(path, deviceId, timestamp, nonce, QrisSigner.sha256Hex(body))

        val headers = LinkedHashMap<String, String>()
        headers[ListenerProtocol.HEADER_CONTENT_TYPE] = ListenerProtocol.CONTENT_TYPE
        headers[ListenerProtocol.HEADER_ACCEPT] = ListenerProtocol.CONTENT_TYPE
        headers[ListenerProtocol.HEADER_VERSION] = ListenerProtocol.VERSION
        headers[ListenerProtocol.HEADER_DEVICE_ID] = deviceId
        headers[ListenerProtocol.HEADER_TIMESTAMP] = timestamp
        headers[ListenerProtocol.HEADER_NONCE] = nonce
        headers[ListenerProtocol.HEADER_SIGNATURE] = signer.sign(canonical)
        appVersion?.let { headers[ListenerProtocol.HEADER_APP_VERSION] = it }

        return ListenerRequest(url = baseUrl + path, headers = headers, body = body)
    }

    companion object {
        private const val MIN_TEN_DIGIT_SECONDS = 1_000_000_000L
        private const val MAX_TEN_DIGIT_SECONDS = 9_999_999_999L

        /** Unix time in whole seconds as the exactly-10-digit string the Worker accepts. */
        @Throws(InvalidDeviceClockException::class)
        fun unixTimestampSeconds(epochMillis: Long): String {
            val seconds = Math.floorDiv(epochMillis, 1_000L)
            if (seconds < MIN_TEN_DIGIT_SECONDS || seconds > MAX_TEN_DIGIT_SECONDS) {
                throw InvalidDeviceClockException()
            }
            return seconds.toString()
        }
    }
}
