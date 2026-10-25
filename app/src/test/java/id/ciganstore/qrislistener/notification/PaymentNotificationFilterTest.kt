package id.ciganstore.qrislistener.notification

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationFilterTest {
    private val gopay = PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE

    @Test
    fun `group summaries never create payment events`() {
        assertTrue(PaymentNotificationFilter.isGroupSummary(Notification.FLAG_GROUP_SUMMARY))
        assertTrue(
            PaymentNotificationFilter.isGroupSummary(Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_AUTO_CANCEL),
        )
        assertFalse(PaymentNotificationFilter.shouldProcess(gopay, Notification.FLAG_GROUP_SUMMARY))
    }

    @Test
    fun `individual GoPay Merchant notifications are processed`() {
        assertFalse(PaymentNotificationFilter.isGroupSummary(0))
        assertFalse(PaymentNotificationFilter.isGroupSummary(Notification.FLAG_AUTO_CANCEL))
        assertTrue(PaymentNotificationFilter.shouldProcess(gopay, 0))
        assertTrue(PaymentNotificationFilter.shouldProcess(gopay, Notification.FLAG_AUTO_CANCEL))
    }

    @Test
    fun `other apps are dropped before anything else is read`() {
        assertFalse(PaymentNotificationFilter.isFromGopayMerchant("com.example.fake"))
        assertFalse(PaymentNotificationFilter.isFromGopayMerchant(null))
        assertFalse(PaymentNotificationFilter.shouldProcess("com.gojek.app", 0))
        assertTrue(PaymentNotificationFilter.isFromGopayMerchant(gopay))
    }
}
