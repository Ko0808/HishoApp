package app.hisho.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.hisho.capture.Deduplication
import app.hisho.capture.NormalizedNotification
import app.hisho.security.EncryptedPayloadStore
import app.hisho.intelligence.LocalTaskProcessor
import app.hisho.intelligence.ActionTitleGenerator

class CaptureQueueDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class QueueStats(
        val pending: Int,
        val duplicates: Int,
        val failed: Int,
        val ignored: Int,
        val scheduled: Int,
    )
    data class PendingCapture(
        val id: Long,
        val dedupKey: String,
        val sourcePackage: String,
        val deadlineEpochMillis: Long?,
        val effortMinutes: Int,
        val title: String,
        val body: String,
        val actionTitle: String,
        val priority: String,
        val recoveryCount: Int,
    )
    data class UnscheduledCapture(
        val id: Long,
        val dedupKey: String,
        val googleTaskId: String,
        val deadlineEpochMillis: Long?,
        val effortMinutes: Int,
        val priority: String,
        val recoveryCount: Int,
        val rescheduleRequested: Boolean,
        val scheduledBlockCount: Int,
    )
    data class RecoveryCandidate(
        val id: Long,
        val googleTaskId: String,
        val scheduledEndEpochMillis: Long,
    )
    data class DashboardTask(
        val id: Long,
        val actionTitle: String,
        val sourcePackage: String,
        val scheduledStartEpochMillis: Long?,
        val scheduledEndEpochMillis: Long?,
        val deadlineEpochMillis: Long?,
        val recoveryCount: Int,
        val state: String,
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
        val actionTitle: String,
        val deadlineEpochMillis: Long?,
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
                candidate_reason TEXT NOT NULL DEFAULT 'legacy',
                scheduled_start INTEGER,
                scheduled_end INTEGER,
                calendar_event_id TEXT,
                action_title TEXT NOT NULL DEFAULT '',
                recovery_count INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER,
                reschedule_requested INTEGER NOT NULL DEFAULT 0,
                scheduled_block_count INTEGER NOT NULL DEFAULT 1
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
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN scheduled_start INTEGER")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN scheduled_end INTEGER")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN calendar_event_id TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN action_title TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN completed_at INTEGER")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN reschedule_requested INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE capture_queue ADD COLUMN scheduled_block_count INTEGER NOT NULL DEFAULT 1")
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
            put(
                "action_title",
                ActionTitleGenerator.generate(notification.title, notification.text, notification.packageName),
            )
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
            "category", "state", "candidate_reason", "action_title", "deadline",
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
                        actionTitle = cursor.getString(8),
                        deadlineEpochMillis = if (cursor.isNull(9)) null else cursor.getLong(9),
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
            """
            UPDATE capture_queue SET effort = ?,
                reschedule_requested = CASE WHEN state = 'SYNCED' THEN 1 ELSE reschedule_requested END
            WHERE id = ? AND state NOT IN ('FAILED','COMPLETED')
            """.trimIndent(),
            arrayOf<Any?>(next, id),
        )
    }

    fun cyclePriority(id: Long) {
        val priorities = arrayOf("LOW", "NORMAL", "HIGH")
        val current = readableDatabase.rawQuery(
            "SELECT priority FROM capture_queue WHERE id = ?",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else "NORMAL" }
        val next = priorities[(priorities.indexOf(current).coerceAtLeast(0) + 1) % priorities.size]
        writableDatabase.execSQL(
            """
            UPDATE capture_queue SET priority = ?,
                reschedule_requested = CASE WHEN state = 'SYNCED' THEN 1 ELSE reschedule_requested END
            WHERE id = ? AND state NOT IN ('FAILED','COMPLETED')
            """.trimIndent(),
            arrayOf<Any?>(next, id),
        )
    }

    fun updateDeadline(id: Long, deadlineEpochMillis: Long?) {
        val values = ContentValues().apply {
            if (deadlineEpochMillis == null) {
                putNull("deadline")
                put("deadline_type", "NONE")
            } else {
                put("deadline", deadlineEpochMillis)
                put("deadline_type", "HARD")
            }
            put("reschedule_requested", 1)
        }
        writableDatabase.update(
            "capture_queue",
            values,
            "id = ? AND state NOT IN ('FAILED','COMPLETED')",
            arrayOf(id.toString()),
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

    fun updateActionTitle(id: Long, actionTitle: String) {
        val normalized = actionTitle.replace(Regex("\\s+"), " ").trim().take(60)
        if (normalized.isBlank()) return
        val values = ContentValues().apply {
            put("action_title", normalized)
            put("reschedule_requested", 1)
        }
        writableDatabase.update(
            "capture_queue",
            values,
            "id = ? AND state NOT IN ('FAILED','COMPLETED')",
            arrayOf(id.toString()),
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
        scheduled = count("calendar_event_id IS NOT NULL"),
    )

    fun pending(limit: Int = 20): List<PendingCapture> {
        val crypto = EncryptedPayloadStore()
        return readableDatabase.query(
            "capture_queue",
            arrayOf(
                "id", "dedup_key", "source_package", "deadline", "effort", "action_title", "priority",
                "recovery_count",
                "title_cipher", "title_nonce", "body_cipher", "body_nonce",
            ),
            "state IN ('PENDING','RETRY')",
            null,
            null,
            null,
            "CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, " +
                "CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC, created_at ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val title = crypto.decrypt(
                        EncryptedPayloadStore.EncryptedValue(cursor.getBlob(6), cursor.getBlob(7)),
                    )
                    val body = crypto.decrypt(
                        EncryptedPayloadStore.EncryptedValue(cursor.getBlob(8), cursor.getBlob(9)),
                    )
                    add(
                        PendingCapture(
                            id = cursor.getLong(0),
                            dedupKey = cursor.getString(1),
                            sourcePackage = cursor.getString(2),
                            deadlineEpochMillis = if (cursor.isNull(3)) null else cursor.getLong(3),
                            effortMinutes = effortMinutes(cursor.getString(4)),
                            title = title,
                            body = body,
                            actionTitle = cursor.getString(5),
                            priority = cursor.getString(6),
                            recoveryCount = cursor.getInt(7),
                        ),
                    )
                }
            }
        }
    }

    fun unscheduled(limit: Int = 20): List<UnscheduledCapture> = readableDatabase.query(
        "capture_queue",
        arrayOf(
            "id", "dedup_key", "google_task_id", "deadline", "effort", "priority", "recovery_count",
            "reschedule_requested", "scheduled_block_count",
        ),
        "state = 'SYNCED' AND google_task_id IS NOT NULL " +
            "AND (calendar_event_id IS NULL OR reschedule_requested = 1)",
        null,
        null,
        null,
        "CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, " +
            "CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC, created_at ASC",
        limit.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    UnscheduledCapture(
                        id = cursor.getLong(0),
                        dedupKey = cursor.getString(1),
                        googleTaskId = cursor.getString(2),
                        deadlineEpochMillis = if (cursor.isNull(3)) null else cursor.getLong(3),
                        effortMinutes = effortMinutes(cursor.getString(4)),
                        priority = cursor.getString(5),
                        recoveryCount = cursor.getInt(6),
                        rescheduleRequested = cursor.getInt(7) == 1,
                        scheduledBlockCount = cursor.getInt(8),
                    ),
                )
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

    fun markScheduled(
        id: Long,
        googleTaskId: String,
        calendarEventId: String,
        scheduledStart: Long,
        scheduledEnd: Long,
        scrubPayload: Boolean,
        scheduledBlockCount: Int = 1,
    ) {
        val values = ContentValues().apply {
            put("state", "SYNCED")
            put("google_task_id", googleTaskId)
            put("calendar_event_id", calendarEventId)
            put("scheduled_start", scheduledStart)
            put("scheduled_end", scheduledEnd)
            put("scheduled_block_count", scheduledBlockCount)
            put("reschedule_requested", 0)
            putNull("last_error_code")
            if (scrubPayload) {
                put("title_cipher", ByteArray(0))
                put("title_nonce", ByteArray(0))
                put("body_cipher", ByteArray(0))
                put("body_nonce", ByteArray(0))
            }
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

    fun recoveryCandidates(endedBeforeEpochMillis: Long, limit: Int = 20): List<RecoveryCandidate> =
        readableDatabase.query(
            "capture_queue",
            arrayOf("id", "google_task_id", "scheduled_end"),
            "state = 'SYNCED' AND google_task_id IS NOT NULL AND scheduled_end IS NOT NULL " +
                "AND scheduled_end < ?",
            arrayOf(endedBeforeEpochMillis.toString()),
            null,
            null,
            "scheduled_end ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(RecoveryCandidate(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
                }
            }
        }

    fun markCompleted(id: Long) {
        val values = ContentValues().apply {
            put("state", "COMPLETED")
            put("completed_at", System.currentTimeMillis())
        }
        writableDatabase.update("capture_queue", values, "id = ?", arrayOf(id.toString()))
    }

    fun markForReschedule(id: Long) {
        writableDatabase.execSQL(
            """
            UPDATE capture_queue
            SET calendar_event_id = NULL,
                scheduled_start = NULL,
                scheduled_end = NULL,
                recovery_count = recovery_count + 1,
                last_error_code = NULL
            WHERE id = ? AND state = 'SYNCED'
            """.trimIndent(),
            arrayOf(id),
        )
    }

    fun beginRequestedReschedule(id: Long) {
        writableDatabase.execSQL(
            """
            UPDATE capture_queue
            SET calendar_event_id = NULL,
                scheduled_start = NULL,
                scheduled_end = NULL,
                recovery_count = recovery_count + 1
            WHERE id = ? AND state = 'SYNCED'
            """.trimIndent(),
            arrayOf(id),
        )
    }

    fun dashboardTasks(limit: Int = 100): List<DashboardTask> = readableDatabase.query(
        "capture_queue",
        arrayOf(
            "id", "action_title", "source_package", "scheduled_start", "scheduled_end",
            "deadline", "recovery_count", "state",
        ),
        "state IN ('SYNCED','COMPLETED')",
        null,
        null,
        null,
        "CASE WHEN scheduled_start IS NULL THEN 1 ELSE 0 END, scheduled_start ASC",
        limit.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DashboardTask(
                        id = cursor.getLong(0),
                        actionTitle = cursor.getString(1).ifBlank { "Google Tasksのタスク" },
                        sourcePackage = cursor.getString(2),
                        scheduledStartEpochMillis = if (cursor.isNull(3)) null else cursor.getLong(3),
                        scheduledEndEpochMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                        deadlineEpochMillis = if (cursor.isNull(5)) null else cursor.getLong(5),
                        recoveryCount = cursor.getInt(6),
                        state = cursor.getString(7),
                    ),
                )
            }
        }
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

    private fun effortMinutes(effort: String): Int = when (effort) {
        "XS" -> 10
        "M" -> 60
        "L" -> 120
        "XL" -> 240
        else -> 25
    }

    private companion object {
        const val DATABASE_NAME = "hisho_capture.db"
        const val DATABASE_VERSION = 7
        const val IGNORED_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}
