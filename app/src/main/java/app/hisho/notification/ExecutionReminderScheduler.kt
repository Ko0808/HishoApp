package app.hisho.notification

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.data.CaptureQueueDatabase
import java.util.concurrent.TimeUnit

object ExecutionReminderScheduler {
    private const val PREPARE_MINUTES = 5L

    fun schedule(context: Context, captureId: Long, blockIndex: Int, startEpochMillis: Long) {
        val manager = WorkManager.getInstance(context)
        enqueue(manager, captureId, blockIndex, startEpochMillis, ExecutionReminderWorker.PHASE_PREPARE,
            startEpochMillis - TimeUnit.MINUTES.toMillis(PREPARE_MINUTES))
        enqueue(manager, captureId, blockIndex, startEpochMillis, ExecutionReminderWorker.PHASE_START, startEpochMillis)
    }

    fun snooze(context: Context, captureId: Long, blockIndex: Int, expectedStart: Long) {
        enqueue(
            WorkManager.getInstance(context), captureId, blockIndex, expectedStart,
            ExecutionReminderWorker.PHASE_START,
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15),
        )
    }

    fun cancel(context: Context, captureId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(captureId))
    }

    fun restoreUpcoming(context: Context) {
        CaptureQueueDatabase(context).upcomingCalendarBlocks(System.currentTimeMillis()).forEach { block ->
            schedule(context, block.captureId, block.blockIndex, block.startEpochMillis)
        }
    }

    private fun enqueue(
        manager: WorkManager,
        captureId: Long,
        blockIndex: Int,
        expectedStart: Long,
        phase: String,
        triggerAt: Long,
    ) {
        if (phase == ExecutionReminderWorker.PHASE_PREPARE && triggerAt <= System.currentTimeMillis()) return
        val data = Data.Builder()
            .putLong(ExecutionReminderWorker.KEY_CAPTURE_ID, captureId)
            .putInt(ExecutionReminderWorker.KEY_BLOCK_INDEX, blockIndex)
            .putLong(ExecutionReminderWorker.KEY_EXPECTED_START, expectedStart)
            .putString(ExecutionReminderWorker.KEY_PHASE, phase)
            .build()
        val request = OneTimeWorkRequestBuilder<ExecutionReminderWorker>()
            .setInputData(data)
            .setInitialDelay((triggerAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(tag(captureId))
            .build()
        manager.enqueueUniqueWork(workName(captureId, blockIndex, phase), ExistingWorkPolicy.REPLACE, request)
    }

    private fun tag(captureId: Long) = "execution-reminder-task-$captureId"
    private fun workName(captureId: Long, blockIndex: Int, phase: String) =
        "execution-reminder-$captureId-$blockIndex-$phase"
}
