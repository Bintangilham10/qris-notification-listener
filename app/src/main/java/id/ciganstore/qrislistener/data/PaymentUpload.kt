package id.ciganstore.qrislistener.data

/** Delivery state of one locally stored payment event. */
enum class UploadStatus(val storageValue: String) {
    /** Stored locally and waiting to be delivered (includes events whose last attempt failed temporarily). */
    PENDING("pending"),

    /** The server accepted the event (new or duplicate). */
    UPLOADED("uploaded"),

    /** The server rejected this event's content; it is kept locally but never resent automatically. */
    FAILED_PERMANENT("failed_permanent"),

    /** Parked because the server rejected the device configuration (secret, device ID or URL). */
    BLOCKED_CONFIG("blocked_config"),
    ;

    companion object {
        fun fromStorage(value: String?): UploadStatus =
            entries.firstOrNull { it.storageValue == value } ?: PENDING
    }
}

data class UploadState(
    val status: UploadStatus,
    val attempts: Int,
    val lastError: String?,
    val lastAttemptAt: Long?,
    val uploadedAt: Long?,
    val serverMatchStatus: String?,
)

data class PaymentRecord(
    val event: PaymentEvent,
    val upload: UploadState,
)

data class UploadCounts(
    val pending: Int,
    val uploaded: Int,
    val failedPermanent: Int,
    val blockedConfig: Int,
) {
    companion object {
        val EMPTY = UploadCounts(pending = 0, uploaded = 0, failedPermanent = 0, blockedConfig = 0)
    }
}

/** Local outbox of payment events waiting for delivery to the server. */
interface UploadQueue {
    /** Pending events, oldest notification first. */
    fun pendingUploads(limit: Int): List<PaymentEvent>

    fun markUploaded(eventId: String, uploadedAt: Long, serverMatchStatus: String?)

    /** Records a failed attempt that will be retried; the event stays pending. */
    fun markAttemptFailed(eventId: String, attemptedAt: Long, error: String)

    fun markFailedPermanent(eventId: String, attemptedAt: Long, error: String)

    /**
     * Records the attempt on [eventId] (when not null) and parks every pending event as blocked until
     * the configuration is fixed.
     */
    fun markBlockedByConfiguration(eventId: String?, attemptedAt: Long, error: String)

    /** Returns blocked events to the pending queue and reports how many were released. */
    fun releaseConfigurationBlock(): Int

    fun uploadCounts(): UploadCounts
}
