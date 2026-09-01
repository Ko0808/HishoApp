package app.hisho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Durable seam for Phase 1 Google Tasks synchronization.
 * Until OAuth is configured, encrypted queue entries intentionally remain pending.
 */
class CaptureSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The Google Tasks sink will be connected after an Android OAuth client id is supplied.
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "capture-to-google-tasks"
    }
}

