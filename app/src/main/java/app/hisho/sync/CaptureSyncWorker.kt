package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksTokenProvider
import app.hisho.data.CaptureQueueDatabase
import app.hisho.intelligence.ActionTitleGenerator
import app.hisho.scheduling.DeterministicScheduler
import app.hisho.scheduling.SchedulingPreferences
import app.hisho.scheduling.TaskBlockPlanner
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Durable seam for Phase 1 Google Tasks synchronization.
 * Until OAuth is configured, encrypted queue entries intentionally remain pending.
 */
class CaptureSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val authStore = EncryptedAuthStore(applicationContext)
        val token = try {
            GoogleTasksTokenProvider(applicationContext).accessToken()
        } catch (_: IOException) {
            return Result.retry()
        } ?: return Result.success()
        val database = CaptureQueueDatabase(applicationContext)
        val tasksApi = GoogleTasksApi(token)
        val calendarApi = GoogleCalendarApi(token)
        val scheduler = SchedulingPreferences(applicationContext).scheduler()

        return try {
            val taskListId = tasksApi.findOrCreateTaskList(TASK_LIST_TITLE)
            database.pending().forEach { capture ->
                syncCapture(tasksApi, calendarApi, scheduler, database, taskListId, capture)
            }
            database.unscheduled().forEach { capture ->
                scheduleExisting(tasksApi, calendarApi, scheduler, database, taskListId, capture)
            }
            if (database.stats().pending > 0 || database.unscheduled(1).isNotEmpty()) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (error: GoogleTasksApi.HttpFailure) {
            if (error.status == 401) {
                authStore.clear()
                Result.retry()
            } else {
                Result.retry()
            }
        } catch (error: GoogleCalendarApi.HttpFailure) {
            if (error.status == 401) authStore.clear()
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun syncCapture(
        tasksApi: GoogleTasksApi,
        calendarApi: GoogleCalendarApi,
        scheduler: DeterministicScheduler,
        database: CaptureQueueDatabase,
        taskListId: String,
        capture: CaptureQueueDatabase.PendingCapture,
    ) {
        val marker = "Hisho capture: ${capture.dedupKey}"
        try {
            val conciseTitle = capture.actionTitle.ifBlank {
                ActionTitleGenerator.generate(capture.title, capture.body, capture.sourcePackage)
            }
            val existing = tasksApi.findTaskByMarker(taskListId, marker)
            val task = existing ?: tasksApi.createTask(
                taskListId = taskListId,
                title = conciseTitle,
                notes = buildString {
                    if (capture.title.isNotBlank() && capture.title != capture.body) {
                        append(capture.title)
                        append("\n\n")
                    }
                    append("Captured from ${capture.sourcePackage}\n")
                    append(marker)
                },
                due = capture.deadlineEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
            )
            if (existing != null) tasksApi.updateTaskTitle(taskListId, task.id, conciseTitle)
            val events = scheduleBlocks(
                calendarApi,
                scheduler,
                capture.dedupKey,
                task.id,
                conciseTitle,
                capture.effortMinutes,
                capture.deadlineEpochMillis,
                capture.recoveryCount,
            )
            database.markScheduled(
                capture.id,
                task.id,
                events.first().id,
                events.first().start.toEpochMilli(),
                events.last().end.toEpochMilli(),
                scrubPayload = true,
            )
        } catch (error: Exception) {
            database.markRetry(capture.id, error.javaClass.simpleName)
            throw error
        }
    }

    private fun scheduleExisting(
        tasksApi: GoogleTasksApi,
        calendarApi: GoogleCalendarApi,
        scheduler: DeterministicScheduler,
        database: CaptureQueueDatabase,
        taskListId: String,
        capture: CaptureQueueDatabase.UnscheduledCapture,
    ) {
        val task = tasksApi.getTask(taskListId, capture.googleTaskId)
        val conciseTitle = ActionTitleGenerator.generate("", task.title)
        if (conciseTitle != task.title) {
            tasksApi.updateTaskTitle(taskListId, task.id, conciseTitle)
        }
        val events = scheduleBlocks(
            calendarApi,
            scheduler,
            capture.dedupKey,
            task.id,
            conciseTitle,
            capture.effortMinutes,
            capture.deadlineEpochMillis,
            capture.recoveryCount,
        )
        database.markScheduled(
            capture.id,
            task.id,
            events.first().id,
            events.first().start.toEpochMilli(),
            events.last().end.toEpochMilli(),
            scrubPayload = false,
        )
    }

    private fun scheduleBlocks(
        calendarApi: GoogleCalendarApi,
        scheduler: DeterministicScheduler,
        captureId: String,
        googleTaskId: String,
        title: String,
        effortMinutes: Int,
        deadlineEpochMillis: Long?,
        recoveryCount: Int,
    ): List<GoogleCalendarApi.CalendarEvent> {
        val blocks = TaskBlockPlanner.split(effortMinutes)
        val events = mutableListOf<GoogleCalendarApi.CalendarEvent>()
        blocks.forEachIndexed { index, blockMinutes ->
            val blockId = "$captureId:recovery:$recoveryCount:block:${index + 1}"
            val now = Instant.now()
            calendarApi.findEvent(blockId, now.minus(1, ChronoUnit.DAYS))?.let {
                events += it
                return@forEachIndexed
            }
            val partTitle = if (blocks.size == 1) title else "$title (${index + 1}/${blocks.size})"
            val blockTitle = if (recoveryCount == 0) partTitle else "$partTitle [再計画$recoveryCount]"
            events += scheduleBlock(
                calendarApi, scheduler, blockId, googleTaskId, blockTitle,
                blockMinutes, deadlineEpochMillis, now,
            )
        }
        return events.sortedBy { it.start }
    }

    private fun scheduleBlock(
        calendarApi: GoogleCalendarApi,
        scheduler: DeterministicScheduler,
        captureId: String,
        googleTaskId: String,
        title: String,
        effortMinutes: Int,
        deadlineEpochMillis: Long?,
        now: Instant,
    ): GoogleCalendarApi.CalendarEvent {
        val zoneId = ZoneId.systemDefault()
        val horizon = maxOf(
            now.plus(8, ChronoUnit.DAYS),
            deadlineEpochMillis?.let(Instant::ofEpochMilli) ?: now,
        )
        val busy = calendarApi.busyIntervals(now, horizon, zoneId)
        val slot = scheduler.findSlot(
            now = now,
            durationMinutes = effortMinutes,
            deadline = deadlineEpochMillis?.let(Instant::ofEpochMilli),
            busy = busy,
        ) ?: throw IOException("No schedulable Calendar slot")
        return calendarApi.createEvent(captureId, googleTaskId, title, slot, zoneId)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "capture-to-google-tasks"
        private const val TASK_LIST_TITLE = "Auto Captured Tasks"
    }
}
