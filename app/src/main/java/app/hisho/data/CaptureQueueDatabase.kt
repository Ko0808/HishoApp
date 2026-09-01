package app.hisho.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.hisho.capture.Deduplication
import app.hisho.capture.NormalizedNotification
import app.hisho.security.EncryptedPayloadStore
import app.hisho.intelligence.LocalTaskProcessor

class CaptureQueueDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class QueueStats(val pending: Int, val duplicates: Int, val failed: Int, val ignored: Int)
    data class PendingCapture(
        val id: Long,
        val dedupKey: String,
        val sourcePackage: String,
        val title: String,
        val body: String,
    )
    data class MetadataItem(
        val id: Long,
        val sourcePackage: String,
        val deadlineType: String,
        val effort: String,
        val priority: String,
        val category: String,
        val state: String,
        val reason: String,
    )

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
                last_error_code TEXT,
                google_task_id TEXT,
                deadline INTEGER,
                deadline_type TEXT NOT NULL DEFAULT 'SOFT',
                effort TEXT NOT NULL DEFAULT 'S',
                priority TEXT NOT NULL DEFAULT 'NORMAL',
                category TEXT NOT NULL DEFAULT 'OTHER',
                is_candidate INTEGER NOT NULL DEFAULT 1,
                candidate_reason TEXT NOT NULL DEFAULT 'legacy'
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN google_task_id TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN deadline INTEGER")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN deadline_type TEXT NOT NULL DEFAULT 'SOFT'")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN effort TEXT NOT NULL DEFAULT 'S'")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN is_candidate INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN candidate_reason TEXT NOT NULL DEFAULT 'legacy'")
        }
    }

    fun enqueue(notification: NormalizedNotification): Boolean {
        scrubExpiredIgnoredPayloads()
        val crypto = EncryptedPayloadStore()
        val metadata = LocalTaskProcessor().process(notification)
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
            put("state", if (metadata.isCandidate) "PENDING" else "IGNORED")
            metadata.deadlineEpochMillis?.let { put("deadline", it) }
            put("deadline_type", metadata.deadlineType.name)
            put("effort", metadata.effort.name)
            put("priority", metadata.priority.name)
            put("category", metadata.category.name)
            put("is_candidate", if (metadata.isCandidate) 1 else 0)
            put("candidate_reason", metadata.candidateReason)
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

    fun recentMetadata(limit: Int = 30): List<MetadataItem> = readableDatabase.query(
        "capture_queue",
        arrayOf(
            "id", "source_package", "deadline_type", "effort", "priority",
            "category", "state", "candidate_reason",
        ),
        null,
        null,
        null,
        null,
        "created_at DESC",
        limit.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    MetadataItem(
                        id = cursor.getLong(0),
                        sourcePackage = cursor.getString(1),
                        deadlineType = cursor.getString(2),
                        effort = cursor.getString(3),
                        priority = cursor.getString(4),
                        category = cursor.getString(5),
                        state = cursor.getString(6),
                        reason = cursor.getString(7),
                    ),
                )
            }
        }
    }

    fun cycleEffort(id: Long) {
        val efforts = arrayOf("XS", "S", "M", "L", "XL")
        val current = readableDatabase.rawQuery(
            "SELECT effort FROM capture_queue WHERE id = ?",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else "S" }
        val next = efforts[(efforts.indexOf(current).coerceAtLeast(0) + 1) % efforts.size]
        writableDatabase.execSQL(
            "UPDATE capture_queue SET effort = ? WHERE id = ?",
            arrayOf<Any?>(next, id),
        )
    }

    fun toggleCandidate(id: Long) {
        writableDatabase.execSQL(
            """
            UPDATE capture_queue
            SET is_candidate = CASE WHEN is_candidate = 1 THEN 0 ELSE 1 END,
                state = CASE
                    WHEN state IN ('SYNCED', 'FAILED') THEN state
                    WHEN is_candidate = 1 THEN 'IGNORED'
                    ELSE 'PENDING'
                END,
                candidate_reason = 'user_correction'
            WHERE id = ?
            """.trimIndent(),
            arrayOf(id),
        )
    }

    private fun scrubExpiredIgnoredPayloads() {
        val cutoff = System.currentTimeMillis() - IGNORED_RETENTION_MILLIS
        val values = ContentValues().apply {
            put("title_cipher", ByteArray(0))
            put("title_nonce", ByteArray(0))
            put("body_cipher", ByteArray(0))
            put("body_nonce", ByteArray(0))
        }
        writableDatabase.update(
            "capture_queue",
            values,
            "state = 'IGNORED' AND created_at < ?",
            arrayOf(cutoff.toString()),
        )
    }

    fun stats(): QueueStats = QueueStats(
        pending = count("state IN ('PENDING','RETRY')"),
        duplicates = metric("duplicates"),
        failed = count("state = 'FAILED'"),
        ignored = count("state = 'IGNORED'"),
    )

    fun pending(limit: Int = 20): List<PendingCapture> {
        val crypto = EncryptedPayloadStore()
        return readableDatabase.query(
            "capture_queue",
            arrayOf(
                "id", "dedup_key", "source_package",
                "title_cipher", "title_nonce", "body_cipher", "body_nonce",
            ),
            "state IN ('PENDING','RETRY')",
            null,
            null,
            null,
            "created_at ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val title = crypto.decrypt(
                        EncryptedPayloadStore.EncryptedValue(cursor.getBlob(3), cursor.getBlob(4)),
                    )
                    val body = crypto.decrypt(
                        EncryptedPayloadStore.EncryptedValue(cursor.getBlob(5), cursor.getBlob(6)),
                    )
                    add(
                        PendingCapture(
                            id = cursor.getLong(0),
                            dedupKey = cursor.getString(1),
                            sourcePackage = cursor.getString(2),
                            title = title,
                            body = body,
                        ),
                    )
                }
            }
        }
    }

    fun markSynced(id: Long, googleTaskId: String) {
        val values = ContentValues().apply {
            put("state", "SYNCED")
            put("google_task_id", googleTaskId)
            put("title_cipher", ByteArray(0))
            put("title_nonce", ByteArray(0))
            put("body_cipher", ByteArray(0))
            put("body_nonce", ByteArray(0))
            putNull("last_error_code")
        }
        writableDatabase.update("capture_queue", values, "id = ?", arrayOf(id.toString()))
    }

    fun markRetry(id: Long, errorCode: String) {
        writableDatabase.execSQL(
            """
            UPDATE capture_queue
            SET state = 'RETRY', attempts = attempts + 1, last_error_code = ?
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any?>(errorCode.take(64), id),
        )
    }

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
        const val DATABASE_VERSION = 3
        const val IGNORED_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}
