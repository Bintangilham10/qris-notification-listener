package id.ciganstore.qrislistener.upload

/** Version 1 of the CiganStore Worker listener API. These values must match the Worker exactly. */
object ListenerProtocol {
    const val VERSION = "1"
    const val SIGNATURE_SCHEME = "QRIS1"
    const val METHOD = "POST"
    const val EVENTS_PATH = "/api/v1/listener/events"
    const val HEARTBEAT_PATH = "/api/v1/listener/heartbeat"
    const val HEARTBEAT_BODY = "{}"
    const val CONTENT_TYPE = "application/json"
    const val MAX_BODY_BYTES = 2_048
    const val MIN_SECRET_BYTES = 32

    /** The UI warns above this |server time - phone time|; the server itself rejects above 300 s. */
    const val CLOCK_WARNING_MILLIS = 60_000L

    const val DEFAULT_BASE_URL = "https://ciganstore.ai.id"
    const val DEFAULT_DEVICE_ID = "primary-android"

    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_ACCEPT = "Accept"
    const val HEADER_VERSION = "X-QRIS-Version"
    const val HEADER_DEVICE_ID = "X-QRIS-Device-Id"
    const val HEADER_TIMESTAMP = "X-QRIS-Timestamp"
    const val HEADER_NONCE = "X-QRIS-Nonce"
    const val HEADER_SIGNATURE = "X-QRIS-Signature"
    const val HEADER_APP_VERSION = "X-QRIS-App-Version"

    val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{3,64}$")
    val TIMESTAMP_PATTERN = Regex("^[0-9]{10}$")
    val NONCE_PATTERN = Regex("^[A-Za-z0-9_-]{16,86}$")
    val SIGNATURE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    val APP_VERSION_PATTERN = Regex("^[A-Za-z0-9._-]{1,32}$")
}
