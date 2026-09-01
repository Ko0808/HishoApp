package app.hisho.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.hisho.capture.Deduplication
import app.hisho.capture.NormalizedNotification
import app.hisho.security.EncryptedPayloadStore

class CaptureQueueDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class QueueStats(val pending: Int, val duplicates: Int, val failed: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE capture_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedup_key TEXT NOT NULL UNIQUE,
                content_hash TEXT NOT NULL,
                source_package TEXT NOT NULL,
                source_category TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                title_cipher BLOB NOT NULL,
                title_nonce BLOB NOT NULL,
                body_cipher BLOB NOT NULL,
                body_nonce BLOB NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_error_code TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX capture_queue_state_idx ON capture_queue(state, created_at)")
        db.execSQL(
            """
            CREATE TABLE capture_metrics (
                name TEXT PRIMARY KEY,
                value INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(notification: NormalizedNotification): Boolean {
        val crypto = EncryptedPayloadStore()
        val title = crypto.encrypt(notification.title)
        val body = crypto.encrypt(notification.text)
        val values = ContentValues().apply {
            put("dedup_key", Deduplication.key(notification))
            put("content_hash", Deduplication.contentHash(notification))
            put("source_package", notification.packageName)
            put("source_category", notification.sourceCategory)
            put("notification_key", notification.notificationKey)
            put("posted_at", notification.postedAtEpochMillis)
            put("title_cipher", title.cipherText)
            put("title_nonce", title.nonce)
            put("body_cipher", body.cipherText)
            put("body_nonce", body.nonce)
            put("created_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "capture_queue",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (!inserted) incrementMetric("duplicates")
        return inserted
    }

    fun stats(): QueueStats = QueueStats(
        pending = count("state IN ('PENDING','RETRY')"),
        duplicates = metric("duplicates"),
        failed = count("state = 'FAILED'"),
    )

    private fun count(where: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM capture_queue WHERE $where",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun incrementMetric(name: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO capture_metrics(name, value) VALUES(?, 1)
            ON CONFLICT(name) DO UPDATE SET value = value + 1
            """.trimIndent(),
            arrayOf(name),
        )
    }

    private fun metric(name: String): Int = readableDatabase.rawQuery(
        "SELECT value FROM capture_metrics WHERE name = ?",
        arrayOf(name),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private companion object {
        const val DATABASE_NAME = "hisho_capture.db"
        const val DATABASE_VERSION = 1
    }
}

