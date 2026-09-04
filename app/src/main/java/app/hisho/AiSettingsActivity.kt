package app.hisho

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.hisho.ai.AiPreferences

class AiSettingsActivity : app.hisho.ui.GlassActivity() {
    private var statusView: TextView? = null
    private val statusListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_status") runOnUiThread {
            statusView?.text = "状態: ${AiPreferences(this).lastStatus}"
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("ai_scheduling", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(statusListener)
        statusView?.text = "状態: ${AiPreferences(this).lastStatus}"
    }

    override fun onStop() {
        getSharedPreferences("ai_scheduling", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AiPreferences(this)
        val padding = 20.dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "AIスマートフィルター"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "除外・最優先ルールに一致しない通知のタイトル・本文・通知元アプリをOpenAIへ送信し、対応が必要か判断します。本文に含まれる人名・会社名・URL等も送信対象です。送信したくない通知は先に除外ルールを設定してください。"
            textSize = 16f
            setPadding(0, 8.dp, 0, 12.dp)
        })
        val consent = CheckBox(this).apply {
            text = "通知のタイトル・本文・通知元をOpenAIへ送信して判定する"
            isChecked = preferences.contentConsent
        }
        root.addView(consent)
        root.addView(TextView(this).apply { text = "APIキー" })
        val keyInput = EditText(this).apply {
            contentDescription = "OpenAI APIキー"
            hint = if (preferences.apiKey() == null) "OpenAI APIキー" else "保存済み（変更する場合のみ入力）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        root.addView(keyInput, matchWidth())
        val status = TextView(this).apply {
            text = "状態: ${preferences.lastStatus}"
            setPadding(0, 8.dp, 0, 8.dp)
        }
        root.addView(status)
        statusView = status
        root.addView(Button(this).apply {
            text = "架空の通知でAI接続をテスト"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@AiSettingsActivity)
                    .setTitle("AI接続テスト")
                    .setMessage("保存済みの設定で架空の通知を1件送信します。少額のAPI利用料が発生する場合があります。実通知は送信せず、Google Tasksにも登録しません。設定を変更した場合は先に保存してください。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("テストする") { _, _ ->
                        isEnabled = false
                        status.text = "接続テスト中…"
                        val appContext = applicationContext
                        Thread {
                            app.hisho.ai.SmartNotificationFilter(appContext).classify("app.hisho.test", "資料確認のお願い", "あなたに依頼します。添付資料を確認し、修正点を返信してください。")
                            runOnUiThread {
                                if (!isDestroyed) {
                                    status.text = "状態: ${preferences.lastStatus}"
                                    isEnabled = true
                                }
                            }
                        }.start()
                    }.show()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "設定を保存"
            setOnClickListener {
                preferences.contentConsent = consent.isChecked
                val key = keyInput.text.toString().trim()
                if (key.isNotEmpty()) preferences.saveApiKey(key)
                keyInput.text.clear()
                keyInput.hint = if (preferences.apiKey() == null) "OpenAI APIキー" else "保存済み（変更する場合のみ入力）"
                Toast.makeText(this@AiSettingsActivity, if (preferences.filterReady()) "AIフィルターを有効にしました" else "AI未設定の通知は確認待ちになります", Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "APIキーを削除してAIを無効化"
            setOnClickListener {
                preferences.clearApiKey()
                preferences.consentGranted = false
                preferences.contentConsent = false
                consent.isChecked = false
                keyInput.hint = "OpenAI APIキー"
                Toast.makeText(this@AiSettingsActivity, "APIキーを削除しました", Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "AIが利用できない・判断が曖昧な場合は確認待ちにし、自動登録しません。確認待ちはタスク確認画面から承認・除外・再判定できます。API利用料が発生します。APIのstoreはfalseですが、送信先のデータ保持ポリシーが適用されます。"
            setPadding(0, 12.dp, 0, 0)
        })
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
