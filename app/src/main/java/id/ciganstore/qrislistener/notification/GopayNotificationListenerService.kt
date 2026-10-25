package id.ciganstore.qrislistener.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import id.ciganstore.qrislistener.data.SqlitePaymentRepository
import id.ciganstore.qrislistener.upload.UploadScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GopayNotificationListenerService : NotificationListenerService() {
    private lateinit var repository: SqlitePaymentRepository
    private lateinit var processor: PaymentNotificationProcessor
    private lateinit var executor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        connected = false
        repository = SqlitePaymentRepository(applicationContext)
        processor = PaymentNotificationProcessor(repository)
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        broadcastStateChanged()
        // Keeps the periodic upload + heartbeat alive and flushes events left pending while the
        // process was dead (for example after HyperOS cleanup or a reboot).
        runCatching { UploadScheduler.ensureScheduled(applicationContext) }
            .onFailure { error -> Log.w(TAG, "Unable to schedule payment uploads", error) }
        processActiveGopayNotifications()
    }

    override fun onListenerDisconnected() {
        connected = false
        broadcastStateChanged()
        requestRebind(ComponentName(this, GopayNotificationListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (!PaymentNotificationFilter.isFromGopayMerchant(statusBarNotification.packageName)) {
            return
        }
        if (PaymentNotificationFilter.isGroupSummary(statusBarNotification.notificationFlags())) {
            return
        }

        val payload = statusBarNotification.toPayload()
        executor.execute { processPayload(payload) }
    }

    override fun onDestroy() {
        connected = false
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
                    // The event is already committed to SQLite; the worker delivers it from there,
                    // so nothing is lost if the phone is offline or the upload fails.
                    requestUpload()
                }

                ProcessResult.Duplicate -> Unit
                ProcessResult.Ignored -> {
                    if (PaymentNotificationParser.isPaymentLikeTitle(payload.title)) {
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

    private fun processActiveGopayNotifications() {
        val payloads = runCatching { activeNotifications.orEmpty() }
            .onFailure { error -> Log.w(TAG, "Unable to read active notifications after reconnect", error) }
            .getOrDefault(emptyArray())
            .filter { PaymentNotificationFilter.isFromGopayMerchant(it.packageName) }
            .filterNot { PaymentNotificationFilter.isGroupSummary(it.notificationFlags()) }
            .sortedBy { it.postTime }
            .map { it.toPayload() }

        if (payloads.isEmpty()) return
        executor.execute {
            payloads.forEach(::processPayload)
        }
    }

    private fun requestUpload() {
        runCatching { UploadScheduler.uploadNow(applicationContext) }
            .onFailure { error -> Log.w(TAG, "Unable to schedule payment upload", error) }
    }

    private fun StatusBarNotification.notificationFlags(): Int = notification?.flags ?: 0

    private fun StatusBarNotification.toPayload(): NotificationPayload {
        val extras = notification.extras
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        return NotificationPayload(
            packageName = packageName,
            notificationKey = key.orEmpty(),
            postedAt = postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            body = bigText.takeIf { it.isNotBlank() } ?: text,
        )
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STATE_CHANGED = "id.ciganstore.qrislistener.PAYMENT_HISTORY_CHANGED"
        private const val TAG = "QrisListener"
        private const val PREFERENCES_NAME = "listener_diagnostics"
        private const val KEY_IGNORED_PAYLOAD_COUNT = "ignored_payload_count"
        private const val KEY_STORAGE_ERROR = "storage_error"
        @Volatile
        private var connected = false

        fun isConnected(): Boolean = connected

        fun requestConnection(context: Context) {
            NotificationListenerService.requestRebind(
                ComponentName(context.applicationContext, GopayNotificationListenerService::class.java),
            )
        }

        fun ignoredPayloadCount(context: Context): Long = preferences(context)
            .getLong(KEY_IGNORED_PAYLOAD_COUNT, 0L)

        fun hasStorageError(context: Context): Boolean = preferences(context)
            .getBoolean(KEY_STORAGE_ERROR, false)

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
