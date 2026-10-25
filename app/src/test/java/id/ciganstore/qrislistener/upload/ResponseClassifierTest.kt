package id.ciganstore.qrislistener.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseClassifierTest {
    private fun workerError(code: String) = "{\"error\":{\"code\":\"$code\",\"message\":\"Pesan dari Worker.\"}}"

    private val cloudflareErrorPage =
        "<!DOCTYPE html><html><head><title>Worker threw exception | ciganstore.ai.id | Cloudflare</title>" +
            "</head><body>Error 1101</body></html>"

    @Test
    fun `202 stores a new event with its match status`() {
        val response = ResponseClassifier.classify(202, "{\"accepted\":true,\"duplicate\":false,\"matchStatus\":\"matched\"}")

        assertEquals(ResponseKind.DELIVERED, response.kind)
        assertFalse(response.duplicate)
        assertEquals("matched", response.matchStatus)
    }

    @Test
    fun `200 duplicate is also a successful delivery`() {
        val response = ResponseClassifier.classify(200, "{\"accepted\":true,\"duplicate\":true,\"matchStatus\":\"unmatched\"}")

        assertEquals(ResponseKind.DELIVERED, response.kind)
        assertTrue(response.duplicate)
        assertEquals("unmatched", response.matchStatus)
        assertEquals(ResponseKind.DELIVERED, ResponseClassifier.classify(200, "{\"accepted\":true,\"duplicate\":true}").kind)
    }

    @Test
    fun `202 heartbeat carries the server time`() {
        val response = ResponseClassifier.classify(202, "{\"accepted\":true,\"serverTime\":\"2026-10-25T04:00:00.123Z\"}")

        assertEquals(ResponseKind.DELIVERED, response.kind)
        assertEquals("2026-10-25T04:00:00.123Z", response.serverTime)
    }

    @Test
    fun `wrong secret or device id stops delivery until the settings change`() {
        val response = ResponseClassifier.classify(401, workerError("invalid_listener_auth"))

        assertEquals(ResponseKind.CONFIGURATION, response.kind)
        assertEquals("invalid_listener_auth", response.errorCode)
    }

    @Test
    fun `stale timestamp is a clock problem that is retried later`() {
        val response = ResponseClassifier.classify(401, workerError("stale_listener_request"))

        assertEquals(ResponseKind.CLOCK_REJECTED, response.kind)
        assertEquals("stale_listener_request", response.errorCode)
    }

    @Test
    fun `replayed request is signed again`() {
        assertEquals(ResponseKind.REPLAYED, ResponseClassifier.classify(409, workerError("replayed_request")).kind)
    }

    @Test
    fun `content rejections from the Worker are permanent`() {
        listOf(
            413 to "payload_too_large",
            415 to "unsupported_media_type",
            422 to "invalid_event",
            422 to "untrusted_notification",
            422 to "invalid_event_time",
            422 to "invalid_heartbeat",
            // Field validators inside the Worker's event parser answer with these codes too.
            422 to "invalid_input",
            422 to "invalid_date",
        ).forEach { (status, code) ->
            val response = ResponseClassifier.classify(status, workerError(code))
            assertEquals("$status $code", ResponseKind.PERMANENT, response.kind)
            assertEquals(code, response.errorCode)
        }
    }

    @Test
    fun `rate limits and server errors are retried with backoff`() {
        listOf(
            429 to workerError("rate_limited"),
            500 to workerError("internal_error"),
            502 to cloudflareErrorPage,
            503 to "",
            504 to null,
            520 to cloudflareErrorPage,
            522 to cloudflareErrorPage,
            408 to null,
        ).forEach { (status, body) ->
            assertEquals("HTTP $status", ResponseKind.RETRY, ResponseClassifier.classify(status, body).kind)
        }
    }

    @Test
    fun `non JSON bodies are retried even with a final looking status`() {
        listOf(200, 202, 401, 403, 409, 413, 415, 422).forEach { status ->
            val response = ResponseClassifier.classify(status, cloudflareErrorPage)
            assertEquals("HTTP $status", ResponseKind.RETRY, response.kind)
        }
    }

    @Test
    fun `success status without accepted true is never a delivery`() {
        listOf(
            200 to "{\"accepted\":false}",
            200 to "{\"ok\":true}",
            200 to "{\"accepted\":\"true\"}",
            202 to "",
            202 to null,
            200 to "[{\"accepted\":true}]",
            201 to "{\"accepted\":true}",
            204 to null,
        ).forEach { (status, body) ->
            val response = ResponseClassifier.classify(status, body)
            assertEquals("HTTP $status $body", ResponseKind.RETRY, response.kind)
        }
        assertEquals(
            ResponseClassifier.CODE_UNEXPECTED_RESPONSE,
            ResponseClassifier.classify(200, "{\"ok\":true}").errorCode,
        )
    }

    @Test
    fun `redirects and missing routes are reported and retried`() {
        listOf(301, 302, 307, 308).forEach { status ->
            assertEquals("HTTP $status", ResponseKind.RETRY, ResponseClassifier.classify(status, null).kind)
        }
        assertEquals(ResponseKind.RETRY, ResponseClassifier.classify(404, workerError("not_found")).kind)
        assertEquals(ResponseKind.RETRY, ResponseClassifier.classify(405, workerError("method_not_allowed")).kind)
    }

    @Test
    fun `other 401 and 409 codes are not mistaken for known ones`() {
        assertEquals(ResponseKind.RETRY, ResponseClassifier.classify(401, workerError("something_else")).kind)
        assertEquals(ResponseKind.RETRY, ResponseClassifier.classify(401, null).kind)
        assertEquals(ResponseKind.RETRY, ResponseClassifier.classify(409, workerError("conflict")).kind)
    }

    @Test
    fun `unsafe error codes are dropped`() {
        val response = ResponseClassifier.classify(422, "{\"error\":{\"code\":\"<b>x</b>\",\"message\":\"x\"}}")

        assertNull(response.errorCode)
        assertEquals(ResponseKind.RETRY, response.kind)
    }

    @Test
    fun `retried statuses are explained to the shop owner`() {
        assertEquals(UploadProblemKind.SERVER_BUSY, ListenerUploader.retryProblemKind(429))
        assertEquals(UploadProblemKind.SERVER_BUSY, ListenerUploader.retryProblemKind(408))
        assertEquals(UploadProblemKind.SERVER_ERROR, ListenerUploader.retryProblemKind(500))
        assertEquals(UploadProblemKind.SERVER_ERROR, ListenerUploader.retryProblemKind(522))
        assertEquals(UploadProblemKind.WRONG_SERVER, ListenerUploader.retryProblemKind(200))
        assertEquals(UploadProblemKind.WRONG_SERVER, ListenerUploader.retryProblemKind(302))
        assertEquals(UploadProblemKind.WRONG_SERVER, ListenerUploader.retryProblemKind(404))
        assertEquals(UploadProblemKind.UNEXPECTED_STATUS, ListenerUploader.retryProblemKind(403))
        assertEquals(UploadProblemKind.UNEXPECTED_STATUS, ListenerUploader.retryProblemKind(401))
    }
}
