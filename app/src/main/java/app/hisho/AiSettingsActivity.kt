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

class AiSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AiPreferences(this)
        val padding = 20.dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "AIスケジューリング"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "AIへ送るのは匿名ID、カテゴリ、工数、優先度、期限、作成日時、再計画回数だけです。タスク名、通知本文、送信者、通知元、URLは送信しません。"
            textSize = 16f
            setPadding(0, 8.dp, 0, 12.dp)
        })
        val consent = CheckBox(this).apply {
            text = "匿名メタデータをOpenAI APIへ送信することに同意する"
            isChecked = preferences.consentGranted
        }
        root.addView(consent)
        val keyInput = EditText(this).apply {
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
        root.addView(Button(this).apply {
            text = "設定を保存"
            setOnClickListener {
                preferences.consentGranted = consent.isChecked
                val key = keyInput.text.toString().trim()
                if (key.isNotEmpty()) preferences.saveApiKey(key)
                keyInput.text.clear()
                keyInput.hint = if (preferences.apiKey() == null) "OpenAI APIキー" else "保存済み（変更する場合のみ入力）"
                Toast.makeText(this@AiSettingsActivity, if (preferences.isReady()) "AI支援を有効にしました" else "AI支援は無効です", Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "APIキーを削除してAIを無効化"
            setOnClickListener {
                preferences.clearApiKey()
                preferences.consentGranted = false
                consent.isChecked = false
                keyInput.hint = "OpenAI APIキー"
                Toast.makeText(this@AiSettingsActivity, "APIキーを削除しました", Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "AIが利用できない場合は、期限・優先度・工数に基づく従来の端末内スケジューラへ自動的に戻ります。API利用料は設定したOpenAIアカウントに発生します。"
            setPadding(0, 12.dp, 0, 0)
        })
        setContentView(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
