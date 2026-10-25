package id.ciganstore.qrislistener.data

/**
 * A payment parsed from a GoPay Merchant notification.
 *
 * [postedAt] is the notification `postTime` on the phone clock. It is sent to the server unchanged
 * as `occurredAt`; the server corrects phone clock skew. [capturedAt] is the phone time at which the
 * listener stored the event. It can be later than [postedAt] when active notifications are replayed
 * after the listener reconnects.
 */
data class PaymentEvent(
    val eventId: String,
    val type: String,
    val amount: Long,
    val merchant: String,
    val postedAt: Long,
    val capturedAt: Long,
    val sourcePackage: String,
) {
    companion object {
        const val TYPE_QRIS_STATIC_PAYMENT = "QRIS_STATIC_PAYMENT"
    }
}
