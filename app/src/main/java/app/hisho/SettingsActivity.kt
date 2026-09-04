package app.hisho

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
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
        if (intent.getBooleanExtra("onboarding", false)) return onboardingContent()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 32.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "設定"; textSize = 28f; setTextColor(INK) })
        root.addView(Button(this).apply {
            text = "初期設定ガイドを開く"
            setOnClickListener { intent.putExtra("onboarding", true); setContentView(content()) }
        }, matchWidth())
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
        val reminderReady = remindersEnabled()
        root.addView(status(if (reminderReady) "有効 — 開始5分前と開始時に通知" else "通知の許可が必要です", reminderReady))
        if (!reminderReady && Build.VERSION.SDK_INT >= 33) root.addView(Button(this).apply {
            text = "実行通知を許可"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Androidの通知設定を開く"
            setOnClickListener { openNotificationSettings() }
        }, matchWidth())
        root.addView(TextView(this).apply { text = "省電力や端末の状態により通知が遅れることがあります。「15分後」は通知だけを延期し、Calendarの枠は変更しません。" })

        root.addView(section("Google Tasks / Calendar"))
        val googleReady = EncryptedAuthStore(this).isConnected()
        root.addView(status(if (googleReady) "接続済み" else "接続が必要です", googleReady))
        root.addView(Button(this).apply {
            text = if (googleReady) "Googleへ再接続" else "Googleアカウントを接続"
            setOnClickListener { connectGoogle() }
        }, matchWidth())

        root.addView(section("AI支援"))
        root.addView(TextView(this).apply { text = PRIVACY_DESCRIPTION })
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
        root.addView(TextView(this).apply { text = RECOVERY_DESCRIPTION })
        root.addView(CheckBox(this).apply {
            text = "未完了タスクを自動で再配置"; isChecked = recovery.enabled
            setOnCheckedChangeListener { _, checked ->
                if (checked && !recovery.enabled) {
                    AlertDialog.Builder(this@SettingsActivity).setTitle("自動再配置を有効にする？")
                        .setMessage(RECOVERY_DESCRIPTION)
                        .setPositiveButton("有効にする") { _, _ -> recovery.enabled = true; scheduleRecovery() }
                        .setNegativeButton("キャンセル") { _, _ -> isChecked = false }
                        .setOnCancelListener { isChecked = false }.show()
                } else if (!checked) {
                    recovery.enabled = false
                    WorkManager.getInstance(this@SettingsActivity).cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
                }
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

    private fun remindersEnabled(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        (getSystemService(NotificationManager::class.java).getNotificationChannel("hisho_execution_reminders")?.importance
            != NotificationManager.IMPORTANCE_NONE)

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun onboardingContent(): ScrollView {
        val progress = getSharedPreferences("onboarding", MODE_PRIVATE)
        val notificationReady = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val googleReady = EncryptedAuthStore(this).isConnected()
        val step = when {
            !notificationReady -> 1
            !googleReady -> 2
            !progress.getBoolean("hours_confirmed", false) -> 3
            !remindersEnabled() -> 4
            !progress.getBoolean("recovery_confirmed", false) -> 5
            else -> 6
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 24.dp, 20.dp, 24.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        fun label(value: String) { root.addView(TextView(this).apply { text = value; textSize = 18f; setPadding(0, 12.dp, 0, 12.dp) }) }
        fun button(value: String, action: () -> Unit) { root.addView(Button(this).apply { text = value; setOnClickListener { action() } }, matchWidth()) }
        label(if (step <= 5) "初期設定 $step / 5" else "初期設定を確認できました")
        when (step) {
            1 -> {
                label("1. 通知を受け取る\nGmail・Slack・Discord・LINEの通知からタスク候補を端末内で推定します。対象アプリは設定で変更できます。")
                label("通知の内容は端末で暗号化して一時保存します。許可しなくても手動入力は使えます。Androidで制限される場合は、アプリ情報のメニューから「制限付き設定を許可」を確認してください。")
                button("通知アクセスの設定へ") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            2 -> {
                label("2. Googleへ接続\nタスク名はGoogle Tasksへ、作業時間はGoogle Calendarへ保存します。Googleの同意画面でアカウントと権限を確認してください。")
                button("Googleへ接続") { connectGoogle() }
            }
            3 -> {
                label("3. 作業できる時間を決める\n曜日別の稼働時間を確認してください。期限は締切、Calendarの枠は実際に作業する時間です。余白・休憩・1日の上限は設定から変更できます。")
                button("曜日別の稼働時間を開く") { startActivity(Intent(this, WeeklyScheduleActivity::class.java)) }
                button("稼働時間を確認しました") { progress.edit().putBoolean("hours_confirmed", true).apply(); setContentView(content()) }
            }
            4 -> {
                label("4. 実行タイミングを知らせる\n開始5分前と開始時に通知します。通知からタスク全体を完了、15分後に再通知、再配置ができます。省電力状態では遅れることがあります。")
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    button("通知を許可") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
                }
                button("Androidの通知設定へ") { openNotificationSettings() }
            }
            5 -> {
                label("5. 予定が崩れたとき")
                label(RECOVERY_DESCRIPTION)
                fun choose(enabled: Boolean) {
                    RecoveryPreferences(this).enabled = enabled
                    if (enabled) scheduleRecovery() else WorkManager.getInstance(this).cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
                    progress.edit().putBoolean("recovery_confirmed", true).apply()
                    setContentView(content())
                }
                button("自動再配置を有効にする") { choose(true) }
                button("自動再配置を使わず進む") { choose(false) }
            }
            else -> {
                label("通知 → タスク → 空き時間への配置まで自動で進みます。普段はCalendarを見て、終わったら完了してください。")
                label(PRIVACY_DESCRIPTION)
                button("ホームへ") { finish() }
            }
        }
        if (step <= 5) button("後で設定する") { finish() }
        button("通常の設定を見る") { intent.putExtra("onboarding", false); setContentView(content()) }
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
        const val PRIVACY_DESCRIPTION = "Googleには実際のタスク名・予定を保存します。任意のAI支援には匿名ID・工数・優先度・期限情報・空き時間などだけを送信し、タイトル・本文・人名・会社名・URLは送りません。AIは別画面で同意するまで有効になりません。"
        const val RECOVERY_DESCRIPTION = "未完了のまま予定時間を過ぎたタスクを、別の空き時間へ自動で再配置します。Google Calendarの作業枠が変わります。設定した回数を超えたら停止して確認を求めます。完了済みの作業はタスクも完了にしてください。いつでも無効にできます。"
        const val REQUEST_NOTIFICATIONS = 2001
        val INK = Color.rgb(24, 39, 34)
        val MUTED = Color.rgb(92, 101, 97)
        val SUCCESS = Color.rgb(28, 112, 76)
        val DANGER = Color.rgb(168, 62, 48)
    }
}
