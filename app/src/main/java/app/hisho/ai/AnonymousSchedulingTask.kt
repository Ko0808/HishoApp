package app.hisho.ai

import app.hisho.data.CaptureQueueDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AnonymousSchedulingTask(
    val taskId: String,
    val category: String,
    val effortMinutes: Int,
    val priority: String,
    val deadlineEpochMillis: Long?,
    val earliestStartEpochMillis: Long,
    val failedAttempts: Int,
    val createdAtEpochMillis: Long,
    val remainingEffortMinutes: Int,
    val splittable: Boolean,
) {
    fun wireFields(): Map<String, Any?> = linkedMapOf(
        "task_id" to taskId,
        "category" to category,
        "effort_minutes" to effortMinutes,
        "priority" to priority,
        "deadline" to deadlineEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
        "earliest_start" to Instant.ofEpochMilli(earliestStartEpochMillis).toString(),
        "failed_attempts" to failedAttempts,
        "created_at" to Instant.ofEpochMilli(createdAtEpochMillis).toString(),
        "remaining_effort_minutes" to remainingEffortMinutes,
        "splittable" to splittable,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        wireFields().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
}

object AnonymousSchedulingTaskMapper {
    fun from(capture: CaptureQueueDatabase.PendingCapture, nowEpochMillis: Long) = AnonymousSchedulingTask(
        taskId = "TASK-${capture.id}",
        category = capture.category,
        effortMinutes = capture.effortMinutes,
        priority = capture.priority,
        deadlineEpochMillis = capture.deadlineEpochMillis,
        earliestStartEpochMillis = nowEpochMillis,
        failedAttempts = capture.recoveryCount,
        createdAtEpochMillis = capture.createdAtEpochMillis,
        remainingEffortMinutes = capture.effortMinutes,
        splittable = capture.effortMinutes > 60,
    )

    fun toJson(tasks: List<AnonymousSchedulingTask>): String = JSONObject().apply {
        put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
    }.toString()
}
