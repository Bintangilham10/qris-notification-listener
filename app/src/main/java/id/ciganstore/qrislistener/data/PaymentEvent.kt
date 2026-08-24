package id.ciganstore.qrislistener.data

data class PaymentEvent(
    val eventId: String,
    val type: String,
    val amount: Long,
    val merchant: String,
    val receivedAt: Long,
    val sourcePackage: String,
) {
    companion object {
        const val TYPE_QRIS_STATIC_PAYMENT = "QRIS_STATIC_PAYMENT"
    }
}

