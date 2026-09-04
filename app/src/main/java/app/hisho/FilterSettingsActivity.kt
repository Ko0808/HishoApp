package app.hisho

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import app.hisho.capture.CapturePreferences
import app.hisho.intelligence.FilterPreferences

class FilterSettingsActivity : app.hisho.ui.GlassActivity() {
    private var inputs: List<Pair<String, EditText>> = emptyList()
    private val sources = mutableMapOf<String, Int>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = FilterPreferences(this)
        val rules = preferences.rules()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        root.addView(TextView(this).apply { text = "通知の登録ルール"; textSize = 26f })
        root.addView(TextView(this).apply { text = "除外 → 最優先登録 → AIの順です。競合時は除外します。ワードはタイトル・本文の部分一致（英字大小・全角半角を区別しない）。各行に1ワードを入力してください。保存後の判定から適用し、登録済みタスクは削除しません。" })
        inputs = listOf("excluded_words" to "除外ワード", "forced_words" to "最優先に登録するワード").map { (key, label) ->
            root.addView(TextView(this).apply { text = label; textSize = 19f })
            key to EditText(this).apply { minLines = 2; maxLines = 4; setText(savedInstanceState?.getString(key) ?: preferences.value(key)); root.addView(this) }
        }
        root.addView(TextView(this).apply { text = "通知元アプリの扱い"; textSize = 19f })
        val labels = arrayOf("AI判定", "除外", "最優先登録")
        CapturePreferences.SUPPORTED_PACKAGES.forEach { (source, name) ->
            sources[source] = savedInstanceState?.getInt(source) ?: when (source) { in rules.excludedSources -> 1; in rules.forcedSources -> 2; else -> 0 }
            root.addView(Button(this).apply {
                fun refresh() { text = "$name: ${labels[sources.getValue(source)]}" }
                refresh()
                setOnClickListener {
                    AlertDialog.Builder(this@FilterSettingsActivity).setTitle(name)
                        .setSingleChoiceItems(labels, sources.getValue(source)) { dialog, choice -> sources[source] = choice; refresh(); dialog.dismiss() }
                        .setNegativeButton("キャンセル", null).show()
                }
            })
        }
        root.addView(TextView(this).apply { text = "通知取得自体がOFFのアプリは対象外です。除外・最優先の通知はAIへ送信しません。AI未設定・失敗時は確認待ちになります。" })
        root.addView(Button(this).apply { text = "ルールを保存"; setOnClickListener {
            preferences.saveAll(inputs.associate { (key, field) -> key to field.text.toString() } + mapOf(
                "excluded_sources" to sources.filterValues { it == 1 }.keys.joinToString("\n"),
                "forced_sources" to sources.filterValues { it == 2 }.keys.joinToString("\n")))
            Toast.makeText(this@FilterSettingsActivity, "保存しました。確認待ちは再判定できます", Toast.LENGTH_LONG).show(); finish()
        } })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onSaveInstanceState(outState: Bundle) {
        inputs.forEach { (key, input) -> outState.putString(key, input.text.toString()) }
        sources.forEach { (key, value) -> outState.putInt(key, value) }
        super.onSaveInstanceState(outState)
    }
}
