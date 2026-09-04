package app.hisho.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.hisho.data.CaptureQueueDatabase

class ExecutionReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (app.hisho.BuildConfig.VERSION_CODE >= 33) return Result.success()
        val captureId = inputData.getLong(KEY_CAPTURE_ID, -1)
        val blockIndex = inputData.getInt(KEY_BLOCK_INDEX, -1)
        val expectedStart = inputData.getLong(KEY_EXPECTED_START, -1)
        val phase = inputData.getString(KEY_PHASE) ?: return Result.failure()
        if (captureId < 0 || blockIndex < 1 || expectedStart < 0) return Result.failure()
        val database = CaptureQueueDatabase(applicationContext)
        val detail = database.taskDetail(captureId) ?: return Result.success()
        if (detail.state != "SYNCED") return Result.success()
        val currentBlock = detail.blocks.firstOrNull {
            it.blockIndex == blockIndex && it.startEpochMillis == expectedStart
        } ?: return Result.success()
        val now = System.currentTimeMillis()
        if (database.isRescheduleRequested(captureId)) return Result.success()
        val deliveryStore = applicationContext.getSharedPreferences("execution_deliveries", Context.MODE_PRIVATE)
        val deliveryKey = "$captureId-$blockIndex-$phase"
        if (!ReminderPolicy.shouldDeliver(now, expectedStart, currentBlock.endEpochMillis, phase == PHASE_PREPARE,
                deliveryStore.getLong(deliveryKey, -1), inputData.getBoolean("snoozed", false))) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        createChannel()
        if (applicationContext.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return Result.success()
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId(captureId, blockIndex),
            Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${currentBlock.startEpochMillis}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (phase == PHASE_PREPARE) "5分後に開始できます" else "今が実行時間です")
            .setContentText(detail.actionTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.actionTitle))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter((currentBlock.endEpochMillis - now).coerceAtLeast(1))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "完了", action(captureId, blockIndex, expectedStart, ExecutionActionReceiver.ACTION_COMPLETE, 1))
            .addAction(0, "15分後", action(captureId, blockIndex, expectedStart, ExecutionActionReceiver.ACTION_SNOOZE, 2))
            .addAction(0, "再配置", action(captureId, blockIndex, expectedStart, ExecutionActionReceiver.ACTION_REPLAN, 3))
            .build()
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()
        NotificationManagerCompat.from(applicationContext).notify(ExecutionReminderScheduler.tag(captureId), notificationId(captureId, blockIndex), notification)
        deliveryStore.edit().putLong(deliveryKey, expectedStart).apply()
        return Result.success()
    }

    private fun action(captureId: Long, blockIndex: Int, expectedStart: Long, action: String, suffix: Int) =
        PendingIntent.getBroadcast(
            applicationContext,
            notificationId(captureId, blockIndex) * 10 + suffix,
            Intent(applicationContext, ExecutionActionReceiver::class.java).apply {
                this.action = action
                putExtra(KEY_CAPTURE_ID, captureId)
                putExtra(KEY_BLOCK_INDEX, blockIndex)
                putExtra(KEY_EXPECTED_START, expectedStart)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "実行タイミング", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calendarへ配置したタスクの開始を知らせます"
            },
        )
    }

    companion object {
        const val KEY_CAPTURE_ID = "capture_id"
        const val KEY_BLOCK_INDEX = "block_index"
        const val KEY_EXPECTED_START = "expected_start"
        const val KEY_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_START = "start"
        private const val CHANNEL_ID = "hisho_execution_reminders"
        fun notificationId(captureId: Long, blockIndex: Int) = "hisho-$captureId-$blockIndex".hashCode()
    }
}
