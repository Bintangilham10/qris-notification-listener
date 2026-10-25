package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.R
import id.ciganstore.qrislistener.data.UploadState
import id.ciganstore.qrislistener.data.UploadStatus
import id.ciganstore.qrislistener.upload.UploadStatusText.Health
import id.ciganstore.qrislistener.upload.UploadStatusText.ProblemArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStatusTextTest {
    private val authProblem = UploadProblem(UploadProblemKind.AUTH_REJECTED, 401, "invalid_listener_auth")

    private fun state(
        status: UploadStatus,
        attempts: Int = 0,
        lastError: String? = null,
        matchStatus: String? = null,
    ) = UploadState(status, attempts, lastError, null, null, matchStatus)

    @Test
    fun `each problem the owner can meet has plain Indonesian copy`() {
        assertEquals(R.string.upload_problem_auth, UploadStatusText.problemMessage(UploadProblemKind.AUTH_REJECTED))
        assertEquals(R.string.upload_problem_clock, UploadStatusText.problemMessage(UploadProblemKind.CLOCK_REJECTED))
        assertEquals(R.string.upload_problem_clock, UploadStatusText.problemMessage(UploadProblemKind.CLOCK_INVALID))
        assertEquals(R.string.upload_problem_server_busy, UploadStatusText.problemMessage(UploadProblemKind.SERVER_BUSY))
        assertEquals(R.string.upload_problem_server_busy, UploadStatusText.problemMessage(UploadProblemKind.SERVER_ERROR))
        assertEquals(R.string.upload_problem_network, UploadStatusText.problemMessage(UploadProblemKind.NETWORK))
        assertEquals(R.string.upload_problem_rejected, UploadStatusText.problemMessage(UploadProblemKind.EVENT_REJECTED))
        assertEquals(R.string.upload_problem_wrong_server, UploadStatusText.problemMessage(UploadProblemKind.WRONG_SERVER))
        assertEquals(
            R.string.upload_problem_secret_unavailable,
            UploadStatusText.problemMessage(UploadProblemKind.SECRET_UNAVAILABLE),
        )
    }

    @Test
    fun `messages with a placeholder get the right argument`() {
        assertEquals(ProblemArgument.REJECTION_REASON, UploadStatusText.problemArgument(UploadProblemKind.EVENT_REJECTED))
        assertEquals(ProblemArgument.TECHNICAL_DETAIL, UploadStatusText.problemArgument(UploadProblemKind.SERVER_BUSY))
        assertEquals(ProblemArgument.TECHNICAL_DETAIL, UploadStatusText.problemArgument(UploadProblemKind.WRONG_SERVER))
        assertEquals(ProblemArgument.NONE, UploadStatusText.problemArgument(UploadProblemKind.AUTH_REJECTED))
        assertEquals(ProblemArgument.NONE, UploadStatusText.problemArgument(UploadProblemKind.NETWORK))
    }

    @Test
    fun `rejection reasons are translated from the stored error`() {
        assertEquals(R.string.reject_reason_untrusted, UploadStatusText.rejectionReason("HTTP 422 untrusted_notification"))
        assertEquals(R.string.reject_reason_untrusted, UploadStatusText.rejectionReason("untrusted_notification"))
        assertEquals(R.string.reject_reason_time, UploadStatusText.rejectionReason("HTTP 422 invalid_event_time"))
        assertEquals(R.string.reject_reason_invalid, UploadStatusText.rejectionReason("HTTP 422 invalid_event"))
        assertEquals(R.string.reject_reason_invalid, UploadStatusText.rejectionReason("HTTP 422 invalid_input"))
        assertEquals(R.string.reject_reason_too_large, UploadStatusText.rejectionReason("HTTP 413 payload_too_large"))
        assertEquals(R.string.reject_reason_format, UploadStatusText.rejectionReason("HTTP 415 unsupported_media_type"))
        assertEquals(R.string.reject_reason_unsupported, UploadStatusText.rejectionReason("unsupported_event"))
        assertEquals(R.string.reject_reason_invalid, UploadStatusText.rejectionReason(null))
    }

    @Test
    fun `health shows the state that matters most`() {
        assertEquals(Health.NOT_CONFIGURED, UploadStatusText.health(false, authProblem, null))
        assertEquals(Health.STOPPED, UploadStatusText.health(true, authProblem, UploadProblem(UploadProblemKind.NETWORK)))
        assertEquals(
            Health.SECRET_UNAVAILABLE,
            UploadStatusText.health(true, null, UploadProblem(UploadProblemKind.SECRET_UNAVAILABLE)),
        )
        assertEquals(Health.RETRYING, UploadStatusText.health(true, null, UploadProblem(UploadProblemKind.NETWORK)))
        assertEquals(
            Health.RETRYING,
            UploadStatusText.health(true, null, UploadProblem(UploadProblemKind.CLOCK_REJECTED, 401)),
        )
        // A rejected event does not stop delivery of the others.
        assertEquals(Health.ACTIVE, UploadStatusText.health(true, null, UploadProblem(UploadProblemKind.EVENT_REJECTED)))
        assertEquals(Health.ACTIVE, UploadStatusText.health(true, null, null))
        assertEquals(R.string.upload_status_stopped, UploadStatusText.healthLabel(Health.STOPPED))
        assertEquals(R.string.upload_status_active, UploadStatusText.healthLabel(Health.ACTIVE))
    }

    @Test
    fun `history rows show where each payment is`() {
        assertEquals(R.string.history_status_pending, UploadStatusText.historyStatus(state(UploadStatus.PENDING)))
        assertEquals(
            R.plurals.history_status_retrying,
            UploadStatusText.historyStatus(state(UploadStatus.PENDING, attempts = 2, lastError = "HTTP 503")),
        )
        assertEquals(R.string.history_status_blocked, UploadStatusText.historyStatus(state(UploadStatus.BLOCKED_CONFIG)))
        assertEquals(R.string.history_status_failed, UploadStatusText.historyStatus(state(UploadStatus.FAILED_PERMANENT)))
        assertEquals(
            R.string.history_status_matched,
            UploadStatusText.historyStatus(state(UploadStatus.UPLOADED, matchStatus = "matched")),
        )
        assertEquals(
            R.string.history_status_review,
            UploadStatusText.historyStatus(state(UploadStatus.UPLOADED, matchStatus = "review")),
        )
        assertEquals(
            R.string.history_status_unmatched,
            UploadStatusText.historyStatus(state(UploadStatus.UPLOADED, matchStatus = "unmatched")),
        )
        assertEquals(
            R.string.history_status_duplicate,
            UploadStatusText.historyStatus(state(UploadStatus.UPLOADED, matchStatus = "duplicate")),
        )
        assertEquals(R.string.history_status_uploaded, UploadStatusText.historyStatus(state(UploadStatus.UPLOADED)))
    }

    @Test
    fun `clock warning starts above one minute either way`() {
        assertFalse(UploadStatusText.shouldWarnClock(null))
        assertFalse(UploadStatusText.shouldWarnClock(60_000))
        assertFalse(UploadStatusText.shouldWarnClock(-60_000))
        assertTrue(UploadStatusText.shouldWarnClock(60_001))
        assertTrue(UploadStatusText.shouldWarnClock(-240_000))
    }
}
