package id.ciganstore.qrislistener.upload

import android.content.Context
import android.content.SharedPreferences

/** Delivery diagnostics shown on the main screen. */
data class UploadStatusSnapshot(
    val lastDeliveredAt: Long?,
    val lastHeartbeatAt: Long?,
    val clockSkewMillis: Long?,
    val lastProblem: UploadProblem?,
    val lastProblemAt: Long?,
    val configurationBlock: UploadProblem?,
    val configurationBlockAt: Long?,
)

class PrefsUploadStatusStore(context: Context) : UploadStatusStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun configurationBlock(): UploadProblem? = readProblem(PREFIX_BLOCK)

    override fun recordDelivered(atMillis: Long) {
        preferences.edit()
            .putLong(KEY_LAST_DELIVERED_AT, atMillis)
            .clearProblem(PREFIX_LAST)
            .apply()
    }

    override fun recordHeartbeat(atMillis: Long, clockSkewMillis: Long?) {
        val editor = preferences.edit().putLong(KEY_LAST_HEARTBEAT_AT, atMillis)
        if (clockSkewMillis != null) {
            editor.putLong(KEY_CLOCK_SKEW_MILLIS, clockSkewMillis)
        } else {
            editor.remove(KEY_CLOCK_SKEW_MILLIS)
        }
        editor.clearProblem(PREFIX_LAST).apply()
    }

    override fun recordProblem(problem: UploadProblem, atMillis: Long) {
        preferences.edit().putProblem(PREFIX_LAST, problem, atMillis).apply()
    }

    override fun blockConfiguration(problem: UploadProblem, atMillis: Long) {
        preferences.edit()
            .putProblem(PREFIX_BLOCK, problem, atMillis)
            .putProblem(PREFIX_LAST, problem, atMillis)
            .apply()
    }

    override fun clearConfigurationBlock() {
        preferences.edit().clearProblem(PREFIX_BLOCK).apply()
    }

    /** New settings: an error caused by the old ones must not stay on screen. */
    fun clearLastProblem() {
        preferences.edit().clearProblem(PREFIX_LAST).apply()
    }

    fun snapshot(): UploadStatusSnapshot = UploadStatusSnapshot(
        lastDeliveredAt = longOrNull(KEY_LAST_DELIVERED_AT),
        lastHeartbeatAt = longOrNull(KEY_LAST_HEARTBEAT_AT),
        clockSkewMillis = longOrNull(KEY_CLOCK_SKEW_MILLIS),
        lastProblem = readProblem(PREFIX_LAST),
        lastProblemAt = longOrNull(PREFIX_LAST + SUFFIX_AT),
        configurationBlock = readProblem(PREFIX_BLOCK),
        configurationBlockAt = longOrNull(PREFIX_BLOCK + SUFFIX_AT),
    )

    private fun longOrNull(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    private fun readProblem(prefix: String): UploadProblem? {
        val kindName = preferences.getString(prefix + SUFFIX_KIND, null) ?: return null
        val kind = UploadProblemKind.entries.firstOrNull { it.name == kindName } ?: return null
        val httpStatus = preferences.getInt(prefix + SUFFIX_HTTP_STATUS, NO_HTTP_STATUS).takeIf { it != NO_HTTP_STATUS }
        return UploadProblem(kind, httpStatus, preferences.getString(prefix + SUFFIX_DETAIL, null))
    }

    private fun SharedPreferences.Editor.putProblem(
        prefix: String,
        problem: UploadProblem,
        atMillis: Long,
    ): SharedPreferences.Editor {
        putString(prefix + SUFFIX_KIND, problem.kind.name)
        putInt(prefix + SUFFIX_HTTP_STATUS, problem.httpStatus ?: NO_HTTP_STATUS)
        val detail = problem.detail
        if (detail != null) putString(prefix + SUFFIX_DETAIL, detail.take(MAX_DETAIL_LENGTH)) else remove(prefix + SUFFIX_DETAIL)
        putLong(prefix + SUFFIX_AT, atMillis)
        return this
    }

    private fun SharedPreferences.Editor.clearProblem(prefix: String): SharedPreferences.Editor =
        remove(prefix + SUFFIX_KIND)
            .remove(prefix + SUFFIX_HTTP_STATUS)
            .remove(prefix + SUFFIX_DETAIL)
            .remove(prefix + SUFFIX_AT)

    private companion object {
        const val PREFERENCES_NAME = "listener_upload_status"
        const val KEY_LAST_DELIVERED_AT = "last_delivered_at"
        const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        const val KEY_CLOCK_SKEW_MILLIS = "clock_skew_millis"
        const val PREFIX_LAST = "last_problem_"
        const val PREFIX_BLOCK = "config_block_"
        const val SUFFIX_KIND = "kind"
        const val SUFFIX_HTTP_STATUS = "http_status"
        const val SUFFIX_DETAIL = "detail"
        const val SUFFIX_AT = "at"
        const val NO_HTTP_STATUS = -1
        const val MAX_DETAIL_LENGTH = 120
    }
}
