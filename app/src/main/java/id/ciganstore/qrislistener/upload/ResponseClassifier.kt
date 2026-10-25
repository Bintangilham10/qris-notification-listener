package id.ciganstore.qrislistener.upload

/** How the uploader must react to one listener API response. */
enum class ResponseKind {
    /** 200/202 with `accepted: true`: the server stored the event (or already had it). */
    DELIVERED,

    /** 409 `replayed_request`: sign again with a new timestamp and nonce, then send again. */
    REPLAYED,

    /** 401 `invalid_listener_auth`: wrong secret or device ID. Stop sending until the settings change. */
    CONFIGURATION,

    /** 401 `stale_listener_request`: the phone clock is more than five minutes off. Retry later. */
    CLOCK_REJECTED,

    /** 413, 415 or 422 from the Worker: it will never accept this request. Keep the event and show it. */
    PERMANENT,

    /** 429, 5xx, non-JSON bodies (Cloudflare error pages) and anything unexpected: exponential backoff. */
    RETRY,
}

data class ServerResponse(
    val kind: ResponseKind,
    val httpStatus: Int,
    val errorCode: String? = null,
    val duplicate: Boolean = false,
    val matchStatus: String? = null,
    val serverTime: String? = null,
)

object ResponseClassifier {
    const val CODE_REPLAYED_REQUEST = "replayed_request"
    const val CODE_INVALID_AUTH = "invalid_listener_auth"
    const val CODE_STALE_REQUEST = "stale_listener_request"
    const val CODE_UNEXPECTED_RESPONSE = "unexpected_response"

    private val safeToken = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun classify(httpStatus: Int, body: String?): ServerResponse {
        val json = MiniJson.parseObject(body)
        // Only the Worker's own error envelope {"error":{"code":...}} is an answer from the Worker.
        // A Cloudflare HTML error page, a proxy or a wrong host has no code and is always retried, so
        // nothing but the Worker itself can stop delivery or mark an event as rejected.
        val errorCode = ((json?.get("error") as? Map<*, *>)?.get("code") as? String)
            ?.takeIf { safeToken.matches(it) }

        return when (httpStatus) {
            200, 202 -> accepted(httpStatus, json)
            401 -> when (errorCode) {
                CODE_INVALID_AUTH -> ServerResponse(ResponseKind.CONFIGURATION, httpStatus, errorCode)
                CODE_STALE_REQUEST -> ServerResponse(ResponseKind.CLOCK_REJECTED, httpStatus, errorCode)
                else -> retry(httpStatus, errorCode)
            }
            409 -> if (errorCode == CODE_REPLAYED_REQUEST) {
                ServerResponse(ResponseKind.REPLAYED, httpStatus, errorCode)
            } else {
                retry(httpStatus, errorCode)
            }
            413, 415, 422 -> if (errorCode != null) {
                ServerResponse(ResponseKind.PERMANENT, httpStatus, errorCode)
            } else {
                retry(httpStatus, null)
            }
            // Redirects are never followed (signed payment data must not be re-sent to another URL),
            // so a 3xx, like a 404 or any other status, is reported and retried with backoff.
            else -> retry(httpStatus, errorCode)
        }
    }

    private fun accepted(httpStatus: Int, json: Map<String, Any?>?): ServerResponse {
        // Only an explicit accepted:true from the Worker marks an event as delivered. A 2xx page from
        // anything else (a wrong host, a proxy) must never be mistaken for a delivery.
        if (json == null || json["accepted"] != true) {
            return retry(httpStatus, CODE_UNEXPECTED_RESPONSE)
        }
        return ServerResponse(
            kind = ResponseKind.DELIVERED,
            httpStatus = httpStatus,
            duplicate = json["duplicate"] == true,
            matchStatus = (json["matchStatus"] as? String)?.takeIf { safeToken.matches(it) },
            serverTime = (json["serverTime"] as? String)?.takeIf { it.length <= MAX_SERVER_TIME_LENGTH },
        )
    }

    private fun retry(httpStatus: Int, errorCode: String?): ServerResponse =
        ServerResponse(ResponseKind.RETRY, httpStatus, errorCode)

    private const val MAX_SERVER_TIME_LENGTH = 64
}
