package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.data.UploadQueue
import id.ciganstore.qrislistener.notification.PaymentNotificationParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Raw HTTP outcome of one POST. */
data class HttpResult(val status: Int, val body: String?)

/**
 * Sends one POST to the listener API. Implementations must sign at send time, so every call carries a
 * fresh timestamp, nonce and signature: the Worker rejects reused nonces and requests older than 300 s.
 */
fun interface ListenerTransport {
    @Throws(IOException::class, InvalidDeviceClockException::class)
    fun post(path: String, body: ByteArray): HttpResult
}

enum class UploadProblemKind(
    /** True when the same request may succeed later without anyone changing anything. */
    val isTransient: Boolean,
) {
    NETWORK(true),
    CLOCK_INVALID(true),
    CLOCK_REJECTED(true),
    AUTH_REJECTED(false),
    WRONG_SERVER(true),
    SERVER_BUSY(true),
    SERVER_ERROR(true),
    UNEXPECTED_STATUS(true),
    REPLAYED(true),
    EVENT_REJECTED(false),
    SECRET_UNAVAILABLE(false),
}

data class UploadProblem(
    val kind: UploadProblemKind,
    val httpStatus: Int? = null,
    val detail: String? = null,
) {
    /** Short technical summary stored with events and shown in the UI. Never contains secrets. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        if (httpStatus != null) parts += "HTTP $httpStatus"
        if (!detail.isNullOrBlank()) parts += detail
        return if (parts.isEmpty()) kind.name.lowercase(Locale.ROOT) else parts.joinToString(" ")
    }
}

/** Device-level delivery diagnostics (last success, heartbeat, clock skew, errors, configuration block). */
interface UploadStatusStore {
    fun configurationBlock(): UploadProblem?
    fun recordDelivered(atMillis: Long)
    fun recordHeartbeat(atMillis: Long, clockSkewMillis: Long?)
    fun recordProblem(problem: UploadProblem, atMillis: Long)
    fun blockConfiguration(problem: UploadProblem, atMillis: Long)
    fun clearConfigurationBlock()
}

/**
 * Delivers stored payment events to the Worker, oldest first. Events are only read from the local
 * outbox, so an event is always stored before any upload attempt. Re-sending a delivered event is safe
 * because the Worker deduplicates by event ID.
 */
class ListenerUploader(
    private val queue: UploadQueue,
    private val status: UploadStatusStore,
    private val transport: ListenerTransport,
    private val nowMillis: () -> Long,
) {
    sealed interface UploadResult {
        val delivered: Int

        data class Completed(override val delivered: Int, val rejected: Int) : UploadResult
        data class RetryLater(override val delivered: Int, val problem: UploadProblem) : UploadResult
        data class Blocked(override val delivered: Int, val problem: UploadProblem) : UploadResult
        data class Stopped(override val delivered: Int) : UploadResult
    }

    sealed interface HeartbeatResult {
        data class Success(val clockSkewMillis: Long?, val releasedEvents: Int) : HeartbeatResult
        data class Failure(val problem: UploadProblem) : HeartbeatResult
    }

    /** True while the server's 401 `invalid_listener_auth` stands; nothing is sent automatically. */
    fun isConfigurationBlocked(): Boolean = status.configurationBlock() != null

    fun uploadPending(isStopped: () -> Boolean = { false }): UploadResult {
        status.configurationBlock()?.let { problem ->
            // Configuration rejected earlier: do not hit the server again. New events are parked too,
            // so the UI shows them as held. Saving settings, "Kirim ulang sekarang" or a successful
            // "Tes koneksi" releases them.
            queue.markBlockedByConfiguration(null, nowMillis(), problem.describe())
            return UploadResult.Blocked(delivered = 0, problem = problem)
        }
        // No block: events parked by an earlier block (for example by a request that was still in
        // flight while the settings were saved) go back to the queue.
        queue.releaseConfigurationBlock()

        var delivered = 0
        var rejected = 0
        repeat(MAX_BATCHES) {
            val batch = queue.pendingUploads(BATCH_SIZE)
            if (batch.isEmpty()) return UploadResult.Completed(delivered, rejected)

            for (event in batch) {
                if (isStopped()) return UploadResult.Stopped(delivered)
                when (val outcome = deliver(event)) {
                    is Outcome.Delivered -> {
                        val now = nowMillis()
                        queue.markUploaded(event.eventId, now, outcome.response.matchStatus)
                        status.recordDelivered(now)
                        delivered++
                    }

                    is Outcome.Failed -> {
                        val now = nowMillis()
                        val problem = outcome.problem
                        when (outcome.reaction) {
                            Reaction.REJECT_EVENT -> {
                                queue.markFailedPermanent(event.eventId, now, problem.describe())
                                status.recordProblem(problem, now)
                                rejected++
                            }

                            Reaction.BLOCK_CONFIGURATION -> {
                                queue.markBlockedByConfiguration(event.eventId, now, problem.describe())
                                status.blockConfiguration(problem, now)
                                return UploadResult.Blocked(delivered, problem)
                            }

                            Reaction.RETRY_LATER -> {
                                // Keep the order: stop here instead of skipping to newer events.
                                queue.markAttemptFailed(event.eventId, now, problem.describe())
                                status.recordProblem(problem, now)
                                return UploadResult.RetryLater(delivered, problem)
                            }
                        }
                    }
                }
            }
        }
        return UploadResult.Completed(delivered, rejected)
    }

    /**
     * Signed POST of `{}` to the heartbeat endpoint. Records the clock skew (server time minus phone
     * time). An accepted heartbeat proves URL, device ID and secret work, so it lifts a configuration
     * block and returns parked events to the queue.
     */
    fun sendHeartbeat(): HeartbeatResult {
        val body = ListenerProtocol.HEARTBEAT_BODY.toByteArray(StandardCharsets.UTF_8)
        return when (val exchange = exchange(ListenerProtocol.HEARTBEAT_PATH, body)) {
            is Exchange.LocalFailure -> {
                status.recordProblem(exchange.problem, nowMillis())
                HeartbeatResult.Failure(exchange.problem)
            }

            is Exchange.Answered -> {
                val response = exchange.response
                if (response.kind == ResponseKind.DELIVERED) {
                    val deviceMillis = exchange.sentAt + (exchange.receivedAt - exchange.sentAt) / 2
                    val skew = response.serverTime
                        ?.let(ListenerJson::parseIsoMillis)
                        ?.let { serverMillis -> serverMillis - deviceMillis }
                    status.recordHeartbeat(exchange.receivedAt, skew)
                    status.clearConfigurationBlock()
                    HeartbeatResult.Success(clockSkewMillis = skew, releasedEvents = queue.releaseConfigurationBlock())
                } else {
                    val problem = problemFor(response)
                    val now = nowMillis()
                    if (reactionFor(response) == Reaction.BLOCK_CONFIGURATION) {
                        status.blockConfiguration(problem, now)
                        queue.markBlockedByConfiguration(null, now, problem.describe())
                    } else {
                        status.recordProblem(problem, now)
                    }
                    HeartbeatResult.Failure(problem)
                }
            }
        }
    }

    private fun deliver(event: PaymentEvent): Outcome {
        if (
            event.type != PaymentEvent.TYPE_QRIS_STATIC_PAYMENT ||
            event.sourcePackage != PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE
        ) {
            return Outcome.Failed(
                Reaction.REJECT_EVENT,
                UploadProblem(UploadProblemKind.EVENT_REJECTED, detail = "unsupported_event"),
            )
        }
        val body = ListenerJson.eventBody(event).toByteArray(StandardCharsets.UTF_8)
        if (body.size > ListenerProtocol.MAX_BODY_BYTES) {
            return Outcome.Failed(
                Reaction.REJECT_EVENT,
                UploadProblem(UploadProblemKind.EVENT_REJECTED, detail = "payload_too_large"),
            )
        }

        return when (val exchange = exchange(ListenerProtocol.EVENTS_PATH, body)) {
            is Exchange.LocalFailure -> Outcome.Failed(exchange.reaction, exchange.problem)
            is Exchange.Answered -> if (exchange.response.kind == ResponseKind.DELIVERED) {
                Outcome.Delivered(exchange.response)
            } else {
                Outcome.Failed(reactionFor(exchange.response), problemFor(exchange.response))
            }
        }
    }

    private fun exchange(path: String, body: ByteArray): Exchange {
        var attempt = 1
        while (true) {
            val sentAt = nowMillis()
            val result = try {
                transport.post(path, body)
            } catch (error: InvalidDeviceClockException) {
                return Exchange.LocalFailure(Reaction.RETRY_LATER, UploadProblem(UploadProblemKind.CLOCK_INVALID))
            } catch (error: IOException) {
                return Exchange.LocalFailure(
                    Reaction.RETRY_LATER,
                    UploadProblem(UploadProblemKind.NETWORK, detail = error.javaClass.simpleName),
                )
            }
            val receivedAt = nowMillis()
            val response = ResponseClassifier.classify(result.status, result.body)
            // 409 replayed_request: retry once right away. The transport signs every call again, so
            // the retry carries a new timestamp, nonce and signature.
            if (response.kind == ResponseKind.REPLAYED && attempt < MAX_ATTEMPTS_PER_REQUEST) {
                attempt++
                continue
            }
            return Exchange.Answered(response, sentAt, receivedAt)
        }
    }

    private fun reactionFor(response: ServerResponse): Reaction = when (response.kind) {
        ResponseKind.CONFIGURATION -> Reaction.BLOCK_CONFIGURATION
        ResponseKind.PERMANENT -> Reaction.REJECT_EVENT
        ResponseKind.DELIVERED,
        ResponseKind.REPLAYED,
        ResponseKind.CLOCK_REJECTED,
        ResponseKind.RETRY,
        -> Reaction.RETRY_LATER
    }

    private fun problemFor(response: ServerResponse): UploadProblem {
        val kind = when (response.kind) {
            ResponseKind.CONFIGURATION -> UploadProblemKind.AUTH_REJECTED
            ResponseKind.CLOCK_REJECTED -> UploadProblemKind.CLOCK_REJECTED
            ResponseKind.PERMANENT -> UploadProblemKind.EVENT_REJECTED
            ResponseKind.REPLAYED -> UploadProblemKind.REPLAYED
            ResponseKind.RETRY, ResponseKind.DELIVERED -> retryProblemKind(response.httpStatus)
        }
        return UploadProblem(kind, response.httpStatus, response.errorCode)
    }

    private enum class Reaction {
        RETRY_LATER,
        BLOCK_CONFIGURATION,
        REJECT_EVENT,
    }

    private sealed interface Outcome {
        data class Delivered(val response: ServerResponse) : Outcome
        data class Failed(val reaction: Reaction, val problem: UploadProblem) : Outcome
    }

    private sealed interface Exchange {
        data class Answered(val response: ServerResponse, val sentAt: Long, val receivedAt: Long) : Exchange
        data class LocalFailure(val reaction: Reaction, val problem: UploadProblem) : Exchange
    }

    companion object {
        const val BATCH_SIZE = 25
        private const val MAX_BATCHES = 20
        private const val MAX_ATTEMPTS_PER_REQUEST = 2

        /** What to tell the shop owner about a response that is retried with backoff. */
        fun retryProblemKind(httpStatus: Int): UploadProblemKind = when (httpStatus) {
            408, 425, 429 -> UploadProblemKind.SERVER_BUSY
            in 500..599 -> UploadProblemKind.SERVER_ERROR
            // A 2xx without accepted:true, a redirect, or a missing route: the URL is not the Worker.
            in 200..399, 404, 405 -> UploadProblemKind.WRONG_SERVER
            else -> UploadProblemKind.UNEXPECTED_STATUS
        }
    }
}
