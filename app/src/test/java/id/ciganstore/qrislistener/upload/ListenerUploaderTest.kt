package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.data.UploadCounts
import id.ciganstore.qrislistener.data.UploadQueue
import id.ciganstore.qrislistener.data.UploadState
import id.ciganstore.qrislistener.data.UploadStatus
import id.ciganstore.qrislistener.notification.PaymentNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

class ListenerUploaderTest {
    private var now = ListenerTestVectors.TIMESTAMP_MILLIS
    private val queue = FakeQueue()
    private val status = FakeStatusStore()
    private var nonceCounter = 0
    private val transport = ScriptedTransport(
        ListenerRequestFactory(
            baseUrl = "https://ciganstore.ai.id",
            deviceId = ListenerTestVectors.DEVICE_ID,
            signer = QrisSigner(ListenerTestVectors.secretKey()),
            appVersion = "0.2.0",
            nowMillis = { now },
            newNonce = { "test-nonce-${nonceCounter++}-abcdefghij" },
        ),
    )
    private val uploader = ListenerUploader(queue, status, transport) { now }

    private val accepted = HttpResult(202, "{\"accepted\":true,\"duplicate\":false,\"matchStatus\":\"matched\"}")
    private val duplicate = HttpResult(200, "{\"accepted\":true,\"duplicate\":true,\"matchStatus\":\"unmatched\"}")

    private fun workerError(status: Int, code: String) =
        HttpResult(status, "{\"error\":{\"code\":\"$code\",\"message\":\"Pesan dari Worker.\"}}")

    private fun event(idChar: Char, postedAt: Long, sourcePackage: String = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE) =
        PaymentEvent(
            eventId = idChar.toString().repeat(64),
            type = PaymentEvent.TYPE_QRIS_STATIC_PAYMENT,
            amount = 10_101,
            merchant = ListenerTestVectors.MERCHANT,
            postedAt = postedAt,
            capturedAt = postedAt + 1_000,
            sourcePackage = sourcePackage,
        )

    private fun sentEventIds(): List<String> = transport.requests.map { request ->
        MiniJson.parseObject(String(request.body, StandardCharsets.UTF_8))!!["eventId"] as String
    }

    @Test
    fun `delivers queued events oldest notification first`() {
        val older = event('a', now - 120_000)
        val newer = event('b', now - 60_000)
        queue.add(newer)
        queue.add(older)
        transport.respond(accepted, duplicate)

        val result = uploader.uploadPending()

        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 2, rejected = 0), result)
        assertEquals(listOf(older.eventId, newer.eventId), sentEventIds())
        assertEquals(UploadStatus.UPLOADED, queue.state(older).status)
        assertEquals("matched", queue.state(older).serverMatchStatus)
        // A 200 duplicate is a success too: the server already stored this event.
        assertEquals(UploadStatus.UPLOADED, queue.state(newer).status)
        assertEquals("unmatched", queue.state(newer).serverMatchStatus)
        assertEquals(now, status.lastDeliveredAt)
        transport.requests.forEach { request ->
            assertEquals("https://ciganstore.ai.id/api/v1/listener/events", request.url)
        }
    }

    @Test
    fun `the body sent is the stored event in the Worker format`() {
        val stored = PaymentEvent(
            eventId = ListenerTestVectors.EVENT_ID,
            type = PaymentEvent.TYPE_QRIS_STATIC_PAYMENT,
            amount = ListenerTestVectors.EVENT_AMOUNT,
            merchant = ListenerTestVectors.MERCHANT,
            postedAt = ListenerTestVectors.EVENT_OCCURRED_AT_MILLIS,
            capturedAt = ListenerTestVectors.EVENT_OCCURRED_AT_MILLIS + 5_000,
            sourcePackage = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE,
        )
        queue.add(stored)
        transport.respond(accepted)

        uploader.uploadPending()

        assertEquals(ListenerTestVectors.EVENT_BODY, String(transport.requests.single().body, StandardCharsets.UTF_8))
    }

    @Test
    fun `wrong secret or device id stops delivery and parks the queue`() {
        val first = event('a', now - 120_000)
        val second = event('b', now - 60_000)
        queue.add(first)
        queue.add(second)
        transport.respond(workerError(401, "invalid_listener_auth"))

        val result = uploader.uploadPending()

        assertTrue(result is ListenerUploader.UploadResult.Blocked)
        assertEquals(1, transport.requests.size)
        assertEquals(UploadProblemKind.AUTH_REJECTED, status.configurationBlock()?.kind)
        assertEquals(UploadStatus.BLOCKED_CONFIG, queue.state(first).status)
        assertEquals(1, queue.state(first).attempts)
        assertEquals(UploadStatus.BLOCKED_CONFIG, queue.state(second).status)
        assertEquals(0, queue.state(second).attempts)

        // Later runs, including one for a newly captured payment, never reach the network.
        val third = event('c', now)
        queue.add(third)
        assertTrue(uploader.uploadPending() is ListenerUploader.UploadResult.Blocked)
        assertEquals(1, transport.requests.size)
        assertEquals(UploadStatus.BLOCKED_CONFIG, queue.state(third).status)
        assertTrue(uploader.isConfigurationBlocked())
    }

    @Test
    fun `phone clock rejected by the server is retried later in order`() {
        val first = event('a', now - 120_000)
        val second = event('b', now - 60_000)
        queue.add(first)
        queue.add(second)
        transport.respond(workerError(401, "stale_listener_request"))

        val result = uploader.uploadPending()

        assertEquals(UploadProblemKind.CLOCK_REJECTED, (result as ListenerUploader.UploadResult.RetryLater).problem.kind)
        assertEquals(1, transport.requests.size)
        assertEquals(UploadStatus.PENDING, queue.state(first).status)
        assertEquals(1, queue.state(first).attempts)
        assertEquals("HTTP 401 stale_listener_request", queue.state(first).lastError)
        assertEquals(0, queue.state(second).attempts)
        assertEquals(UploadProblemKind.CLOCK_REJECTED, status.lastProblem?.kind)
        assertNull(status.configurationBlock())
    }

    @Test
    fun `replayed request is signed again with a new nonce`() {
        queue.add(event('a', now - 60_000))
        transport.respond(workerError(409, "replayed_request"), accepted)

        val result = uploader.uploadPending()

        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 1, rejected = 0), result)
        val (first, second) = transport.requests
        assertTrue(first.body.contentEquals(second.body))
        assertNotEquals(first.headers["X-QRIS-Nonce"], second.headers["X-QRIS-Nonce"])
        assertNotEquals(first.headers["X-QRIS-Signature"], second.headers["X-QRIS-Signature"])
    }

    @Test
    fun `a second replay waits for the backoff`() {
        val queued = event('a', now - 60_000)
        queue.add(queued)
        transport.respond(workerError(409, "replayed_request"), workerError(409, "replayed_request"))

        val result = uploader.uploadPending()

        assertEquals(UploadProblemKind.REPLAYED, (result as ListenerUploader.UploadResult.RetryLater).problem.kind)
        assertEquals(2, transport.requests.size)
        assertEquals(UploadStatus.PENDING, queue.state(queued).status)
    }

    @Test
    fun `rejected event is kept visible, never resent, and the queue moves on`() {
        val rejected = event('a', now - 120_000)
        val next = event('b', now - 60_000)
        queue.add(rejected)
        queue.add(next)
        transport.respond(workerError(422, "untrusted_notification"), accepted)

        val result = uploader.uploadPending()

        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 1, rejected = 1), result)
        assertEquals(UploadStatus.FAILED_PERMANENT, queue.state(rejected).status)
        assertEquals("HTTP 422 untrusted_notification", queue.state(rejected).lastError)
        assertEquals(UploadStatus.UPLOADED, queue.state(next).status)

        // Nothing is left to send; the rejected event stays in the queue for manual reconciliation.
        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 0, rejected = 0), uploader.uploadPending())
        assertEquals(2, transport.requests.size)
        assertEquals(1, queue.uploadCounts().failedPermanent)
    }

    @Test
    fun `every permanent rejection code marks only that event failed`() {
        listOf(
            413 to "payload_too_large",
            415 to "unsupported_media_type",
            422 to "invalid_event",
            422 to "invalid_event_time",
        ).forEachIndexed { index, (httpStatus, code) ->
            val queued = event('a' + index, now - 60_000L + index)
            queue.add(queued)
            transport.respond(workerError(httpStatus, code))

            uploader.uploadPending()

            assertEquals(code, UploadStatus.FAILED_PERMANENT, queue.state(queued).status)
            assertEquals(code, UploadProblemKind.EVENT_REJECTED, status.lastProblem?.kind)
            assertEquals(code, code, status.lastProblem?.detail)
        }
    }

    @Test
    fun `server busy, server errors and network failures are retried with backoff`() {
        listOf(
            Scripted.Answer(workerError(429, "rate_limited")) to UploadProblemKind.SERVER_BUSY,
            Scripted.Answer(workerError(500, "internal_error")) to UploadProblemKind.SERVER_ERROR,
            Scripted.Answer(HttpResult(502, "<html>Bad gateway</html>")) to UploadProblemKind.SERVER_ERROR,
            Scripted.Fail(UnknownHostException("ciganstore.ai.id")) to UploadProblemKind.NETWORK,
            Scripted.Fail(SocketTimeoutException("timeout")) to UploadProblemKind.NETWORK,
            Scripted.Answer(HttpResult(200, "<html>Login Wi-Fi</html>")) to UploadProblemKind.WRONG_SERVER,
        ).forEachIndexed { index, (answer, expectedKind) ->
            val queued = event('a', now - 60_000)
            queue.clear()
            queue.add(queued)
            transport.script(answer)

            val result = uploader.uploadPending()

            val retry = result as ListenerUploader.UploadResult.RetryLater
            assertEquals("case $index", expectedKind, retry.problem.kind)
            assertEquals("case $index", UploadStatus.PENDING, queue.state(queued).status)
            assertEquals("case $index", 1, queue.state(queued).attempts)
            assertEquals("case $index", expectedKind, status.lastProblem?.kind)
            assertNull("case $index", status.configurationBlock())
        }
    }

    @Test
    fun `a phone clock that cannot sign is reported and retried`() {
        now = 946_684_800_000L // 2000-01-01: only nine digits of Unix seconds.
        val queued = event('a', now)
        queue.add(queued)

        val result = uploader.uploadPending()

        assertEquals(UploadProblemKind.CLOCK_INVALID, (result as ListenerUploader.UploadResult.RetryLater).problem.kind)
        assertTrue(transport.requests.isEmpty())
        assertEquals(UploadStatus.PENDING, queue.state(queued).status)
    }

    @Test
    fun `events that are not GoPay QRIS payments are rejected without a request`() {
        val foreign = event('a', now - 60_000, sourcePackage = "com.example.fake")
        queue.add(foreign)

        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 0, rejected = 1), uploader.uploadPending())
        assertTrue(transport.requests.isEmpty())
        assertEquals(UploadStatus.FAILED_PERMANENT, queue.state(foreign).status)
        assertEquals("unsupported_event", queue.state(foreign).lastError)
    }

    @Test
    fun `a stopped worker leaves the rest pending`() {
        val first = event('a', now - 120_000)
        val second = event('b', now - 60_000)
        queue.add(first)
        queue.add(second)
        transport.respond(accepted, accepted)

        val result = uploader.uploadPending(isStopped = { transport.requests.size >= 1 })

        assertEquals(ListenerUploader.UploadResult.Stopped(delivered = 1), result)
        assertEquals(UploadStatus.UPLOADED, queue.state(first).status)
        assertEquals(UploadStatus.PENDING, queue.state(second).status)
    }

    @Test
    fun `heartbeat is a signed empty object and measures the clock skew`() {
        transport.respond(HttpResult(202, "{\"accepted\":true,\"serverTime\":\"2026-10-25T04:01:30.000Z\"}"))

        val result = uploader.sendHeartbeat()

        assertEquals(ListenerUploader.HeartbeatResult.Success(clockSkewMillis = 90_000, releasedEvents = 0), result)
        val request = transport.requests.single()
        assertEquals("https://ciganstore.ai.id/api/v1/listener/heartbeat", request.url)
        assertEquals("{}", String(request.body, StandardCharsets.UTF_8))
        assertEquals(now, status.lastHeartbeatAt)
        assertEquals(90_000L, status.clockSkewMillis)
    }

    @Test
    fun `successful heartbeat lifts a configuration block and releases parked events`() {
        val parked = event('a', now - 60_000)
        queue.add(parked)
        status.blockConfiguration(UploadProblem(UploadProblemKind.AUTH_REJECTED, 401, "invalid_listener_auth"), now)
        uploader.uploadPending()
        assertEquals(UploadStatus.BLOCKED_CONFIG, queue.state(parked).status)
        transport.respond(HttpResult(202, "{\"accepted\":true,\"serverTime\":\"2026-10-25T04:00:00.000Z\"}"))

        val result = uploader.sendHeartbeat()

        assertEquals(ListenerUploader.HeartbeatResult.Success(clockSkewMillis = 0, releasedEvents = 1), result)
        assertNull(status.configurationBlock())
        assertEquals(UploadStatus.PENDING, queue.state(parked).status)
    }

    @Test
    fun `heartbeat with the wrong secret blocks delivery`() {
        val queued = event('a', now - 60_000)
        queue.add(queued)
        transport.respond(workerError(401, "invalid_listener_auth"))

        val result = uploader.sendHeartbeat()

        assertEquals(UploadProblemKind.AUTH_REJECTED, (result as ListenerUploader.HeartbeatResult.Failure).problem.kind)
        assertEquals(UploadProblemKind.AUTH_REJECTED, status.configurationBlock()?.kind)
        assertEquals(UploadStatus.BLOCKED_CONFIG, queue.state(queued).status)
    }

    @Test
    fun `heartbeat failures that pass by themselves do not block`() {
        transport.respond(workerError(401, "stale_listener_request"))

        val result = uploader.sendHeartbeat()

        assertEquals(UploadProblemKind.CLOCK_REJECTED, (result as ListenerUploader.HeartbeatResult.Failure).problem.kind)
        assertTrue(result.problem.kind.isTransient)
        assertNull(status.configurationBlock())
    }

    @Test
    fun `events parked by a stale block are released when no block stands`() {
        val parked = event('a', now - 60_000)
        queue.add(parked)
        queue.markBlockedByConfiguration(null, now, "HTTP 401 invalid_listener_auth")
        transport.respond(accepted)

        val result = uploader.uploadPending()

        assertEquals(ListenerUploader.UploadResult.Completed(delivered = 1, rejected = 0), result)
        assertEquals(UploadStatus.UPLOADED, queue.state(parked).status)
    }

    private sealed interface Scripted {
        data class Answer(val result: HttpResult) : Scripted
        data class Fail(val error: IOException) : Scripted
    }

    /** Signs every call like [HttpListenerTransport] does, records it, and plays scripted answers. */
    private class ScriptedTransport(private val requestFactory: ListenerRequestFactory) : ListenerTransport {
        val requests = mutableListOf<ListenerRequest>()
        private val script = ArrayDeque<Scripted>()

        fun respond(vararg results: HttpResult) = results.forEach { script.addLast(Scripted.Answer(it)) }

        fun script(step: Scripted) = script.addLast(step)

        override fun post(path: String, body: ByteArray): HttpResult {
            requests += requestFactory.create(path, body)
            return when (val step = script.removeFirst()) {
                is Scripted.Answer -> step.result
                is Scripted.Fail -> throw step.error
            }
        }
    }

    /** In-memory [UploadQueue] with the same semantics as the SQLite statements. */
    private class FakeQueue : UploadQueue {
        private val rows = linkedMapOf<String, Pair<PaymentEvent, UploadState>>()

        fun add(event: PaymentEvent) {
            rows[event.eventId] = event to UploadState(UploadStatus.PENDING, 0, null, null, null, null)
        }

        fun clear() = rows.clear()

        fun state(event: PaymentEvent): UploadState = rows.getValue(event.eventId).second

        private fun update(eventId: String, change: (UploadState) -> UploadState) {
            val (event, state) = rows[eventId] ?: return
            rows[eventId] = event to change(state)
        }

        override fun pendingUploads(limit: Int): List<PaymentEvent> = rows.values
            .filter { it.second.status == UploadStatus.PENDING }
            .map { it.first }
            .sortedWith(compareBy<PaymentEvent>({ it.postedAt }, { it.capturedAt }, { it.eventId }))
            .take(limit)

        override fun markUploaded(eventId: String, uploadedAt: Long, serverMatchStatus: String?) = update(eventId) {
            it.copy(
                status = UploadStatus.UPLOADED,
                attempts = it.attempts + 1,
                lastAttemptAt = uploadedAt,
                uploadedAt = uploadedAt,
                serverMatchStatus = serverMatchStatus,
                lastError = null,
            )
        }

        override fun markAttemptFailed(eventId: String, attemptedAt: Long, error: String) = update(eventId) {
            it.copy(attempts = it.attempts + 1, lastAttemptAt = attemptedAt, lastError = error)
        }

        override fun markFailedPermanent(eventId: String, attemptedAt: Long, error: String) = update(eventId) {
            it.copy(
                status = UploadStatus.FAILED_PERMANENT,
                attempts = it.attempts + 1,
                lastAttemptAt = attemptedAt,
                lastError = error,
            )
        }

        override fun markBlockedByConfiguration(eventId: String?, attemptedAt: Long, error: String) {
            if (eventId != null) update(eventId) { it.copy(attempts = it.attempts + 1, lastAttemptAt = attemptedAt) }
            rows.keys.toList().forEach { id ->
                update(id) { state ->
                    if (state.status == UploadStatus.PENDING) {
                        state.copy(status = UploadStatus.BLOCKED_CONFIG, lastError = error)
                    } else {
                        state
                    }
                }
            }
        }

        override fun releaseConfigurationBlock(): Int {
            var released = 0
            rows.keys.toList().forEach { id ->
                update(id) { state ->
                    if (state.status == UploadStatus.BLOCKED_CONFIG) {
                        released++
                        state.copy(status = UploadStatus.PENDING)
                    } else {
                        state
                    }
                }
            }
            return released
        }

        override fun uploadCounts(): UploadCounts {
            val statuses = rows.values.map { it.second.status }
            return UploadCounts(
                pending = statuses.count { it == UploadStatus.PENDING },
                uploaded = statuses.count { it == UploadStatus.UPLOADED },
                failedPermanent = statuses.count { it == UploadStatus.FAILED_PERMANENT },
                blockedConfig = statuses.count { it == UploadStatus.BLOCKED_CONFIG },
            )
        }
    }

    /** In-memory [UploadStatusStore] with the same semantics as [PrefsUploadStatusStore]. */
    private class FakeStatusStore : UploadStatusStore {
        private var block: UploadProblem? = null
        var lastProblem: UploadProblem? = null
        var lastDeliveredAt: Long? = null
        var lastHeartbeatAt: Long? = null
        var clockSkewMillis: Long? = null

        override fun configurationBlock(): UploadProblem? = block

        override fun recordDelivered(atMillis: Long) {
            lastDeliveredAt = atMillis
            lastProblem = null
        }

        override fun recordHeartbeat(atMillis: Long, clockSkewMillis: Long?) {
            lastHeartbeatAt = atMillis
            this.clockSkewMillis = clockSkewMillis
            lastProblem = null
        }

        override fun recordProblem(problem: UploadProblem, atMillis: Long) {
            lastProblem = problem
        }

        override fun blockConfiguration(problem: UploadProblem, atMillis: Long) {
            block = problem
            lastProblem = problem
        }

        override fun clearConfigurationBlock() {
            block = null
        }
    }
}
