package id.ciganstore.qrislistener.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import id.ciganstore.qrislistener.data.SqlitePaymentRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GopayNotificationListenerService : NotificationListenerService() {
    private lateinit var repository: SqlitePaymentRepository
    private lateinit var processor: PaymentNotificationProcessor
    private lateinit var executor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        repository = SqlitePaymentRepository(applicationContext)
        processor = PaymentNotificationProcessor(repository)
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        setConnected(this, true)
        broadcastStateChanged()
    }

    override fun onListenerDisconnected() {
        setConnected(this, false)
        broadcastStateChanged()
        requestRebind(ComponentName(this, GopayNotificationListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.packageName != PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE) {
            return
        }

        val extras = statusBarNotification.notification.extras
        val payload = NotificationPayload(
            packageName = statusBarNotification.packageName,
            notificationKey = statusBarNotification.key.orEmpty(),
            postedAt = statusBarNotification.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            body = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                )?.toString().orEmpty(),
        )

        executor.execute { processPayload(payload) }
    }

    override fun onDestroy() {
        setConnected(this, false)
        broadcastStateChanged()
        if (::executor.isInitialized && ::repository.isInitialized) {
            executor.execute { repository.close() }
            executor.shutdown()
        }
        super.onDestroy()
    }

    private fun processPayload(payload: NotificationPayload) {
        try {
            when (processor.process(payload)) {
                is ProcessResult.Accepted -> {
                    setStorageError(this, false)
                    broadcastStateChanged()
                }

                ProcessResult.Duplicate -> Unit
                ProcessResult.Ignored -> {
                    if (payload.title.trim().equals(
                            PaymentNotificationParser.STATIC_QRIS_TITLE,
                            ignoreCase = true,
                        )
                    ) {
                        incrementIgnoredPayloadCount(this)
                        broadcastStateChanged()
                    }
                }
            }
        } catch (error: RuntimeException) {
            setStorageError(this, true)
            Log.e(TAG, "Failed to store a parsed payment event", error)
            broadcastStateChanged()
        }
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STATE_CHANGED = "id.ciganstore.qrislistener.PAYMENT_HISTORY_CHANGED"
        private const val TAG = "QrisListener"
        private const val PREFERENCES_NAME = "listener_diagnostics"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_IGNORED_PAYLOAD_COUNT = "ignored_payload_count"
        private const val KEY_STORAGE_ERROR = "storage_error"

        fun isConnected(context: Context): Boolean = preferences(context)
            .getBoolean(KEY_CONNECTED, false)

        fun ignoredPayloadCount(context: Context): Long = preferences(context)
            .getLong(KEY_IGNORED_PAYLOAD_COUNT, 0L)

        fun hasStorageError(context: Context): Boolean = preferences(context)
            .getBoolean(KEY_STORAGE_ERROR, false)

        private fun setConnected(context: Context, connected: Boolean) {
            preferences(context).edit().putBoolean(KEY_CONNECTED, connected).apply()
        }

        private fun incrementIgnoredPayloadCount(context: Context) {
            val prefs = preferences(context)
            prefs.edit().putLong(
                KEY_IGNORED_PAYLOAD_COUNT,
                prefs.getLong(KEY_IGNORED_PAYLOAD_COUNT, 0L) + 1L,
            ).apply()
        }

        private fun setStorageError(context: Context, hasError: Boolean) {
            preferences(context).edit().putBoolean(KEY_STORAGE_ERROR, hasError).apply()
        }

        private fun preferences(context: Context) = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
    }
}
