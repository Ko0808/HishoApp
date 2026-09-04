package app.hisho

import android.app.Activity
import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.ai.AiPreferences
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksAuthorization
import app.hisho.capture.CapturePreferences
import app.hisho.notification.ExecutionReminderScheduler
import app.hisho.scheduling.RecoveryPreferences
import app.hisho.scheduling.SchedulingPreferences
import app.hisho.sync.CaptureSyncWorker
import app.hisho.sync.TaskRecoveryWorker
import java.util.concurrent.TimeUnit

class SettingsActivity : Activity() {
    private lateinit var authorization: GoogleTasksAuthorization

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorization = GoogleTasksAuthorization(this)
    }

    override fun onResume() {
        super.onResume()
        setContentView(content())
    }

    private fun content(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 32.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "設定"; textSize = 28f; setTextColor(INK) })
        root.addView(TextView(this).apply {
            text = "一度設定すれば、Hishoが自動で収集・配置・再計画します。"
            setPadding(0, 6.dp, 0, 12.dp); setTextColor(MUTED)
        })

        root.addView(section("通知の自動取得"))
        val notificationReady = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        root.addView(status(if (notificationReady) "有効" else "許可が必要です", notificationReady))
        if (!notificationReady) root.addView(Button(this).apply {
            text = "通知アクセスを許可"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }, matchWidth())
        val capture = CapturePreferences(this)
        CapturePreferences.SUPPORTED_PACKAGES.forEach { (packageName, label) ->
            root.addView(CheckBox(this).apply {
                text = label; isChecked = capture.isEnabled(packageName)
                setOnCheckedChangeListener { _, checked -> capture.setEnabled(packageName, checked) }
            })
        }

        root.addView(section("実行タイミング通知"))
        val reminderReady = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        root.addView(status(if (reminderReady) "有効 — 開始5分前と開始時に通知" else "通知の許可が必要です", reminderReady))
        if (!reminderReady && Build.VERSION.SDK_INT >= 33) root.addView(Button(this).apply {
            text = "実行通知を許可"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
        }, matchWidth())

        root.addView(section("Google Tasks / Calendar"))
        val googleReady = EncryptedAuthStore(this).isConnected()
        root.addView(status(if (googleReady) "接続済み" else "接続が必要です", googleReady))
        if (!googleReady) root.addView(Button(this).apply {
            text = "Googleアカウントを接続"
            setOnClickListener { connectGoogle() }
        }, matchWidth())

        root.addView(section("AI支援"))
        val ai = AiPreferences(this)
        root.addView(status(if (ai.isReady()) "有効 — 匿名メタデータのみ送信" else "無効", ai.isReady()))
        root.addView(Button(this).apply {
            text = "AI支援を設定"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AiSettingsActivity::class.java)) }
        }, matchWidth())

        val scheduling = SchedulingPreferences(this)
        root.addView(section("配置する時間"))
        val schedulingSummary = TextView(this).apply { textSize = 16f; setTextColor(MUTED) }
        fun updateSummary() {
            schedulingSummary.text = "平日 ${scheduling.workdayStartHour}:00〜${scheduling.workdayEndHour}:00" +
                "  •  余白 ${scheduling.bufferMinutes}分\n1日最大 ${scheduling.dailyCapacityMinutes / 60}時間" +
                "  •  休憩 ${if (scheduling.lunchBreakEnabled) formatMinutes(scheduling.breakStartMinutes) + "〜" + formatMinutes(scheduling.breakEndMinutes) else "なし"}"
        }
        updateSummary()
        root.addView(schedulingSummary)
        root.addView(Button(this).apply {
            text = "平日の稼働時間を変更"
            setOnClickListener { scheduling.cycleWorkHours(); updateSummary() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "曜日別に設定"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, WeeklyScheduleActivity::class.java)) }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "予定間の余白を変更"
            setOnClickListener { scheduling.cycleBuffer(); updateSummary() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "1日の上限を変更"
            setOnClickListener { scheduling.cycleDailyCapacity(); updateSummary() }
        }, matchWidth())
        root.addView(CheckBox(this).apply {
            text = "土日にも配置"; isChecked = scheduling.weekendsEnabled
            setOnCheckedChangeListener { _, checked -> scheduling.weekendsEnabled = checked; updateSummary() }
        })
        root.addView(CheckBox(this).apply {
            text = "休憩時間を避ける"; isChecked = scheduling.lunchBreakEnabled
            setOnCheckedChangeListener { _, checked -> scheduling.lunchBreakEnabled = checked; updateSummary() }
        })
        root.addView(Button(this).apply {
            text = "休憩開始 ${formatMinutes(scheduling.breakStartMinutes)}"
            setOnClickListener { showBreakPicker(scheduling, true) }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "休憩終了 ${formatMinutes(scheduling.breakEndMinutes)}"
            setOnClickListener { showBreakPicker(scheduling, false) }
        }, matchWidth())

        val recovery = RecoveryPreferences(this)
        root.addView(section("予定が崩れたとき"))
        root.addView(CheckBox(this).apply {
            text = "未完了タスクを自動で再配置"; isChecked = recovery.enabled
            setOnCheckedChangeListener { _, checked ->
                recovery.enabled = checked
                if (checked) scheduleRecovery() else WorkManager.getInstance(this@SettingsActivity)
                    .cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
            }
        })
        root.addView(Button(this).apply {
            text = "再配置の上限 ${recovery.maximumAttempts}回"
            setOnClickListener { recovery.cycleMaximumAttempts(); text = "再配置の上限 ${recovery.maximumAttempts}回" }
        }, matchWidth())

        root.addView(section("詳細"))
        root.addView(Button(this).apply {
            text = "すべてのタスクを確認・修正"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, MetadataActivity::class.java).putExtra("show_all", true)) }
        }, matchWidth())
        return ScrollView(this).apply { addView(root) }
    }

    private fun connectGoogle() {
        authorization.authorize(object : GoogleTasksAuthorization.Callback {
            override fun onAuthorized(accessToken: String) = finishAuthorization(accessToken)
            override fun onResolutionRequired(sender: IntentSender) {
                try { startIntentSenderForResult(sender, GoogleTasksAuthorization.REQUEST_CODE, null, 0, 0, 0) }
                catch (error: IntentSender.SendIntentException) { showAuthError(error.localizedMessage) }
            }
            override fun onError(message: String) = showAuthError(message)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                ExecutionReminderScheduler.restoreUpcoming(this)
            }
            setContentView(content())
        }
    }

    @Deprecated("Legacy callback is required for the Google Authorization PendingIntent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GoogleTasksAuthorization.REQUEST_CODE) return
        val token = if (resultCode == RESULT_OK) authorization.resultFromIntent(data)?.accessToken else null
        if (token.isNullOrBlank()) showAuthError("Google接続を完了できませんでした") else finishAuthorization(token)
    }

    private fun finishAuthorization(token: String) {
        EncryptedAuthStore(this).saveAccessToken(token)
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CaptureSyncWorker>().build())
        setContentView(content())
        Toast.makeText(this, "Googleへ接続しました", Toast.LENGTH_SHORT).show()
    }

    private fun showBreakPicker(settings: SchedulingPreferences, editingStart: Boolean) {
        val current = if (editingStart) settings.breakStartMinutes else settings.breakEndMinutes
        TimePickerDialog(this, { _, hour, minute ->
            val saved = if (editingStart) settings.setBreakStart(hour, minute) else settings.setBreakEnd(hour, minute)
            if (saved) setContentView(content())
            else Toast.makeText(this, "開始時刻は終了時刻より前にしてください", Toast.LENGTH_LONG).show()
        }, current / 60, current % 60, true).show()
    }

    private fun scheduleRecovery() {
        val manager = WorkManager.getInstance(this)
        manager.enqueueUniquePeriodicWork(
            TaskRecoveryWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TaskRecoveryWorker>(TaskRecoveryWorker.REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
        )
        manager.enqueue(OneTimeWorkRequestBuilder<TaskRecoveryWorker>().build())
    }

    private fun status(text: String, ready: Boolean) = TextView(this).apply {
        this.text = if (ready) "● $text" else "● $text"
        textSize = 16f; setTextColor(if (ready) SUCCESS else DANGER); setPadding(0, 0, 0, 6.dp)
    }
    private fun section(label: String) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(INK); setPadding(0, 24.dp, 0, 8.dp)
    }
    private fun showAuthError(message: String?) = Toast.makeText(this, message ?: "Google認証に失敗しました", Toast.LENGTH_LONG).show()
    private fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 2001
        val INK = Color.rgb(24, 39, 34)
        val MUTED = Color.rgb(92, 101, 97)
        val SUCCESS = Color.rgb(28, 112, 76)
        val DANGER = Color.rgb(168, 62, 48)
    }
}
