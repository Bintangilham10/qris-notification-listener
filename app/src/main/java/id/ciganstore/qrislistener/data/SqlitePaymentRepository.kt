package id.ciganstore.qrislistener.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

class SqlitePaymentRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    PaymentRepository {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_PAYMENTS (
                $COLUMN_EVENT_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_AMOUNT INTEGER NOT NULL CHECK($COLUMN_AMOUNT > 0),
                $COLUMN_MERCHANT TEXT NOT NULL,
                $COLUMN_RECEIVED_AT INTEGER NOT NULL,
                $COLUMN_SOURCE_PACKAGE TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun insertIfNew(event: PaymentEvent): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_EVENT_ID, event.eventId)
            put(COLUMN_TYPE, event.type)
            put(COLUMN_AMOUNT, event.amount)
            put(COLUMN_MERCHANT, event.merchant)
            put(COLUMN_RECEIVED_AT, event.receivedAt)
            put(COLUMN_SOURCE_PACKAGE, event.sourcePackage)
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

    override fun listNewest(limit: Int): List<PaymentEvent> {
        if (limit <= 0) return emptyList()
        val safeLimit = limit.coerceAtMost(MAX_HISTORY_LIMIT)
        val events = mutableListOf<PaymentEvent>()

        readableDatabase.query(
            TABLE_PAYMENTS,
            arrayOf(
                COLUMN_EVENT_ID,
                COLUMN_TYPE,
                COLUMN_AMOUNT,
                COLUMN_MERCHANT,
                COLUMN_RECEIVED_AT,
                COLUMN_SOURCE_PACKAGE,
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_RECEIVED_AT DESC, $COLUMN_EVENT_ID DESC",
            safeLimit.toString(),
        ).use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(COLUMN_EVENT_ID)
            val typeIndex = cursor.getColumnIndexOrThrow(COLUMN_TYPE)
            val amountIndex = cursor.getColumnIndexOrThrow(COLUMN_AMOUNT)
            val merchantIndex = cursor.getColumnIndexOrThrow(COLUMN_MERCHANT)
            val receivedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_RECEIVED_AT)
            val sourcePackageIndex = cursor.getColumnIndexOrThrow(COLUMN_SOURCE_PACKAGE)

            while (cursor.moveToNext()) {
                events += PaymentEvent(
                    eventId = cursor.getString(eventIdIndex),
                    type = cursor.getString(typeIndex),
                    amount = cursor.getLong(amountIndex),
                    merchant = cursor.getString(merchantIndex),
                    receivedAt = cursor.getLong(receivedAtIndex),
                    sourcePackage = cursor.getString(sourcePackageIndex),
                )
            }
        }
        return events
    }

    override fun clear() {
        writableDatabase.apply {
            beginTransaction()
            try {
                delete(TABLE_PAYMENTS, null, null)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
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

    private companion object {
        const val DATABASE_NAME = "qris_listener.db"
        const val DATABASE_VERSION = 1
        const val MAX_HISTORY_LIMIT = 200
        const val TABLE_PAYMENTS = "payment_events"
        const val COLUMN_EVENT_ID = "event_id"
        const val COLUMN_TYPE = "type"
        const val COLUMN_AMOUNT = "amount"
        const val COLUMN_MERCHANT = "merchant"
        const val COLUMN_RECEIVED_AT = "received_at"
        const val COLUMN_SOURCE_PACKAGE = "source_package"
    }
}
