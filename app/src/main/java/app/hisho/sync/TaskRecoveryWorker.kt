package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Retire legacy recovery jobs after the Tasks-only migration. */
class TaskRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = Result.success()
    companion object {
        const val UNIQUE_WORK_NAME = "recover-unfinished-tasks"
        const val REPEAT_INTERVAL_MINUTES = 15L
    }
}
