package id.ciganstore.qrislistener.notification

data class ParsedPayment(
    val amount: Long,
    val merchant: String,
)

object PaymentNotificationParser {
    const val GOPAY_MERCHANT_PACKAGE = "com.gojek.gopaymerchant"
    const val STATIC_QRIS_TITLE = "Pembayaran QRIS statis diterima"

    private val bodyPattern = Regex(
        pattern = "^Rp\\s+([0-9]{1,3}(?:\\.[0-9]{3})*|[0-9]+)\\s+di\\s+(.+?)\\.?$",
    )

    fun parse(payload: NotificationPayload): ParsedPayment? {
        if (payload.packageName != GOPAY_MERCHANT_PACKAGE) return null
        if (!payload.title.trim().equals(STATIC_QRIS_TITLE, ignoreCase = true)) return null

        val match = bodyPattern.matchEntire(payload.body.trim()) ?: return null
        val amount = match.groupValues[1].replace(".", "").toLongOrNull() ?: return null
        val merchant = match.groupValues[2].trim().removeSuffix(".").trim()

        if (amount <= 0L || merchant.isEmpty()) return null
        return ParsedPayment(amount = amount, merchant = merchant)
    }
}
