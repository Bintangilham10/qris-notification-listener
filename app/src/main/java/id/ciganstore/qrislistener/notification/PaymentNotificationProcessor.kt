package id.ciganstore.qrislistener.notification

import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.data.PaymentRepository

sealed interface ProcessResult {
    data class Accepted(val event: PaymentEvent) : ProcessResult
    data object Duplicate : ProcessResult
    data object Ignored : ProcessResult
}

class PaymentNotificationProcessor(
    private val repository: PaymentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun process(payload: NotificationPayload): ProcessResult {
        val parsed = PaymentNotificationParser.parse(payload) ?: return ProcessResult.Ignored
        val event = PaymentEvent(
            eventId = PaymentEventId.from(payload),
            type = PaymentEvent.TYPE_QRIS_STATIC_PAYMENT,
            amount = parsed.amount,
            merchant = parsed.merchant,
            postedAt = payload.postedAt,
            capturedAt = clock(),
            sourcePackage = payload.packageName,
        )

        return if (repository.insertIfNew(event)) {
            ProcessResult.Accepted(event)
        } else {
            ProcessResult.Duplicate
        }
    }
}
