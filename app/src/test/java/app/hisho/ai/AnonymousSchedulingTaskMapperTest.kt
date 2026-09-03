package app.hisho.ai

import app.hisho.data.CaptureQueueDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousSchedulingTaskMapperTest {
    @Test
    fun `payload excludes all private task fields`() {
        val privateCapture = CaptureQueueDatabase.PendingCapture(
            id = 42,
            dedupKey = "secret-dedup-key",
            sourcePackage = "private.source.package",
            deadlineEpochMillis = 1_800_000_000_000,
            effortMinutes = 120,
            title = "A社の田中さん",
            body = "secret@example.comへ資料を送る https://example.com",
            actionTitle = "機密プロジェクト資料を送る",
            priority = "HIGH",
            category = "COMMUNICATION",
            recoveryCount = 2,
            createdAtEpochMillis = 1_700_000_000_000,
        )

        val fields = AnonymousSchedulingTaskMapper.from(privateCapture, 1_750_000_000_000).wireFields()
        val payload = fields.values.joinToString("|")

        assertTrue(payload.contains("TASK-42"))
        assertTrue(payload.contains("COMMUNICATION"))
        assertEquals(
            setOf(
                "task_id", "category", "effort_minutes", "priority", "deadline", "earliest_start",
                "failed_attempts", "created_at", "remaining_effort_minutes", "splittable",
            ),
            fields.keys,
        )
        listOf(
            privateCapture.dedupKey,
            privateCapture.sourcePackage,
            privateCapture.title,
            privateCapture.body,
            privateCapture.actionTitle,
            "secret@example.com",
            "https://example.com",
        ).forEach { assertFalse("Leaked private value: $it", payload.contains(it)) }
    }
}
