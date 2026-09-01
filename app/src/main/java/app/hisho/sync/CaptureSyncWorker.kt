package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.hisho.auth.EncryptedAuthStore
import app.hisho.data.CaptureQueueDatabase
import java.io.IOException

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
        val token = authStore.accessToken() ?: return Result.success()
        val database = CaptureQueueDatabase(applicationContext)
        val api = GoogleTasksApi(token)

        return try {
            val taskListId = api.findOrCreateTaskList(TASK_LIST_TITLE)
            database.pending().forEach { capture ->
                syncCapture(api, database, taskListId, capture)
            }
            if (database.stats().pending > 0) Result.retry() else Result.success()
        } catch (error: GoogleTasksApi.HttpFailure) {
            if (error.status == 401) {
                authStore.clear()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun syncCapture(
        api: GoogleTasksApi,
        database: CaptureQueueDatabase,
        taskListId: String,
        capture: CaptureQueueDatabase.PendingCapture,
    ) {
        val marker = "Hisho capture: ${capture.dedupKey}"
        try {
            val existing = api.findTaskByMarker(taskListId, marker)
            val task = existing ?: api.createTask(
                taskListId = taskListId,
                title = capture.body.ifBlank { capture.title }.take(1_024),
                notes = buildString {
                    if (capture.title.isNotBlank() && capture.title != capture.body) {
                        append(capture.title)
                        append("\n\n")
                    }
                    append("Captured from ${capture.sourcePackage}\n")
                    append(marker)
                },
            )
            database.markSynced(capture.id, task.id)
        } catch (error: Exception) {
            database.markRetry(capture.id, error.javaClass.simpleName)
            throw error
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "capture-to-google-tasks"
        private const val TASK_LIST_TITLE = "Auto Captured Tasks"
    }
}
