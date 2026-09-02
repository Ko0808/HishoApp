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
        val statusStore = SyncStatusStore(applicationContext)
        statusStore.markRunning()
        val authStore = EncryptedAuthStore(applicationContext)
        val token = try {
            GoogleTasksTokenProvider(applicationContext).accessToken()
        } catch (_: IOException) {
            statusStore.markNetworkError()
            return Result.retry()
        } ?: run {
            statusStore.markAuthRequired()
            return Result.success()
        }
        val database = CaptureQueueDatabase(applicationContext)
        val tasksApi = GoogleTasksApi(token)
        val calendarApi = GoogleCalendarApi(token)
        val scheduler = SchedulingPreferences(applicationContext).scheduler()

        return try {
            val taskListId = tasksApi.findOrCreateTaskList(TASK_LIST_TITLE)
            database.deletionRequests().forEach { request ->
                database.calendarBlocks(request.id).forEach { block ->
                    calendarApi.deleteEvent(block.calendarEventId)
                }
                request.googleTaskId?.let { tasksApi.deleteTask(taskListId, it) }
                database.markDeleted(request.id)
            }
            database.completionRequests().forEach { request ->
                tasksApi.completeTask(taskListId, request.googleTaskId)
                database.markCompleted(request.id)
            }
            reconcileCalendarBlocks(calendarApi, database)
            database.pending().forEach { capture ->
                syncCapture(tasksApi, calendarApi, scheduler, database, taskListId, capture)
            }
            database.unscheduled().forEach { capture ->
                scheduleExisting(tasksApi, calendarApi, scheduler, database, taskListId, capture)
            }
            if (database.stats().pending > 0 || database.unscheduled(1).isNotEmpty()) {
                statusStore.markWaiting()
                Result.retry()
            } else {
                statusStore.markSuccess()
                Result.success()
            }
        } catch (error: GoogleTasksApi.HttpFailure) {
            if (error.status == 401) {
                authStore.clear()
                statusStore.markAuthRequired()
            } else {
                statusStore.markApiError("Google Tasks", error.status)
            }
            Result.retry()
        } catch (error: GoogleCalendarApi.HttpFailure) {
            if (error.status == 401) {
                authStore.clear()
                statusStore.markAuthRequired()
            } else statusStore.markApiError("Google Calendar", error.status)
            Result.retry()
        } catch (_: IOException) {
            statusStore.markNetworkError()
            Result.retry()
        }
    }

    private fun reconcileCalendarBlocks(
        calendarApi: GoogleCalendarApi,
        database: CaptureQueueDatabase,
    ) {
        database.calendarBlocksToCheck().forEach { block ->
            val event = calendarApi.getEventOrNull(block.calendarEventId)
            if (event == null) database.markCalendarBlockMissing(block.captureId, block.calendarEventId)
            else database.updateCalendarBlock(
                block.captureId,
                block.calendarEventId,
                event.start.toEpochMilli(),
                event.end.toEpochMilli(),
            )
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
                scheduledBlockCount = events.size,
            )
            database.replaceCalendarBlocks(capture.id, events.toBlockRecords(capture.recoveryCount))
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
        val metadata = database.recentMetadata(100).firstOrNull { it.id == capture.id }
        val conciseTitle = metadata?.actionTitle?.ifBlank { null }
            ?: ActionTitleGenerator.generate("", task.title)
        tasksApi.updateTask(
            taskListId,
            task.id,
            conciseTitle,
            capture.deadlineEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
        )
        var recoveryCount = capture.recoveryCount
        if (capture.rescheduleRequested) {
            deleteScheduledBlocks(calendarApi, database, capture)
            database.beginRequestedReschedule(capture.id)
            recoveryCount += 1
        }
        val events = scheduleBlocks(
            calendarApi,
            scheduler,
            capture.dedupKey,
            task.id,
            conciseTitle,
            capture.effortMinutes,
            capture.deadlineEpochMillis,
            recoveryCount,
        )
        database.markScheduled(
            capture.id,
            task.id,
            events.first().id,
            events.first().start.toEpochMilli(),
            events.last().end.toEpochMilli(),
            scrubPayload = false,
            scheduledBlockCount = events.size,
        )
        database.replaceCalendarBlocks(capture.id, events.toBlockRecords(recoveryCount))
    }

    private fun deleteScheduledBlocks(
        calendarApi: GoogleCalendarApi,
        database: CaptureQueueDatabase,
        capture: CaptureQueueDatabase.UnscheduledCapture,
    ) {
        val tracked = database.calendarBlocks(capture.id)
        if (tracked.isNotEmpty()) {
            tracked.forEach { calendarApi.deleteEvent(it.calendarEventId) }
            database.clearCalendarBlocks(capture.id)
            return
        }
        val from = Instant.now().minus(30, ChronoUnit.DAYS)
        val identifiers = buildList {
            add(capture.dedupKey) // Compatibility with schedules created before block splitting.
            repeat(capture.scheduledBlockCount) { index ->
                add("${capture.dedupKey}:recovery:${capture.recoveryCount}:block:${index + 1}")
            }
        }
        identifiers.distinct().forEach { identifier ->
            calendarApi.findEvent(identifier, from)?.let { calendarApi.deleteEvent(it.id) }
        }
    }

    private fun List<GoogleCalendarApi.CalendarEvent>.toBlockRecords(
        generation: Int,
    ): List<CaptureQueueDatabase.CalendarBlock> = sortedBy { it.start }.mapIndexed { index, event ->
        CaptureQueueDatabase.CalendarBlock(
            blockIndex = index + 1,
            generation = generation,
            calendarEventId = event.id,
            startEpochMillis = event.start.toEpochMilli(),
            endEpochMillis = event.end.toEpochMilli(),
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
