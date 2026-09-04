package app.hisho

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.auth.EncryptedAuthStore
import app.hisho.data.CaptureQueueDatabase
import app.hisho.scheduling.RecoveryPreferences
import app.hisho.notification.ExecutionReminderScheduler
import app.hisho.scheduling.ScheduleHealth
import app.hisho.sync.CaptureSyncWorker
import app.hisho.sync.SyncStatusStore
import app.hisho.sync.TaskRecoveryWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : app.hisho.ui.GlassActivity() {
    private lateinit var automationStatus: TextView
    private lateinit var nextTask: TextView
    private lateinit var alerts: LinearLayout
    private lateinit var setup: LinearLayout
    private lateinit var syncStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(content())
        schedulePeriodicSync()
        WorkManager.getInstance(this).cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
        CaptureQueueDatabase(this).legacyTargets().forEach { ExecutionReminderScheduler.cancel(this, it.first) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun content(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 32.dp)
            setBackgroundColor(BACKGROUND)
        }
        root.addView(TextView(this).apply { text = "Hisho"; textSize = 32f; setTextColor(INK) })
        root.addView(TextView(this).apply {
            text = "覚え続けなくても、大丈夫。"; textSize = 16f; setTextColor(MUTED)
            setPadding(0, 2.dp, 0, 20.dp)
        })
        automationStatus = TextView(this).apply { textSize = 18f; setPadding(16.dp, 14.dp, 16.dp, 14.dp) }
        root.addView(automationStatus, matchWidth(bottom = 20.dp))
        root.addView(section("Google Tasksの未完了タスク"))
        nextTask = TextView(this).apply {
            textSize = 20f; setTextColor(INK); setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundColor(Color.WHITE)
        }
        root.addView(nextTask, matchWidth(bottom = 8.dp))
        root.addView(Button(this).apply {
            text = "Google Tasksを開く"; setOnClickListener { openCalendar() }
        }, matchWidth(bottom = 20.dp))
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setup, matchWidth())
        root.addView(section("対応が必要"))
        alerts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(alerts, matchWidth(bottom = 16.dp))
        root.addView(section("同期"))
        syncStatus = TextView(this).apply { textSize = 16f; setTextColor(MUTED) }
        root.addView(syncStatus, matchWidth(bottom = 8.dp))
        root.addView(Button(this).apply {
            text = "今すぐ同期"
            setOnClickListener { enqueueSync(); Toast.makeText(this@MainActivity, "同期を開始しました", Toast.LENGTH_SHORT).show() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "タスクをすぐ追加"
            setOnClickListener { startActivity(Intent(this@MainActivity, ManualTaskActivity::class.java)) }
        }, matchWidth(top = 20.dp))
        root.addView(Button(this).apply {
            text = "設定"; setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, matchWidth())
        return ScrollView(this).apply { addView(root) }
    }

    private fun render() {
        val notificationReady = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val reminderReady = app.hisho.ai.AiPreferences(this).filterReady()
        val googleReady = EncryptedAuthStore(this).isConnected()
        val stats = CaptureQueueDatabase(this).stats()
        val syncSnapshot = SyncStatusStore(this).snapshot()
        val staleSync = syncSnapshot.state == "RUNNING" &&
            System.currentTimeMillis() - syncSnapshot.updatedAt > STALE_SYNC_MILLIS
        val syncFailed = syncSnapshot.state in setOf("AUTH_REQUIRED", "API_ERROR") || staleSync
        val healthy = notificationReady && reminderReady && googleReady && stats.failed == 0 && stats.needsAttention == 0 && !syncFailed
        automationStatus.apply {
            text = if (healthy) "● スマートフィルター稼働中\n必要な通知だけGoogle Tasksへ登録します" else "● 設定・確認が必要です\n不明な通知は自動登録しません"
            setTextColor(if (healthy) SUCCESS else DANGER)
            setBackgroundColor(if (healthy) SUCCESS_BACKGROUND else WARNING_BACKGROUND)
        }
        renderNextTask()
        renderSetup(notificationReady, reminderReady, googleReady)
        renderAlerts(stats, if (staleSync) "同期が長引いています。再実行してください" else null)
        syncStatus.text = syncStatusText(syncSnapshot)
    }

    private fun renderNextTask() {
        val now = System.currentTimeMillis()
        val database = CaptureQueueDatabase(this)
        val tasks = database.recentMetadata(5, states = setOf("SYNCED"))
        nextTask.text = if (tasks.isEmpty()) "未完了のタスクはありません" else tasks.joinToString("\n") { it.actionTitle }
    }

    private fun renderSetup(notificationReady: Boolean, reminderReady: Boolean, googleReady: Boolean) {
        setup.removeAllViews()
        val progress = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (notificationReady && reminderReady && googleReady) return
        setup.addView(section("セットアップ"))
        setup.addView(TextView(this).apply {
            text = when {
                !notificationReady && !googleReady -> "通知アクセスとGoogle接続を完了してください"
                !notificationReady -> "通知を自動取得するための許可が必要です"
                !reminderReady -> "除外・最優先ルールを設定し、AI本文フィルターを有効にしてください"
                else -> "Google Tasksへ同期するため接続が必要です"
            }
            textSize = 16f; setTextColor(DANGER)
        })
        setup.addView(Button(this).apply {
            text = "セットアップを続ける"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra("onboarding", true)) }
        }, matchWidth(bottom = 12.dp))
    }

    private fun renderAlerts(stats: CaptureQueueDatabase.QueueStats, syncWarning: String?) {
        alerts.removeAllViews()
        val now = System.currentTimeMillis()
        val atRisk = CaptureQueueDatabase(this).dashboardTasks().count {
            it.state == "SYNCED" && it.deadlineEpochMillis != null && it.deadlineEpochMillis < now
        }
        val messages = buildList {
            if (syncWarning != null) add(syncWarning)
            if (stats.needsAttention > 0) add("登録前の確認待ち・要確認: ${stats.needsAttention}件")
            if (stats.failed > 0) add("同期に失敗: ${stats.failed}件")
            if (atRisk > 0) add("期限に間に合わない可能性: ${atRisk}件")
            if (stats.pending > 0) add("同期を待っているタスク: ${stats.pending}件")
        }
        if (messages.isEmpty()) {
            alerts.addView(TextView(this).apply { text = "対応が必要な項目はありません"; textSize = 16f; setTextColor(SUCCESS) })
            return
        }
        alerts.addView(TextView(this).apply {
            text = messages.joinToString("\n"); textSize = 17f; setTextColor(DANGER)
            setPadding(16.dp, 14.dp, 16.dp, 14.dp); setBackgroundColor(WARNING_BACKGROUND)
        }, matchWidth(bottom = 8.dp))
        alerts.addView(Button(this).apply {
            text = "タスクを確認する"
            setOnClickListener { startActivity(Intent(this@MainActivity, MetadataActivity::class.java)) }
        }, matchWidth())
    }

    private fun openCalendar() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.tasks")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://tasks.google.com"))
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tasks.google.com"))) }
    }

    private fun enqueueSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().setConstraints(networkConstraints()).build(),
        )
    }

    private fun schedulePeriodicSync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CaptureSyncWorker>(15, TimeUnit.MINUTES).setConstraints(networkConstraints()).build(),
        )
    }

    private fun scheduleRecoveryChecks() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TaskRecoveryWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TaskRecoveryWorker>(TaskRecoveryWorker.REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun syncStatusText(snapshot: SyncStatusStore.Snapshot): String {
        if (snapshot.state == "RUNNING" && System.currentTimeMillis() - snapshot.updatedAt > STALE_SYNC_MILLIS) {
            return "前回の同期が完了していません  •  ${formatDateTime(snapshot.updatedAt)}"
        }
        val label = when (snapshot.state) {
            "RUNNING" -> "同期しています"
            "SUCCESS" -> "正常に同期しました"
            "WAITING" -> "次の同期を待っています"
            "AUTH_REQUIRED" -> "Googleへの再接続が必要です"
            "NETWORK_ERROR" -> "通信の回復後に再試行します"
            "API_ERROR" -> "Googleとの同期に問題があります"
            else -> "まだ同期していません"
        }
        return if (snapshot.updatedAt == 0L) label else "$label  •  ${formatDateTime(snapshot.updatedAt)}"
    }

    private fun formatDateTime(epochMillis: Long?): String = epochMillis?.let {
        DATE_TIME_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "未配置"
    private fun section(label: String) = TextView(this).apply {
        text = label; textSize = 21f; setTextColor(INK); setPadding(0, 12.dp, 0, 8.dp)
    }
    private fun matchWidth(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top; bottomMargin = bottom }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND = Color.rgb(246, 247, 242)
        val INK = Color.rgb(24, 39, 34)
        val MUTED = Color.rgb(92, 101, 97)
        val SUCCESS = Color.rgb(28, 112, 76)
        val DANGER = Color.rgb(168, 62, 48)
        val SUCCESS_BACKGROUND = Color.rgb(229, 242, 234)
        val WARNING_BACKGROUND = Color.rgb(252, 237, 231)
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        const val PERIODIC_SYNC_WORK_NAME = "periodic-capture-sync"
        const val STALE_SYNC_MILLIS = 10 * 60 * 1000L
    }
}
