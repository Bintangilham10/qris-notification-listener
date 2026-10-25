package id.ciganstore.qrislistener.notification

import android.app.Notification

/** Decides which posted notifications reach the parser. */
object PaymentNotificationFilter {
    /** Checked first so notifications from every other app are dropped before anything else is read. */
    fun isFromGopayMerchant(packageName: String?): Boolean =
        packageName == PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE

    /**
     * Group summaries are posted next to the child notification and can repeat its title and text.
     * They have their own key (and so their own event ID), so one payment would become two events.
     */
    fun isGroupSummary(notificationFlags: Int): Boolean =
        (notificationFlags and Notification.FLAG_GROUP_SUMMARY) != 0

    fun shouldProcess(packageName: String?, notificationFlags: Int): Boolean =
        isFromGopayMerchant(packageName) && !isGroupSummary(notificationFlags)
}
