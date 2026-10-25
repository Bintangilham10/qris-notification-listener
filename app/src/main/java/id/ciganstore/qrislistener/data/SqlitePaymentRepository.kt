package id.ciganstore.qrislistener.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

class SqlitePaymentRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    PaymentRepository,
    UploadQueue {

    override fun onCreate(database: SQLiteDatabase) {
        // Fresh installs run the same steps as upgraded installs, so both end with one schema.
        createVersion1Table(database)
        migrateToVersion2(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToVersion2(database)
    }

    override fun insertIfNew(event: PaymentEvent): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_EVENT_ID, event.eventId)
            put(COLUMN_TYPE, event.type)
            put(COLUMN_AMOUNT, event.amount)
            put(COLUMN_MERCHANT, event.merchant)
            put(COLUMN_POSTED_AT, event.postedAt)
            put(COLUMN_CAPTURED_AT, event.capturedAt)
            put(COLUMN_SOURCE_PACKAGE, event.sourcePackage)
            put(COLUMN_UPLOAD_STATUS, UploadStatus.PENDING.storageValue)
            put(COLUMN_UPLOAD_ATTEMPTS, 0)
        }
        val insertedRowId = writableDatabase.insertWithOnConflict(
            TABLE_PAYMENTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (insertedRowId != -1L) return true
        if (containsEvent(event.eventId)) return false

        throw SQLiteException("Payment event was not inserted and no duplicate exists")
    }

    override fun listNewest(limit: Int): List<PaymentEvent> = listHistory(limit).map { it.event }

    /** Newest notification first, with the delivery state of each event. */
    fun listHistory(limit: Int): List<PaymentRecord> {
        if (limit <= 0) return emptyList()
        return readableDatabase.query(
            TABLE_PAYMENTS,
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_POSTED_AT DESC, $COLUMN_EVENT_ID DESC",
            limit.coerceAtMost(MAX_HISTORY_LIMIT).toString(),
        ).use(::readRecords)
    }

    /**
     * Deletes delivered and permanently rejected events. Events still waiting for the server
     * (pending or blocked by configuration) are kept so clearing history never loses a payment.
     */
    override fun clear() {
        writableDatabase.apply {
            beginTransaction()
            try {
                delete(
                    TABLE_PAYMENTS,
                    "$COLUMN_UPLOAD_STATUS IN (?, ?)",
                    arrayOf(UploadStatus.UPLOADED.storageValue, UploadStatus.FAILED_PERMANENT.storageValue),
                )
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    override fun pendingUploads(limit: Int): List<PaymentEvent> {
        if (limit <= 0) return emptyList()
        return readableDatabase.query(
            TABLE_PAYMENTS,
            ALL_COLUMNS,
            "$COLUMN_UPLOAD_STATUS = ?",
            arrayOf(UploadStatus.PENDING.storageValue),
            null,
            null,
            "$COLUMN_POSTED_AT ASC, $COLUMN_CAPTURED_AT ASC, $COLUMN_EVENT_ID ASC",
            limit.coerceAtMost(MAX_HISTORY_LIMIT).toString(),
        ).use(::readRecords).map { it.event }
    }

    override fun markUploaded(eventId: String, uploadedAt: Long, serverMatchStatus: String?) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PAYMENTS SET " +
                "$COLUMN_UPLOAD_STATUS = ?, " +
                "$COLUMN_UPLOAD_ATTEMPTS = $COLUMN_UPLOAD_ATTEMPTS + 1, " +
                "$COLUMN_LAST_ATTEMPT_AT = ?, " +
                "$COLUMN_UPLOADED_AT = ?, " +
                "$COLUMN_SERVER_MATCH_STATUS = ?, " +
                "$COLUMN_LAST_ERROR = NULL " +
                "WHERE $COLUMN_EVENT_ID = ?",
            arrayOf<Any?>(
                UploadStatus.UPLOADED.storageValue,
                uploadedAt,
                uploadedAt,
                serverMatchStatus?.take(MAX_TEXT_LENGTH),
                eventId,
            ),
        )
    }

    override fun markAttemptFailed(eventId: String, attemptedAt: Long, error: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PAYMENTS SET " +
                "$COLUMN_UPLOAD_ATTEMPTS = $COLUMN_UPLOAD_ATTEMPTS + 1, " +
                "$COLUMN_LAST_ATTEMPT_AT = ?, " +
                "$COLUMN_LAST_ERROR = ? " +
                "WHERE $COLUMN_EVENT_ID = ?",
            arrayOf<Any?>(attemptedAt, error.take(MAX_TEXT_LENGTH), eventId),
        )
    }

    override fun markFailedPermanent(eventId: String, attemptedAt: Long, error: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PAYMENTS SET " +
                "$COLUMN_UPLOAD_STATUS = ?, " +
                "$COLUMN_UPLOAD_ATTEMPTS = $COLUMN_UPLOAD_ATTEMPTS + 1, " +
                "$COLUMN_LAST_ATTEMPT_AT = ?, " +
                "$COLUMN_LAST_ERROR = ? " +
                "WHERE $COLUMN_EVENT_ID = ?",
            arrayOf<Any?>(UploadStatus.FAILED_PERMANENT.storageValue, attemptedAt, error.take(MAX_TEXT_LENGTH), eventId),
        )
    }

    override fun markBlockedByConfiguration(eventId: String?, attemptedAt: Long, error: String) {
        val safeError = error.take(MAX_TEXT_LENGTH)
        writableDatabase.apply {
            beginTransaction()
            try {
                if (eventId != null) {
                    execSQL(
                        "UPDATE $TABLE_PAYMENTS SET " +
                            "$COLUMN_UPLOAD_ATTEMPTS = $COLUMN_UPLOAD_ATTEMPTS + 1, " +
                            "$COLUMN_LAST_ATTEMPT_AT = ? " +
                            "WHERE $COLUMN_EVENT_ID = ?",
                        arrayOf<Any?>(attemptedAt, eventId),
                    )
                }
                execSQL(
                    "UPDATE $TABLE_PAYMENTS SET $COLUMN_UPLOAD_STATUS = ?, $COLUMN_LAST_ERROR = ? " +
                        "WHERE $COLUMN_UPLOAD_STATUS = ?",
                    arrayOf<Any?>(UploadStatus.BLOCKED_CONFIG.storageValue, safeError, UploadStatus.PENDING.storageValue),
                )
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    override fun releaseConfigurationBlock(): Int {
        val values = ContentValues().apply {
            put(COLUMN_UPLOAD_STATUS, UploadStatus.PENDING.storageValue)
        }
        return writableDatabase.update(
            TABLE_PAYMENTS,
            values,
            "$COLUMN_UPLOAD_STATUS = ?",
            arrayOf(UploadStatus.BLOCKED_CONFIG.storageValue),
        )
    }

    override fun uploadCounts(): UploadCounts {
        var pending = 0
        var uploaded = 0
        var failedPermanent = 0
        var blockedConfig = 0
        readableDatabase.rawQuery(
            "SELECT $COLUMN_UPLOAD_STATUS, COUNT(*) FROM $TABLE_PAYMENTS GROUP BY $COLUMN_UPLOAD_STATUS",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val count = cursor.getInt(1)
                when (UploadStatus.fromStorage(cursor.getString(0))) {
                    UploadStatus.PENDING -> pending += count
                    UploadStatus.UPLOADED -> uploaded += count
                    UploadStatus.FAILED_PERMANENT -> failedPermanent += count
                    UploadStatus.BLOCKED_CONFIG -> blockedConfig += count
                }
            }
        }
        return UploadCounts(pending, uploaded, failedPermanent, blockedConfig)
    }

    private fun containsEvent(eventId: String): Boolean = readableDatabase.query(
        TABLE_PAYMENTS,
        arrayOf(COLUMN_EVENT_ID),
        "$COLUMN_EVENT_ID = ?",
        arrayOf(eventId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> cursor.moveToFirst() }

    private fun readRecords(cursor: Cursor): List<PaymentRecord> {
        val eventIdIndex = cursor.getColumnIndexOrThrow(COLUMN_EVENT_ID)
        val typeIndex = cursor.getColumnIndexOrThrow(COLUMN_TYPE)
        val amountIndex = cursor.getColumnIndexOrThrow(COLUMN_AMOUNT)
        val merchantIndex = cursor.getColumnIndexOrThrow(COLUMN_MERCHANT)
        val postedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_POSTED_AT)
        val capturedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CAPTURED_AT)
        val sourcePackageIndex = cursor.getColumnIndexOrThrow(COLUMN_SOURCE_PACKAGE)
        val statusIndex = cursor.getColumnIndexOrThrow(COLUMN_UPLOAD_STATUS)
        val attemptsIndex = cursor.getColumnIndexOrThrow(COLUMN_UPLOAD_ATTEMPTS)
        val lastErrorIndex = cursor.getColumnIndexOrThrow(COLUMN_LAST_ERROR)
        val lastAttemptAtIndex = cursor.getColumnIndexOrThrow(COLUMN_LAST_ATTEMPT_AT)
        val uploadedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_UPLOADED_AT)
        val matchStatusIndex = cursor.getColumnIndexOrThrow(COLUMN_SERVER_MATCH_STATUS)

        val records = ArrayList<PaymentRecord>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            records += PaymentRecord(
                event = PaymentEvent(
                    eventId = cursor.getString(eventIdIndex),
                    type = cursor.getString(typeIndex),
                    amount = cursor.getLong(amountIndex),
                    merchant = cursor.getString(merchantIndex),
                    postedAt = cursor.getLong(postedAtIndex),
                    capturedAt = cursor.getLong(capturedAtIndex),
                    sourcePackage = cursor.getString(sourcePackageIndex),
                ),
                upload = UploadState(
                    status = UploadStatus.fromStorage(cursor.getString(statusIndex)),
                    attempts = cursor.getInt(attemptsIndex),
                    lastError = cursor.stringOrNull(lastErrorIndex),
                    lastAttemptAt = cursor.longOrNull(lastAttemptAtIndex),
                    uploadedAt = cursor.longOrNull(uploadedAtIndex),
                    serverMatchStatus = cursor.stringOrNull(matchStatusIndex),
                ),
            )
        }
        return records
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun createVersion1Table(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PAYMENTS (
                $COLUMN_EVENT_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_AMOUNT INTEGER NOT NULL CHECK($COLUMN_AMOUNT > 0),
                $COLUMN_MERCHANT TEXT NOT NULL,
                $COLUMN_POSTED_AT INTEGER NOT NULL,
                $COLUMN_SOURCE_PACKAGE TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    /**
     * Version 2 turns the history table into the upload outbox. Existing rows keep their data and
     * become `pending`, so payments captured before the uploader existed are still delivered.
     *
     * Every step is idempotent: the listener service, the activity and the upload worker each open
     * their own helper, and SQLiteOpenHelper reads the schema version before it takes the write lock,
     * so a second opener racing the first upgrade must not fail on columns that already exist.
     */
    private fun migrateToVersion2(database: SQLiteDatabase) {
        val columns = existingColumns(database)
        if (COLUMN_CAPTURED_AT !in columns) {
            database.execSQL(
                "ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_CAPTURED_AT INTEGER NOT NULL DEFAULT 0",
            )
            // Version 1 never recorded the capture time; the post time is the closest known value.
            database.execSQL("UPDATE $TABLE_PAYMENTS SET $COLUMN_CAPTURED_AT = $COLUMN_POSTED_AT")
        }
        if (COLUMN_UPLOAD_STATUS !in columns) {
            val allowedStatuses = UploadStatus.entries.joinToString(", ") { "'${it.storageValue}'" }
            database.execSQL(
                "ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_UPLOAD_STATUS TEXT NOT NULL " +
                    "DEFAULT '${UploadStatus.PENDING.storageValue}' " +
                    "CHECK($COLUMN_UPLOAD_STATUS IN ($allowedStatuses))",
            )
        }
        if (COLUMN_UPLOAD_ATTEMPTS !in columns) {
            database.execSQL(
                "ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_UPLOAD_ATTEMPTS INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (COLUMN_LAST_ERROR !in columns) {
            database.execSQL("ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_LAST_ERROR TEXT")
        }
        if (COLUMN_LAST_ATTEMPT_AT !in columns) {
            database.execSQL("ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_LAST_ATTEMPT_AT INTEGER")
        }
        if (COLUMN_UPLOADED_AT !in columns) {
            database.execSQL("ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_UPLOADED_AT INTEGER")
        }
        if (COLUMN_SERVER_MATCH_STATUS !in columns) {
            database.execSQL("ALTER TABLE $TABLE_PAYMENTS ADD COLUMN $COLUMN_SERVER_MATCH_STATUS TEXT")
        }
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS $INDEX_UPLOAD_QUEUE ON $TABLE_PAYMENTS " +
                "($COLUMN_UPLOAD_STATUS, $COLUMN_POSTED_AT, $COLUMN_EVENT_ID)",
        )
    }

    private fun existingColumns(database: SQLiteDatabase): Set<String> =
        database.rawQuery("PRAGMA table_info($TABLE_PAYMENTS)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private companion object {
        const val DATABASE_NAME = "qris_listener.db"
        const val DATABASE_VERSION = 2
        const val MAX_HISTORY_LIMIT = 200
        const val MAX_TEXT_LENGTH = 200
        const val TABLE_PAYMENTS = "payment_events"
        const val INDEX_UPLOAD_QUEUE = "payment_events_upload_queue"
        const val COLUMN_EVENT_ID = "event_id"
        const val COLUMN_TYPE = "type"
        const val COLUMN_AMOUNT = "amount"
        const val COLUMN_MERCHANT = "merchant"

        // Version 1 named this column received_at, but it always held the notification postTime.
        // The name is kept so the upgrade only adds columns and never rebuilds the table.
        const val COLUMN_POSTED_AT = "received_at"
        const val COLUMN_CAPTURED_AT = "captured_at"
        const val COLUMN_SOURCE_PACKAGE = "source_package"
        const val COLUMN_UPLOAD_STATUS = "upload_status"
        const val COLUMN_UPLOAD_ATTEMPTS = "upload_attempts"
        const val COLUMN_LAST_ERROR = "last_error"
        const val COLUMN_LAST_ATTEMPT_AT = "last_attempt_at"
        const val COLUMN_UPLOADED_AT = "uploaded_at"
        const val COLUMN_SERVER_MATCH_STATUS = "server_match_status"

        val ALL_COLUMNS = arrayOf(
            COLUMN_EVENT_ID,
            COLUMN_TYPE,
            COLUMN_AMOUNT,
            COLUMN_MERCHANT,
            COLUMN_POSTED_AT,
            COLUMN_CAPTURED_AT,
            COLUMN_SOURCE_PACKAGE,
            COLUMN_UPLOAD_STATUS,
            COLUMN_UPLOAD_ATTEMPTS,
            COLUMN_LAST_ERROR,
            COLUMN_LAST_ATTEMPT_AT,
            COLUMN_UPLOADED_AT,
            COLUMN_SERVER_MATCH_STATUS,
        )
    }
}
