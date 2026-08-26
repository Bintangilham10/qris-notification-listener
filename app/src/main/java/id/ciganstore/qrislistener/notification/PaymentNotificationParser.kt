package id.ciganstore.qrislistener.notification

data class ParsedPayment(
    val amount: Long,
    val merchant: String,
)

object PaymentNotificationParser {
    const val GOPAY_MERCHANT_PACKAGE = "com.gojek.gopaymerchant"
    const val STATIC_QRIS_TITLE = "Pembayaran QRIS statis diterima"

    private val whitespacePattern = Regex("[\\s\\u00A0\\u202F]+")
    private val bodyPattern = Regex(
        pattern = "^Rp\\s*([0-9]{1,3}(?:\\.[0-9]{3})*|[0-9]+)\\s+di\\s+(.+?)\\.?$",
    )

    fun parse(payload: NotificationPayload): ParsedPayment? {
        if (payload.packageName != GOPAY_MERCHANT_PACKAGE) return null
        if (!normalizeWhitespace(payload.title).equals(STATIC_QRIS_TITLE, ignoreCase = true)) return null

        val match = bodyPattern.matchEntire(normalizeWhitespace(payload.body)) ?: return null
        val amount = match.groupValues[1].replace(".", "").toLongOrNull() ?: return null
        val merchant = match.groupValues[2].trim().removeSuffix(".").trim()

        if (amount <= 0L || merchant.isEmpty()) return null
        return ParsedPayment(amount = amount, merchant = merchant)
    }

    fun isPaymentLikeTitle(title: String): Boolean {
        val normalizedTitle = normalizeWhitespace(title)
        return normalizedTitle.contains("pembayaran", ignoreCase = true) &&
            (
                normalizedTitle.contains("QRIS", ignoreCase = true) ||
                    normalizedTitle.contains("diterima", ignoreCase = true)
                )
    }

    private fun normalizeWhitespace(value: String): String = whitespacePattern
        .replace(value, " ")
        .trim()
}
