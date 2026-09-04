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

class SettingsActivity : app.hisho.ui.GlassActivity() {
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 20.dp, 20.dp, 32.dp) }
        fun note(value: String) { root.addView(TextView(this).apply { text = value; textSize = 17f }) }
        fun button(value: String, action: () -> Unit) { root.addView(Button(this).apply { text = value; setOnClickListener { action() } }, matchWidth()) }
        root.addView(app.hisho.ui.HishoDesign.text(this, "設定", 30f, true))
        root.addView(section("通知の整理"))
        note("除外ルールを優先し、残りをAIが判定します。")
        button("除外・最優先ルールを設定") { startActivity(Intent(this, FilterSettingsActivity::class.java)) }
        button("AIスマートフィルターを設定") { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        root.addView(section("通知の取得"))
        val ready = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        note(if (ready) "通知アクセス: 許可済み" else "通知アクセス: 未許可")
        button("通知アクセスの設定") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        val capture = CapturePreferences(this)
        CapturePreferences.SUPPORTED_PACKAGES.forEach { (source, label) ->
            root.addView(CheckBox(this).apply { text = label; isChecked = capture.isEnabled(source)
                setOnCheckedChangeListener { _, enabled -> capture.setEnabled(source, enabled) } })
        }
        root.addView(section("Google Tasks"))
        note(if (EncryptedAuthStore(this).isConnected()) "Google接続済み" else "Google未接続")
        button("Googleへ接続・再接続") { connectGoogle() }
        note("Google Tasksに登録し、Google側での完了も同期します。APIで送れる期限は日付のみです。時刻付きのCalendar予定は新規作成しません。")
        button("すべてのタスクと除外履歴") { startActivity(Intent(this, MetadataActivity::class.java).putExtra("show_all", true)) }
        root.addView(section("表示と操作"))
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        root.addView(CheckBox(this).apply {
            text = "動きを減らす"
            isChecked = appearance.getBoolean("reduce_motion", false)
            setOnCheckedChangeListener { _, checked ->
                appearance.edit().putBoolean("reduce_motion", checked).apply()
                recreate()
            }
        })
        note("押下・表示アニメーションを抑えます。端末のアニメーション無効設定にも従います。")
        root.addView(section("以前作ったCalendar予定の整理"))
        note("タスクは残して、端末がイベントIDを追跡しているHisho予定だけを削除できます。追跡されていない古い予定はCalendarで個別に確認してください。")
        button("整理する予定を選ぶ") { chooseLegacyCleanup() }
        return ScrollView(this).apply { addView(root) }
    }

    private fun chooseLegacyCleanup() {
        val database = app.hisho.data.CaptureQueueDatabase(this)
        val targets = database.legacyTargets()
        if (targets.isEmpty()) { Toast.makeText(this, "追跡中の予定はありません", Toast.LENGTH_SHORT).show(); return }
        val selected = BooleanArray(targets.size)
        AlertDialog.Builder(this).setTitle("予定だけを削除する対象")
            .setMultiChoiceItems(targets.map { "${it.second}（${database.legacyEventIds(it.first).size}枠）" }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("選択内容を確認") { _, _ ->
                val chosen = targets.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) return@setPositiveButton
                AlertDialog.Builder(this).setTitle("${chosen.size}件のCalendar予定を削除しますか？")
                    .setMessage("Google Tasksは残ります。削除した予定はHishoから元に戻せません。\n" + chosen.joinToString("\n") { it.second })
                    .setNegativeButton("キャンセル", null).setPositiveButton("予定の削除を予約") { _, _ ->
                        val prefs = getSharedPreferences("legacy_cleanup", MODE_PRIVATE)
                        prefs.edit().putStringSet("requested_ids", prefs.getStringSet("requested_ids", emptySet()).orEmpty() + chosen.map { it.first.toString() }).commit()
                        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CaptureSyncWorker>().build())
                        Toast.makeText(this, "次の同期で選択した予定だけを削除します", Toast.LENGTH_LONG).show()
                    }.show()
            }.show()
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

    private fun section(label: String) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(INK); setPadding(0, 24.dp, 0, 8.dp)
    }
    private fun showAuthError(message: String?) = Toast.makeText(this, message ?: "Google認証に失敗しました", Toast.LENGTH_LONG).show()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val INK = Color.rgb(24, 39, 34)
        val MUTED = Color.rgb(92, 101, 97)
        val SUCCESS = Color.rgb(28, 112, 76)
        val DANGER = Color.rgb(168, 62, 48)
    }
}
