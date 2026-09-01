package app.hisho.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker

class HishoNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!CapturePreferences(this).isEnabled(sbn.packageName)) return

        // This callback is on the main thread. Only bounded extraction and one durable insert occur here.
        val notification = NotificationNormalizer.normalize(sbn)
        if (notification.title.isBlank() && notification.text.isBlank()) return

        if (CaptureQueueDatabase(this).enqueue(notification)) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                CaptureSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
            )
        }
    }
}

