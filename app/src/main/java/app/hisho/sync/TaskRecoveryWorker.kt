package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksTokenProvider
import app.hisho.data.CaptureQueueDatabase
import app.hisho.scheduling.RecoveryPreferences
import java.io.IOException
import java.util.concurrent.TimeUnit

class TaskRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!RecoveryPreferences(applicationContext).enabled) return Result.success()
        val token = try {
            GoogleTasksTokenProvider(applicationContext).accessToken()
        } catch (_: IOException) {
            return Result.retry()
        } ?: return Result.success()
        val database = CaptureQueueDatabase(applicationContext)
        val tasksApi = GoogleTasksApi(token)
        return try {
            val taskListId = tasksApi.findOrCreateTaskList(TASK_LIST_TITLE)
            val cutoff = System.currentTimeMillis() - GRACE_PERIOD_MILLIS
            var needsScheduling = false
            database.recoveryCandidates(cutoff).forEach { candidate ->
                val task = tasksApi.getTask(taskListId, candidate.googleTaskId)
                if (task.completed) database.markCompleted(candidate.id)
                else {
                    database.markForReschedule(candidate.id)
                    needsScheduling = true
                }
            }
            if (needsScheduling) {
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    CaptureSyncWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
                )
            }
            Result.success()
        } catch (error: GoogleTasksApi.HttpFailure) {
            if (error.status == 401) EncryptedAuthStore(applicationContext).clear()
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "recover-unfinished-tasks"
        val REPEAT_INTERVAL_MINUTES = 15L
        private val GRACE_PERIOD_MILLIS = TimeUnit.MINUTES.toMillis(10)
        private const val TASK_LIST_TITLE = "Auto Captured Tasks"
    }
}
