package id.ciganstore.qrislistener.upload

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSchedulingTest {
    @Test
    fun `upload work waits for a network and backs off exponentially`() {
        val spec = UploadScheduler.uploadRequest(sdkInt = 34).workSpec

        assertEquals(UploadWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertTrue(spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
    }

    @Test
    fun `before Android 12 upload work is regular work`() {
        // Expedited work would need a foreground service (and its permission) on older versions.
        val spec = UploadScheduler.uploadRequest(sdkInt = 30).workSpec

        assertFalse(spec.expedited)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
    }

    @Test
    fun `heartbeat sweep runs every 15 minutes with a network`() {
        val spec = UploadScheduler.periodicRequest().workSpec

        assertEquals(PeriodicSyncWorker::class.java.name, spec.workerClassName)
        assertTrue(spec.isPeriodic)
        assertEquals(15 * 60_000L, spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `unique work names keep one upload chain and one periodic sweep`() {
        assertEquals("qris-listener-upload", UploadScheduler.UPLOAD_WORK_NAME)
        assertEquals("qris-listener-periodic-sync", UploadScheduler.PERIODIC_WORK_NAME)
    }

    @Test
    fun `temporary failures ask WorkManager for a backoff retry`() {
        val retried = listOf(
            ListenerSync.SyncResult.Uploaded(
                ListenerUploader.UploadResult.RetryLater(0, UploadProblem(UploadProblemKind.NETWORK)),
            ),
            ListenerSync.SyncResult.Uploaded(
                ListenerUploader.UploadResult.RetryLater(2, UploadProblem(UploadProblemKind.SERVER_ERROR, 503)),
            ),
            ListenerSync.SyncResult.Uploaded(ListenerUploader.UploadResult.Stopped(1)),
            ListenerSync.SyncResult.Busy,
        )

        retried.forEach { result -> assertTrue(result.toString(), UploadWorkDecision.shouldRetry(result)) }
    }

    @Test
    fun `finished, blocked and unconfigured runs end without a retry`() {
        val finished = listOf(
            ListenerSync.SyncResult.Uploaded(ListenerUploader.UploadResult.Completed(delivered = 3, rejected = 1)),
            // Wrong secret or device: retrying would only repeat the 401 until the settings change.
            ListenerSync.SyncResult.Uploaded(
                ListenerUploader.UploadResult.Blocked(
                    0,
                    UploadProblem(UploadProblemKind.AUTH_REJECTED, 401, "invalid_listener_auth"),
                ),
            ),
            ListenerSync.SyncResult.NotConfigured,
            ListenerSync.SyncResult.SecretUnavailable,
        )

        finished.forEach { result -> assertFalse(result.toString(), UploadWorkDecision.shouldRetry(result)) }
    }
}
