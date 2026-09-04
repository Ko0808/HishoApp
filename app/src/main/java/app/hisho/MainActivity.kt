package app.hisho

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import app.hisho.auth.EncryptedAuthStore
import app.hisho.data.CaptureQueueDatabase
import app.hisho.notification.ExecutionReminderScheduler
import app.hisho.sync.*
import app.hisho.ui.HishoDesign as D
import java.util.concurrent.TimeUnit

/** Home answers one question: what needs my attention now? */
class MainActivity : app.hisho.ui.GlassActivity() {
    private lateinit var summary: LinearLayout
    private lateinit var tasks: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(D.text(this, "いま、やること", 30f, true))
        root.addView(D.text(this, "通知の整理はHishoに。行動は、ひとつずつ。"), D.spacing(this))
        summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(summary, D.spacing(this, 20))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(D.text(this, "登録済みタスク", 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(D.button(this, "すべて見る") { openTasks("SYNCED") })
        root.addView(heading)
        tasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tasks, D.spacing(this))
        root.addView(D.button(this, "＋ タスクを追加") { startActivity(Intent(this, ManualTaskActivity::class.java)) }, D.spacing(this))
        root.addView(D.button(this, "Google Tasksで開く ↗") { openGoogleTasks() }, D.spacing(this, 20))
        val sync = D.card(this)
        sync.addView(app.hisho.ui.SyncStatusView(this, onSync = { enqueueSync() }) { render() })
        root.addView(sync, D.spacing(this))
        setContentView(ScrollView(this).apply { addView(root) })
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CaptureSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        WorkManager.getInstance(this).cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
        CaptureQueueDatabase(this).legacyTargets().forEach { ExecutionReminderScheduler.cancel(this, it.first) }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val database = CaptureQueueDatabase(this)
        val stats = database.stats()
        val ready = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) &&
            EncryptedAuthStore(this).isConnected() && app.hisho.ai.AiPreferences(this).filterReady()
        summary.removeAllViews()
        val card = D.card(this)
        val attention = stats.needsAttention + stats.failed
        card.addView(D.text(this, when {
            !ready -> "はじめに、接続を確認"
            attention > 0 -> "あなたの確認が必要です"
            else -> "確認待ちはありません"
        }, 22f, true))
        card.addView(D.text(this, when {
            !ready -> "通知アクセス・Google接続・AI設定を確認してください。"
            attention > 0 -> "$attention 件を保留しています。内容を見て、必要な用事だけ登録しましょう。"
            else -> "新しい通知は自動で整理します。登録済みの用事に取りかかれます。"
        }), D.spacing(this))
        card.addView(D.button(this, when {
            !ready -> "設定を確認する"
            attention > 0 -> "$attention 件を確認する"
            else -> "タスクを見る"
        }, D.PRIMARY) {
            if (!ready) startActivity(Intent(this, SettingsActivity::class.java))
            else openTasks(if (attention > 0) "ATTENTION" else "SYNCED")
        })
        card.addView(D.button(this, "未同期 ${stats.pending} 件  ›") { openTasks("PENDING") })
        summary.addView(card)
        tasks.removeAllViews()
        val recent = database.recentMetadata(3, states = setOf("SYNCED"))
        if (recent.isEmpty()) tasks.addView(D.text(this, "登録済みの未完了タスクはありません。"))
        recent.forEach { item ->
            tasks.addView(D.button(this, item.actionTitle.ifBlank { "タスクを確認" } + "  ›", "hisho:secondary") {
                startActivity(Intent(this, MetadataActivity::class.java).putExtra("filter", "SYNCED").putExtra("open_task_id", item.id))
            }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; setTextColor(app.hisho.ui.GlassUi.INK) }, D.spacing(this, 4))
        }
    }

    private fun openTasks(filter: String) = startActivity(Intent(this, MetadataActivity::class.java).putExtra("filter", filter))
    private fun openGoogleTasks() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.tasks") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://tasks.google.com"))
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tasks.google.com"))) }
    }
    private fun enqueueSync() {
        SyncStatusStore(this).markQueued()
        WorkManager.getInstance(this).enqueueUniqueWork(CaptureSyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().setConstraints(network()).build())
    }
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    companion object { const val PERIODIC_SYNC_WORK_NAME = "periodic-capture-sync" }
}
