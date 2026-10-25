package id.ciganstore.qrislistener.upload

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * One-time upload, enqueued for every captured payment and by "Kirim ulang sekarang". Temporary
 * failures return [Result.retry] so WorkManager applies the exponential backoff.
 */
class UploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        val sync = ListenerSync.uploadPending(applicationContext) { isStopped }
        if (UploadWorkDecision.shouldRetry(sync)) Result.retry() else Result.success()
    } catch (error: RuntimeException) {
        // Storage or Keystore trouble: keep the events and let the backoff try again.
        Log.e(WORKER_TAG, "Payment upload run failed", error)
        Result.retry()
    }
}

/**
 * Every 15 minutes: heartbeat plus a sweep of pending events. It always succeeds; the next period
 * (or the one-time worker's own backoff) handles temporary failures.
 */
class PeriodicSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        try {
            ListenerSync.periodicSync(applicationContext) { isStopped }
        } catch (error: RuntimeException) {
            Log.e(WORKER_TAG, "Periodic listener sync failed", error)
        }
        return Result.success()
    }
}

/** Whether the one-time upload work asks WorkManager for an exponential-backoff retry. */
object UploadWorkDecision {
    fun shouldRetry(sync: ListenerSync.SyncResult): Boolean = when (sync) {
        is ListenerSync.SyncResult.Uploaded -> when (sync.result) {
            // Offline, server busy, phone clock rejected, replayed twice, or stopped by the system.
            is ListenerUploader.UploadResult.RetryLater,
            is ListenerUploader.UploadResult.Stopped,
            -> true

            // Done, or stopped until the settings change: retrying would only repeat the 401.
            is ListenerUploader.UploadResult.Completed,
            is ListenerUploader.UploadResult.Blocked,
            -> false
        }

        // Another run held the lock for too long; try again after the backoff.
        ListenerSync.SyncResult.Busy -> true

        // Nothing can be sent until the owner saves the settings; saving schedules a new upload.
        ListenerSync.SyncResult.NotConfigured,
        ListenerSync.SyncResult.SecretUnavailable,
        is ListenerSync.SyncResult.Heartbeat,
        is ListenerSync.SyncResult.Periodic,
        -> false
    }
}

private const val WORKER_TAG = "QrisUploader"
