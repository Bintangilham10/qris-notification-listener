package id.ciganstore.qrislistener.upload

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {
    const val UPLOAD_WORK_NAME = "qris-listener-upload"
    const val PERIODIC_WORK_NAME = "qris-listener-periodic-sync"

    /** WorkManager's minimum period; the Worker shows the listener offline after 20 silent minutes. */
    const val PERIODIC_INTERVAL_MINUTES = 15L
    const val BACKOFF_SECONDS = 30L

    /**
     * Uploads as soon as a network is available. Replaces a queued or backing-off run so a new payment
     * never waits behind an old backoff. Runs are serialized in [ListenerSync], so a replaced run that
     * is mid-request finishes recording its result before the new run reads the queue.
     */
    fun uploadNow(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE, uploadRequest())
    }

    /**
     * Keeps the 15-minute heartbeat + sweep scheduled under one unique name (never duplicated) and
     * flushes anything still pending, for example after the process was killed or the phone rebooted.
     */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest())
        workManager.enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, uploadRequest())
    }

    internal fun uploadRequest(sdkInt: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        if (sdkInt >= Build.VERSION_CODES.S) {
            // Android 12+ runs this as an expedited job, so a payment is sent right away even while
            // the app is in the background. Older versions would need a foreground service instead,
            // so they use regular work.
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    internal fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PeriodicSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
