package id.ciganstore.qrislistener.upload

import android.content.Context
import android.content.Intent
import android.util.Log
import id.ciganstore.qrislistener.BuildConfig
import id.ciganstore.qrislistener.data.SqlitePaymentRepository
import id.ciganstore.qrislistener.notification.GopayNotificationListenerService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Wires the settings, the SQLite outbox and HTTPS together. Runs are serialized per process, so the
 * one-time worker, the periodic worker and "Tes koneksi" never send the same event concurrently.
 */
object ListenerSync {
    sealed interface SyncResult {
        data object NotConfigured : SyncResult
        data object SecretUnavailable : SyncResult
        data object Busy : SyncResult
        data class Uploaded(val result: ListenerUploader.UploadResult) : SyncResult
        data class Heartbeat(val result: ListenerUploader.HeartbeatResult) : SyncResult
        data class Periodic(
            /** Null when the heartbeat was skipped because the server rejected the device settings. */
            val heartbeat: ListenerUploader.HeartbeatResult?,
            val upload: ListenerUploader.UploadResult?,
        ) : SyncResult
    }

    private const val TAG = "QrisUploader"
    private const val LOCK_WAIT_SECONDS = 45L
    private val runLock = ReentrantLock()

    /** One-time work: deliver pending events oldest first, then refresh the heartbeat. */
    fun uploadPending(context: Context, isStopped: () -> Boolean): SyncResult = withUploader(context) { uploader ->
        val upload = uploader.uploadPending(isStopped)
        if (upload.delivered > 0 && !isStopped()) uploader.sendHeartbeat()
        SyncResult.Uploaded(upload)
    }

    /**
     * Periodic work: heartbeat, then a sweep of pending events. While the server rejects the secret or
     * device ID nothing is sent (the contract: stop until the settings change); the sweep then only
     * parks new events without touching the network.
     */
    fun periodicSync(context: Context, isStopped: () -> Boolean): SyncResult = withUploader(context) { uploader ->
        val heartbeat = if (uploader.isConfigurationBlocked()) null else uploader.sendHeartbeat()
        val upload = when {
            isStopped() -> null
            // Offline, server busy or clock rejected: the events would fail the same way right now.
            heartbeat is ListenerUploader.HeartbeatResult.Failure && heartbeat.problem.kind.isTransient -> null
            else -> uploader.uploadPending(isStopped)
        }
        SyncResult.Periodic(heartbeat, upload)
    }

    /**
     * "Tes koneksi": one signed heartbeat, sent even while a configuration block stands because the
     * owner asked for it. A success lifts the block, so anything still waiting is sent right away.
     */
    fun testConnection(context: Context): SyncResult {
        val result = withUploader(context) { uploader -> SyncResult.Heartbeat(uploader.sendHeartbeat()) }
        if ((result as? SyncResult.Heartbeat)?.result is ListenerUploader.HeartbeatResult.Success) {
            UploadScheduler.uploadNow(context)
        }
        return result
    }

    /**
     * "Kirim ulang sekarang": lifts a configuration block, returns parked events to the queue and
     * starts an upload as soon as a network is available. Rejected events stay rejected.
     */
    fun retryNow(context: Context) {
        val appContext = context.applicationContext
        whileIdle {
            PrefsUploadStatusStore(appContext).clearConfigurationBlock()
            releaseParkedEvents(appContext)
        }
        UploadScheduler.ensureScheduled(appContext)
        UploadScheduler.uploadNow(appContext)
        notifyStateChanged(appContext)
    }

    /**
     * Saves validated settings. It waits for a running upload to finish first, so a request signed with
     * the old settings cannot block the new ones. A null [secretKey] keeps the stored secret.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun saveSettings(context: Context, baseUrl: String, deviceId: String, secretKey: ByteArray?): Boolean {
        val appContext = context.applicationContext
        val saved = whileIdle {
            val stored = ListenerSettingsStore(appContext, allowDebugCleartext = BuildConfig.DEBUG)
                .save(baseUrl, deviceId, secretKey)
            if (stored) {
                PrefsUploadStatusStore(appContext).apply {
                    clearConfigurationBlock()
                    clearLastProblem()
                }
                releaseParkedEvents(appContext)
            }
            stored
        }
        if (saved) {
            UploadScheduler.ensureScheduled(appContext)
            UploadScheduler.uploadNow(appContext)
        }
        notifyStateChanged(appContext)
        return saved
    }

    /**
     * Runs [block] while no upload run holds the lock. If a run does not finish in time the block runs
     * anyway: the next run releases events parked by a stale request (see [ListenerUploader]).
     */
    private fun <T> whileIdle(block: () -> T): T {
        val locked = try {
            runLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        try {
            return block()
        } finally {
            if (locked) runLock.unlock()
        }
    }

    private fun withUploader(context: Context, block: (ListenerUploader) -> SyncResult): SyncResult {
        val appContext = context.applicationContext
        val locked = try {
            runLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) return SyncResult.Busy
        try {
            return runLocked(appContext, block)
        } finally {
            runLock.unlock()
            notifyStateChanged(appContext)
        }
    }

    private fun runLocked(context: Context, block: (ListenerUploader) -> SyncResult): SyncResult {
        val statusStore = PrefsUploadStatusStore(context)
        val settings = ListenerSettingsStore(context, allowDebugCleartext = BuildConfig.DEBUG)
        val config = when (val loaded = settings.load()) {
            ListenerSettingsStore.LoadResult.NotConfigured -> return SyncResult.NotConfigured
            ListenerSettingsStore.LoadResult.SecretUnavailable -> {
                statusStore.recordProblem(UploadProblem(UploadProblemKind.SECRET_UNAVAILABLE), System.currentTimeMillis())
                return SyncResult.SecretUnavailable
            }
            is ListenerSettingsStore.LoadResult.Ready -> loaded.config
        }

        val signer = try {
            QrisSigner(config.secretKey)
        } finally {
            config.secretKey.fill(0)
        }
        val requests = ListenerRequestFactory(
            baseUrl = config.baseUrl,
            deviceId = config.deviceId,
            signer = signer,
            appVersion = BuildConfig.VERSION_NAME,
            nowMillis = System::currentTimeMillis,
        )
        val transport = HttpListenerTransport(requests, allowCleartext = BuildConfig.DEBUG)
        // SQLiteOpenHelper is only AutoCloseable from API 29, so no use {} with minSdk 26.
        val repository = SqlitePaymentRepository(context)
        return try {
            block(ListenerUploader(repository, statusStore, transport, System::currentTimeMillis))
        } finally {
            repository.close()
        }
    }

    private fun releaseParkedEvents(context: Context) {
        val repository = SqlitePaymentRepository(context)
        try {
            repository.releaseConfigurationBlock()
        } finally {
            repository.close()
        }
    }

    private fun notifyStateChanged(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(GopayNotificationListenerService.ACTION_STATE_CHANGED).setPackage(context.packageName),
            )
        }.onFailure { error -> Log.w(TAG, "Unable to publish upload state", error) }
    }
}
