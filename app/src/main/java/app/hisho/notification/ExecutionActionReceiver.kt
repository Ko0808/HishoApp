package app.hisho.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker

class ExecutionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val captureId = intent.getLongExtra(ExecutionReminderWorker.KEY_CAPTURE_ID, -1)
        val blockIndex = intent.getIntExtra(ExecutionReminderWorker.KEY_BLOCK_INDEX, -1)
        val expectedStart = intent.getLongExtra(ExecutionReminderWorker.KEY_EXPECTED_START, -1)
        if (captureId < 0 || blockIndex < 1) return
        when (intent.action) {
            ACTION_COMPLETE -> {
                CaptureQueueDatabase(context).requestCompletion(captureId)
                ExecutionReminderScheduler.cancel(context, captureId)
                enqueueSync(context)
            }
            ACTION_SNOOZE -> ExecutionReminderScheduler.snooze(context, captureId, blockIndex, expectedStart)
            ACTION_REPLAN -> {
                CaptureQueueDatabase(context).requestReschedule(captureId)
                ExecutionReminderScheduler.cancel(context, captureId)
                enqueueSync(context)
            }
        }
        NotificationManagerCompat.from(context).cancel(ExecutionReminderWorker.notificationId(captureId, blockIndex))
    }

    private fun enqueueSync(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().setConstraints(constraints).build(),
        )
    }

    companion object {
        const val ACTION_COMPLETE = "app.hisho.action.COMPLETE"
        const val ACTION_SNOOZE = "app.hisho.action.SNOOZE"
        const val ACTION_REPLAN = "app.hisho.action.REPLAN"
    }
}
