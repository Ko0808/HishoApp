package app.hisho

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksAuthorization
import app.hisho.capture.CapturePreferences
import app.hisho.capture.HishoNotificationListener
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker
import app.hisho.sync.TaskRecoveryWorker
import app.hisho.sync.SyncStatusStore
import java.util.concurrent.TimeUnit
import app.hisho.scheduling.SchedulingPreferences
import app.hisho.scheduling.RecoveryPreferences
import app.hisho.scheduling.ScheduleHealth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var googleStatus: TextView
    private lateinit var authorization: GoogleTasksAuthorization
    private lateinit var schedulingSummary: TextView
    private lateinit var todaySummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorization = GoogleTasksAuthorization(this)
        title = getString(R.string.app_name)
        setContentView(content())
        schedulePeriodicSync()
        if (RecoveryPreferences(this).enabled) scheduleRecoveryChecks()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun content(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply {
            text = "Hisho"
            textSize = 32f
            setTextColor(Color.rgb(24, 39, 34))
        })
        root.addView(TextView(this).apply {
            text = "通知から、忘れない仕組みへ。"
            textSize = 16f
            setPadding(0, 4.dp, 0, 24.dp)
        })

        root.addView(section("今日の予定"))
        todaySummary = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 4.dp)
        }
        root.addView(todaySummary)

        status = TextView(this).apply { textSize = 18f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "通知へのアクセスを設定"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }, matchWidth())

        root.addView(section("捕捉するアプリ"))
        val preferences = CapturePreferences(this)
        CapturePreferences.SUPPORTED_PACKAGES.forEach { (packageName, label) ->
            root.addView(CheckBox(this).apply {
                text = label
                isChecked = preferences.isEnabled(packageName)
                setOnCheckedChangeListener { _, checked -> preferences.setEnabled(packageName, checked) }
            })
        }

        root.addView(section("Google Tasks"))
        googleStatus = TextView(this).apply { textSize = 16f }
        root.addView(googleStatus)
        root.addView(Button(this).apply {
            text = "Googleアカウントを接続"
            setOnClickListener { connectGoogleTasks() }
        }, matchWidth())

        root.addView(section("端末内キュー"))
        metrics = TextView(this).apply { textSize = 16f }
        root.addView(metrics)
        root.addView(Button(this).apply {
            text = "タスクを手動で追加"
            setOnClickListener { startActivity(Intent(this@MainActivity, ManualTaskActivity::class.java)) }
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "通知本文はAndroid Keystoreの鍵で暗号化され、画面やログには表示しません。Google接続後、同期成功時に削除します。"
            setPadding(0, 12.dp, 0, 0)
        })
        root.addView(Button(this).apply {
            text = "今すぐ同期"
            setOnClickListener {
                enqueueSync()
                Toast.makeText(this@MainActivity, "同期を開始しました", Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "タスク推定を確認・修正"
            setOnClickListener { startActivity(Intent(this@MainActivity, MetadataActivity::class.java)) }
        }, matchWidth())

        root.addView(section("自動スケジュール"))
        val scheduling = SchedulingPreferences(this)
        schedulingSummary = TextView(this).apply { textSize = 16f }
        root.addView(schedulingSummary)
        root.addView(Button(this).apply {
            text = "平日の稼働時間を一括変更"
            setOnClickListener {
                scheduling.cycleWorkHours()
                renderSchedulingSettings(scheduling)
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "曜日別の稼働時間を設定"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, WeeklyScheduleActivity::class.java))
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "予定間の余白を変更"
            setOnClickListener {
                scheduling.cycleBuffer()
                renderSchedulingSettings(scheduling)
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "1日の上限を変更"
            setOnClickListener {
                scheduling.cycleDailyCapacity()
                renderSchedulingSettings(scheduling)
            }
        }, matchWidth())
        root.addView(CheckBox(this).apply {
            text = "土日にもタスクを配置"
            isChecked = scheduling.weekendsEnabled
            setOnCheckedChangeListener { _, checked ->
                scheduling.weekendsEnabled = checked
                renderSchedulingSettings(scheduling)
            }
        })
        root.addView(CheckBox(this).apply {
            text = "休憩時間を避ける"
            isChecked = scheduling.lunchBreakEnabled
            setOnCheckedChangeListener { _, checked ->
                scheduling.lunchBreakEnabled = checked
                renderSchedulingSettings(scheduling)
            }
        })
        val breakStartButton = Button(this).apply {
            text = "休憩開始 ${formatMinutes(scheduling.breakStartMinutes)}"
            setOnClickListener {
                showBreakTimePicker(scheduling, true) {
                    text = "休憩開始 ${formatMinutes(scheduling.breakStartMinutes)}"
                }
            }
        }
        val breakEndButton = Button(this).apply {
            text = "休憩終了 ${formatMinutes(scheduling.breakEndMinutes)}"
            setOnClickListener {
                showBreakTimePicker(scheduling, false) {
                    text = "休憩終了 ${formatMinutes(scheduling.breakEndMinutes)}"
                }
            }
        }
        root.addView(breakStartButton, matchWidth())
        root.addView(breakEndButton, matchWidth())
        val recoveryPreferences = RecoveryPreferences(this)
        val recoveryLimitButton = Button(this).apply {
            text = "再計画の上限 ${recoveryPreferences.maximumAttempts}回"
        }
        recoveryLimitButton.setOnClickListener {
            recoveryPreferences.cycleMaximumAttempts()
            recoveryLimitButton.text = "再計画の上限 ${recoveryPreferences.maximumAttempts}回"
        }
        root.addView(recoveryLimitButton, matchWidth())
        root.addView(CheckBox(this).apply {
            text = "予定終了後も未完了なら自動で再配置"
            isChecked = recoveryPreferences.enabled
            setOnCheckedChangeListener { _, checked ->
                recoveryPreferences.enabled = checked
                if (checked) {
                    scheduleRecoveryChecks()
                    Toast.makeText(this@MainActivity, "未完了タスクの確認を開始しました", Toast.LENGTH_SHORT).show()
                } else {
                    WorkManager.getInstance(this@MainActivity)
                        .cancelUniqueWork(TaskRecoveryWorker.UNIQUE_WORK_NAME)
                }
            }
        })
        renderSchedulingSettings(scheduling)
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderSchedulingSettings(settings: SchedulingPreferences) {
        schedulingSummary.text =
            "稼働 ${settings.workdayStartHour}:00〜${settings.workdayEndHour}:00" +
                "  •  余白 ${settings.bufferMinutes}分" +
                "\n1日の予定上限 ${settings.dailyCapacityMinutes / 60}時間" +
                "  •  土日 ${if (settings.weekendsEnabled) "使用" else "休み"}" +
                "\n休憩 ${if (settings.lunchBreakEnabled) {
                    "${formatMinutes(settings.breakStartMinutes)}〜${formatMinutes(settings.breakEndMinutes)}"
                } else "なし"}"
    }

    private fun showBreakTimePicker(
        settings: SchedulingPreferences,
        editingStart: Boolean,
        onSaved: () -> Unit,
    ) {
        val current = if (editingStart) settings.breakStartMinutes else settings.breakEndMinutes
        TimePickerDialog(this, { _, hour, minute ->
            val saved = if (editingStart) settings.setBreakStart(hour, minute)
            else settings.setBreakEnd(hour, minute)
            if (saved) {
                onSaved()
                renderSchedulingSettings(settings)
            } else {
                Toast.makeText(this, "開始時刻は終了時刻より前に設定してください", Toast.LENGTH_LONG).show()
            }
        }, current / 60, current % 60, true).show()
    }

    private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun renderStatus() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = if (enabled) "通知アクセス: 有効" else "通知アクセス: 未設定"
        status.setTextColor(if (enabled) Color.rgb(28, 112, 76) else Color.rgb(168, 62, 48))

        val stats = CaptureQueueDatabase(this).stats()
        metrics.text = buildString {
            append("同期待ち ${stats.pending}件  •  重複除外 ${stats.duplicates}件")
            append("\n時間配置 ${stats.scheduled}件  •  広告除外 ${stats.ignored}件  •  失敗 ${stats.failed}件")
            if (stats.needsAttention > 0) append("\n要確認 ${stats.needsAttention}件")
        }
        googleStatus.text = if (EncryptedAuthStore(this).isConnected()) {
            "接続済み — Auto Captured Tasksへ同期します\n${syncStatusText()}"
        } else {
            "未接続"
        }
        renderToday()
    }

    private fun renderToday() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val tasks = CaptureQueueDatabase(this).dashboardTasks()
            .filter { it.state == "SYNCED" }
        val todayTasks = tasks.filter { task ->
            task.scheduledStartEpochMillis?.let {
                Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today
            } == true
        }
        val next = tasks
            .filter { (it.scheduledStartEpochMillis ?: Long.MIN_VALUE) >= now }
            .minByOrNull { it.scheduledStartEpochMillis ?: Long.MAX_VALUE }
        val atRisk = tasks.count {
            ScheduleHealth.isAtRisk(now, it.deadlineEpochMillis, it.scheduledEndEpochMillis)
        }
        todaySummary.text = buildString {
            if (next == null) append("次の予定はありません")
            else append("次: ${formatTime(next.scheduledStartEpochMillis)}  ${next.actionTitle}")
            append("\n今日 ${todayTasks.size}件  •  締切注意 ${atRisk}件")
            todayTasks.take(3).forEach { task ->
                append("\n${formatTime(task.scheduledStartEpochMillis)}  ${task.actionTitle}")
                if (task.recoveryCount > 0) append("  再計画${task.recoveryCount}回")
            }
            if (todayTasks.size > 3) append("\nほか${todayTasks.size - 3}件")
        }
        todaySummary.setTextColor(if (atRisk > 0) Color.rgb(168, 62, 48) else Color.rgb(24, 39, 34))
    }

    private fun formatTime(epochMillis: Long?): String {
        if (epochMillis == null) return "未配置"
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }

    private fun connectGoogleTasks() {
        authorization.authorize(object : GoogleTasksAuthorization.Callback {
            override fun onAuthorized(accessToken: String) = finishAuthorization(accessToken)

            override fun onResolutionRequired(sender: IntentSender) {
                try {
                    startIntentSenderForResult(
                        sender,
                        GoogleTasksAuthorization.REQUEST_CODE,
                        null,
                        0,
                        0,
                        0,
                    )
                } catch (error: IntentSender.SendIntentException) {
                    showAuthError(error.localizedMessage)
                }
            }

            override fun onError(message: String) = showAuthError(message)
        })
    }

    @Deprecated("Legacy callback is required for the Google Authorization PendingIntent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GoogleTasksAuthorization.REQUEST_CODE) return
        if (resultCode != RESULT_OK) {
            showAuthError("Google Tasksへの接続がキャンセルされました")
            return
        }
        val result = authorization.resultFromIntent(data)
        val token = result?.accessToken
        if (token.isNullOrBlank()) showAuthError("認証結果を取得できませんでした")
        else finishAuthorization(token)
    }

    private fun finishAuthorization(accessToken: String) {
        EncryptedAuthStore(this).saveAccessToken(accessToken)
        enqueueSync()
        renderStatus()
        Toast.makeText(this, "Google Tasksへ接続しました", Toast.LENGTH_SHORT).show()
    }

    private fun enqueueSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>()
                .setConstraints(networkConstraints())
                .build(),
        )
    }

    private fun schedulePeriodicSync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CaptureSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build(),
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun syncStatusText(): String {
        val snapshot = SyncStatusStore(this).snapshot()
        val label = when (snapshot.state) {
            "RUNNING" -> "同期中"
            "SUCCESS" -> "同期成功"
            "WAITING" -> "再試行待ち"
            "AUTH_REQUIRED" -> "再接続が必要"
            "NETWORK_ERROR" -> "通信エラー"
            "API_ERROR" -> "Google APIエラー"
            else -> "まだ同期していません"
        }
        if (snapshot.updatedAt == 0L) return label
        val time = formatTime(snapshot.updatedAt)
        return buildString {
            append("最終状態: $label ($time)")
            snapshot.detail?.let { append("\n$it") }
        }
    }

    private fun scheduleRecoveryChecks() {
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            TaskRecoveryWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TaskRecoveryWorker>(
                TaskRecoveryWorker.REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build(),
        )
        workManager.enqueue(
            OneTimeWorkRequestBuilder<TaskRecoveryWorker>().build(),
        )
    }

    private fun showAuthError(message: String?) {
        Toast.makeText(this, message ?: "Google認証に失敗しました", Toast.LENGTH_LONG).show()
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.rgb(24, 39, 34))
        setPadding(0, 28.dp, 0, 8.dp)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val PERIODIC_SYNC_WORK_NAME = "periodic-capture-sync"
    }
}
