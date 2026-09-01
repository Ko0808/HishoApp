package app.hisho

import android.app.Activity
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
import androidx.work.WorkManager
import app.hisho.auth.EncryptedAuthStore
import app.hisho.auth.GoogleTasksAuthorization
import app.hisho.capture.CapturePreferences
import app.hisho.capture.HishoNotificationListener
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var googleStatus: TextView
    private lateinit var authorization: GoogleTasksAuthorization

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorization = GoogleTasksAuthorization(this)
        title = getString(R.string.app_name)
        setContentView(content())
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
        root.addView(TextView(this).apply {
            text = "通知本文はAndroid Keystoreの鍵で暗号化され、画面やログには表示しません。Google接続後、同期成功時に削除します。"
            setPadding(0, 12.dp, 0, 0)
        })
        root.addView(Button(this).apply {
            text = "タスク推定を確認・修正"
            setOnClickListener { startActivity(Intent(this@MainActivity, MetadataActivity::class.java)) }
        }, matchWidth())
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderStatus() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = if (enabled) "通知アクセス: 有効" else "通知アクセス: 未設定"
        status.setTextColor(if (enabled) Color.rgb(28, 112, 76) else Color.rgb(168, 62, 48))

        val stats = CaptureQueueDatabase(this).stats()
        metrics.text = buildString {
            append("同期待ち ${stats.pending}件  •  重複除外 ${stats.duplicates}件")
            append("\n広告除外 ${stats.ignored}件  •  失敗 ${stats.failed}件")
        }
        googleStatus.text = if (EncryptedAuthStore(this).isConnected()) {
            "接続済み — Auto Captured Tasksへ同期します"
        } else {
            "未接続"
        }
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
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
        )
        renderStatus()
        Toast.makeText(this, "Google Tasksへ接続しました", Toast.LENGTH_SHORT).show()
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
}
