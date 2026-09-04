package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.hisho.ai.SmartNotificationFilter
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksTokenProvider
import app.hisho.data.CaptureQueueDatabase
import app.hisho.intelligence.FilterPreferences
import app.hisho.intelligence.FilterRules
import app.hisho.notification.ExecutionReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Calendar is accessed only for explicit legacy cleanup/deletion, never creation. */
class CaptureSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { mutex.withLock { sync() } }

    private suspend fun sync(): Result {
        val status = SyncStatusStore(applicationContext)
        status.markRunning()
        val database = CaptureQueueDatabase(applicationContext)
        return try {
            val token = GoogleTasksTokenProvider(applicationContext).accessToken() ?: run {
                status.markAuthRequired(); return Result.success()
            }
            val api = GoogleTasksApi(token)
            status.markRunning("Google Tasksの登録先を確認しています")
            val listId = api.findOrCreateTaskList("Auto Captured Tasks")
            val calendar = GoogleCalendarApi(token)
            val cleanup = applicationContext.getSharedPreferences("legacy_cleanup", Context.MODE_PRIVATE)
            status.markRunning("削除・完了の変更をGoogleへ反映しています")
            cleanup.getStringSet("requested_ids", emptySet()).orEmpty().toSet().forEach { raw ->
                val id = raw.toLongOrNull() ?: return@forEach
                database.legacyEventIds(id).forEach { calendar.deleteEvent(it) }
                database.detachLegacyEvents(id)
                ExecutionReminderScheduler.cancel(applicationContext, id)
                cleanup.edit().putStringSet("requested_ids", cleanup.getStringSet("requested_ids", emptySet()).orEmpty() - raw).commit()
            }
            database.deletionRequests().forEach { request ->
                database.legacyEventIds(request.id).forEach { calendar.deleteEvent(it) }
                request.googleTaskId?.let { api.deleteTask(listId, it) }
                database.markDeleted(request.id)
                ExecutionReminderScheduler.cancel(applicationContext, request.id)
            }
            database.completionRequests().forEach { request ->
                api.completeTask(listId, request.googleTaskId)
                database.markCompleted(request.id)
                ExecutionReminderScheduler.cancel(applicationContext, request.id)
            }
            status.markRunning("Google側の完了・削除を確認しています")
            val statuses = api.taskStatuses(listId)
            database.syncTargets().forEach { task ->
                when (statuses[task.googleTaskId]) {
                    "completed" -> database.markCompleted(task.id)
                    "deleted" -> database.markRemoteDeleted(task.id)
                }
            }
            var registered = 0
            var ignored = 0
            var reviewed = 0
            val pending = database.pending()
            var position = 0
            pending.forEach { capture ->
                position++
                status.markRunning("通知を処理中 $position / ${pending.size}件（今回の処理分）: ルール確認")
                if (!database.isPending(capture.id)) return@forEach
                val configuredRules = FilterPreferences(applicationContext).rules()
                val rules = FilterRules.decide(configuredRules, capture.sourcePackage, capture.title, capture.body)
                val ruleReason = FilterRules.explanation(configuredRules, capture.sourcePackage, capture.title, capture.body)
                val manual = capture.sourcePackage == "app.hisho.manual"
                if (!manual && rules == FilterRules.Decision.EXCLUDE) {
                    database.filterResult(capture.id, "IGNORED", ruleReason)
                    ignored++
                    return@forEach
                }
                val approved = capture.decisionReason == "user_approved" || capture.decisionReason == "user_correction" || capture.decisionReason.startsWith("ai_task:")
                var title = capture.actionTitle
                if (!manual && rules == FilterRules.Decision.FORCE) {
                    title = "【最優先】" + title.removePrefix("【最優先】").take(54)
                    database.filterResult(capture.id, "PENDING", ruleReason, title, "HIGH")
                } else if (!manual && !approved) {
                    if (capture.title.isBlank() && capture.body.isBlank()) {
                        database.filterResult(capture.id, "REVIEW", "通知本文が残っていないため確認が必要")
                        reviewed++
                        return@forEach
                    }
                    status.markRunning("通知を処理中 $position / ${pending.size}件: AIの判定を待っています")
                    val decision = SmartNotificationFilter(applicationContext).classify(capture.sourcePackage, capture.title, capture.body)
                    if (decision.verdict != "task") {
                        database.filterResult(capture.id, if (decision.verdict == "ignore") "IGNORED" else "REVIEW", decision.reason)
                        if (decision.verdict == "ignore") ignored++ else reviewed++
                        return@forEach
                    }
                    title = decision.title
                    database.filterResult(capture.id, "PENDING", "ai_task: ${decision.reason}", title, "NORMAL")
                }
                if (!database.isPending(capture.id)) return@forEach
                val finalRule = FilterRules.decide(FilterPreferences(applicationContext).rules(), capture.sourcePackage, capture.title, capture.body)
                if (!manual && finalRule == FilterRules.Decision.EXCLUDE) {
                    database.filterResult(capture.id, "IGNORED", "更新された除外ルールにより登録を停止")
                    ignored++
                    return@forEach
                }
                if (!manual && finalRule == FilterRules.Decision.FORCE) {
                    title = "【最優先】" + title.removePrefix("【最優先】").take(54)
                    database.filterResult(capture.id, "PENDING", FilterRules.explanation(FilterPreferences(applicationContext).rules(), capture.sourcePackage, capture.title, capture.body), title, "HIGH")
                }
                try {
                    status.markRunning("通知を処理中 $position / ${pending.size}件: Google Tasksへ登録しています")
                    val marker = "Hisho capture: ${capture.dedupKey}"
                    val existing = api.findTaskByMarker(listId, marker)
                    val task = existing ?: api.createTask(listId, title, marker, TaskDueDate.format(capture.deadlineEpochMillis))
                    if (existing != null) api.updateTask(listId, task.id, title, TaskDueDate.format(capture.deadlineEpochMillis))
                    database.markSynced(capture.id, task.id)
                    registered++
                    if (statuses[task.id] == "completed") database.markCompleted(capture.id)
                } catch (error: IOException) {
                    database.markRetry(capture.id, error.javaClass.simpleName); throw error
                }
            }
            status.markRunning("登録済みタスクの編集内容を反映しています")
            database.unscheduled().forEach { task ->
                val detail = database.taskDetail(task.id) ?: return@forEach
                api.updateTask(listId, task.googleTaskId, detail.actionTitle, TaskDueDate.format(detail.deadlineEpochMillis))
                database.markSynced(task.id, task.googleTaskId)
            }
            if (database.stats().pending > 0 || database.unscheduled(1).isNotEmpty() ||
                database.completionRequests(1).isNotEmpty() || database.deletionRequests(1).isNotEmpty()) {
                status.markWaiting(); Result.retry()
            } else {
                val attention = database.stats().needsAttention
                status.markSuccess("今回: 登録 $registered 件・除外 $ignored 件・確認待ち $reviewed 件\n要確認は全体で $attention 件。確認待ちは詳細・操作から再判定できます。", attention > 0)
                Result.success()
            }
        } catch (error: GoogleTasksApi.HttpFailure) {
            if (error.status == 401) { EncryptedAuthStore(applicationContext).clear(); status.markAuthRequired() }
            else status.markApiError("Google Tasks", error.status)
            Result.retry()
        } catch (error: GoogleCalendarApi.HttpFailure) {
            status.markApiError("Calendarの既存予定整理", error.status); Result.retry()
        } catch (_: IOException) {
            status.markNetworkError(); Result.retry()
        } catch (error: kotlinx.coroutines.CancellationException) {
            status.markInterrupted(); throw error
        } catch (_: Exception) {
            status.markUnexpectedError(); Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "capture-to-google-tasks"
        private val mutex = Mutex()
    }
}
