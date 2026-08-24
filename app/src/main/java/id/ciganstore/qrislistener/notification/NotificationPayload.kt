package id.ciganstore.qrislistener.notification

data class NotificationPayload(
    val packageName: String,
    val notificationKey: String,
    val postedAt: Long,
    val title: String,
    val body: String,
)

