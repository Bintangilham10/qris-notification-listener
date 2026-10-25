package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.R
import id.ciganstore.qrislistener.data.UploadState
import id.ciganstore.qrislistener.data.UploadStatus
import kotlin.math.abs

/**
 * Maps delivery state to the plain-Indonesian copy in strings.xml. It returns string resource IDs and
 * stays free of Android calls, so every mapping is unit tested; MainActivity only fills in arguments.
 */
object UploadStatusText {
    enum class Health {
        NOT_CONFIGURED,
        SECRET_UNAVAILABLE,

        /** The server rejected the secret or device ID; nothing is sent until the settings change. */
        STOPPED,

        /** A temporary problem (offline, server busy, clock); WorkManager retries with backoff. */
        RETRYING,
        ACTIVE,
    }

    fun health(configured: Boolean, configurationBlock: UploadProblem?, lastProblem: UploadProblem?): Health = when {
        !configured -> Health.NOT_CONFIGURED
        configurationBlock != null -> Health.STOPPED
        lastProblem?.kind == UploadProblemKind.SECRET_UNAVAILABLE -> Health.SECRET_UNAVAILABLE
        lastProblem != null && lastProblem.kind.isTransient -> Health.RETRYING
        else -> Health.ACTIVE
    }

    fun healthLabel(health: Health): Int = when (health) {
        Health.NOT_CONFIGURED -> R.string.upload_status_not_configured
        Health.SECRET_UNAVAILABLE -> R.string.upload_status_secret_unavailable
        Health.STOPPED -> R.string.upload_status_stopped
        Health.RETRYING -> R.string.upload_status_retrying
        Health.ACTIVE -> R.string.upload_status_active
    }

    /** The explanation shown for a delivery problem. See [problemArgument] for its `%1$s`. */
    fun problemMessage(kind: UploadProblemKind): Int = when (kind) {
        UploadProblemKind.NETWORK -> R.string.upload_problem_network
        UploadProblemKind.CLOCK_INVALID, UploadProblemKind.CLOCK_REJECTED -> R.string.upload_problem_clock
        UploadProblemKind.AUTH_REJECTED -> R.string.upload_problem_auth
        UploadProblemKind.WRONG_SERVER -> R.string.upload_problem_wrong_server
        UploadProblemKind.SERVER_BUSY, UploadProblemKind.SERVER_ERROR -> R.string.upload_problem_server_busy
        UploadProblemKind.UNEXPECTED_STATUS -> R.string.upload_problem_unexpected
        UploadProblemKind.REPLAYED -> R.string.upload_problem_replayed
        UploadProblemKind.EVENT_REJECTED -> R.string.upload_problem_rejected
        UploadProblemKind.SECRET_UNAVAILABLE -> R.string.upload_problem_secret_unavailable
    }

    enum class ProblemArgument {
        NONE,

        /** The technical summary, for example "HTTP 503 internal_error". */
        TECHNICAL_DETAIL,

        /** The translated rejection reason from [rejectionReason]. */
        REJECTION_REASON,
    }

    fun problemArgument(kind: UploadProblemKind): ProblemArgument = when (kind) {
        UploadProblemKind.WRONG_SERVER,
        UploadProblemKind.SERVER_BUSY,
        UploadProblemKind.SERVER_ERROR,
        UploadProblemKind.UNEXPECTED_STATUS,
        -> ProblemArgument.TECHNICAL_DETAIL

        UploadProblemKind.EVENT_REJECTED -> ProblemArgument.REJECTION_REASON

        UploadProblemKind.NETWORK,
        UploadProblemKind.CLOCK_INVALID,
        UploadProblemKind.CLOCK_REJECTED,
        UploadProblemKind.AUTH_REJECTED,
        UploadProblemKind.REPLAYED,
        UploadProblemKind.SECRET_UNAVAILABLE,
        -> ProblemArgument.NONE
    }

    /**
     * Why the server refused an event, from a stored error such as `HTTP 422 untrusted_notification`
     * (the per-event `last_error`) or the bare code in [UploadProblem.detail].
     */
    fun rejectionReason(error: String?): Int {
        val tokens = error?.split(' ')?.toSet().orEmpty()
        return when {
            "untrusted_notification" in tokens -> R.string.reject_reason_untrusted
            "invalid_event_time" in tokens -> R.string.reject_reason_time
            "payload_too_large" in tokens || "413" in tokens -> R.string.reject_reason_too_large
            "unsupported_media_type" in tokens || "415" in tokens -> R.string.reject_reason_format
            "unsupported_event" in tokens -> R.string.reject_reason_unsupported
            // invalid_event, plus the Worker's own invalid_input / invalid_date validation codes.
            else -> R.string.reject_reason_invalid
        }
    }

    /**
     * Delivery state of one history row: a string resource, except [R.plurals.history_status_retrying]
     * (pending with earlier attempts), a plurals resource that takes the attempt count.
     */
    fun historyStatus(state: UploadState): Int = when (state.status) {
        UploadStatus.UPLOADED -> when (state.serverMatchStatus) {
            "matched" -> R.string.history_status_matched
            "review" -> R.string.history_status_review
            "unmatched" -> R.string.history_status_unmatched
            "duplicate" -> R.string.history_status_duplicate
            else -> R.string.history_status_uploaded
        }
        UploadStatus.PENDING ->
            if (state.attempts > 0) R.plurals.history_status_retrying else R.string.history_status_pending
        UploadStatus.BLOCKED_CONFIG -> R.string.history_status_blocked
        UploadStatus.FAILED_PERMANENT -> R.string.history_status_failed
    }

    /** The server still accepts requests up to 300 s off, but the owner should fix the clock early. */
    fun shouldWarnClock(clockSkewMillis: Long?): Boolean =
        clockSkewMillis != null && abs(clockSkewMillis) > ListenerProtocol.CLOCK_WARNING_MILLIS
}
