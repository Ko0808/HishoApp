package app.hisho

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ManualTaskActivity : Activity() {
    private lateinit var titleInput: EditText
    private lateinit var deadlineButton: Button
    private var deadlineEpochMillis: Long? = null
    private var effortIndex = 1
    private var priorityIndex = 1
    private val efforts = listOf("XS", "S", "M", "L", "XL")
    private val priorities = listOf("LOW", "NORMAL", "HIGH")
    private var saving = false
    private var advancedOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deadlineEpochMillis = savedInstanceState?.takeIf { it.containsKey("deadline") }?.getLong("deadline")
        effortIndex = savedInstanceState?.getInt("effort", 1) ?: 1
        priorityIndex = savedInstanceState?.getInt("priority", 1) ?: 1
        advancedOpen = savedInstanceState?.getBoolean("advanced", false) ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "タスクを追加"; textSize = 28f })
        titleInput = EditText(this).apply {
            hint = "何をする？"; setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            setText(savedInstanceState?.getString("draft") ?: if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(500)?.replace('\n', ' ') else "")
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    save(); true
                } else false
            }
        }
        root.addView(titleInput, matchWidth())
        root.addView(TextView(this).apply { text = "タイトルだけで保存できます。初期値は25分・優先度通常・期限なし。共有や音声の内容は確認してから保存してください。" })
        root.addView(Button(this).apply { text = "保存して同期"; setOnClickListener { save() } }, matchWidth())
        root.addView(Button(this).apply { text = "音声で入力"; setOnClickListener { voiceInput() } }, matchWidth())
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (advancedOpen) View.VISIBLE else View.GONE }
        root.addView(Button(this).apply {
            text = if (advancedOpen) "詳細設定を閉じる" else "期限・工数・優先度（任意）"
            setOnClickListener {
                advancedOpen = !advancedOpen
                advanced.visibility = if (advancedOpen) View.VISIBLE else View.GONE
                text = if (advancedOpen) "詳細設定を閉じる" else "期限・工数・優先度（任意）"
            }
        }, matchWidth())
        root.addView(advanced, matchWidth())
        deadlineButton = Button(this).apply {
            text = deadlineEpochMillis?.let { "期限 ${FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" } ?: "期限なし"
            setOnClickListener { selectDeadline() }
        }
        advanced.addView(deadlineButton, matchWidth())
        advanced.addView(Button(this).apply {
            text = "期限を解除"
            setOnClickListener { deadlineEpochMillis = null; deadlineButton.text = "期限なし" }
        }, matchWidth())
        val effortButton = Button(this).apply { text = "工数 ${efforts[effortIndex]}" }
        effortButton.setOnClickListener {
            effortIndex = (effortIndex + 1) % efforts.size
            effortButton.text = "工数 ${efforts[effortIndex]}"
        }
        advanced.addView(effortButton, matchWidth())
        val priorityButton = Button(this).apply { text = "優先度 ${priorityLabel()}" }
        priorityButton.setOnClickListener {
            priorityIndex = (priorityIndex + 1) % priorities.size
            priorityButton.text = "優先度 ${priorityLabel()}"
        }
        advanced.addView(priorityButton, matchWidth())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", titleInput.text.toString())
        deadlineEpochMillis?.let { outState.putLong("deadline", it) }
        outState.putInt("effort", effortIndex)
        outState.putInt("priority", priorityIndex)
        outState.putBoolean("advanced", advancedOpen)
        super.onSaveInstanceState(outState)
    }

    private fun voiceInput() {
        AlertDialog.Builder(this).setTitle("端末の音声入力を使用")
            .setMessage("音声認識サービスによっては音声が外部へ送信されます。オフライン優先を要求しますが保証できません。機密情報はキーボードで入力してください。認識結果は自動保存されません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("音声入力を開始") { _, _ ->
                try {
                    startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "追加するタスクを話してください")
                    }, 3001)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "音声入力に対応していません。キーボードで入力してください", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    @Deprecated("System speech recognition activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                titleInput.append(if (titleInput.text.isEmpty()) it else " $it")
            }
        }
    }

    private fun selectDeadline() {
        val initial = deadlineEpochMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
            ?: ZonedDateTime.now().plusDays(1).withHour(17).withMinute(0)
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                deadlineEpochMillis = ZonedDateTime.of(
                    year, month + 1, day, hour, minute, 0, 0, ZoneId.systemDefault(),
                ).toInstant().toEpochMilli()
                deadlineButton.text = "期限 ${FORMAT.format(Instant.ofEpochMilli(deadlineEpochMillis!!).atZone(ZoneId.systemDefault()))}"
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun save() {
        if (saving) return
        val title = titleInput.text.toString().trim()
        if (title.isBlank()) {
            Toast.makeText(this, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        CaptureQueueDatabase(this).enqueueManual(title, deadlineEpochMillis, efforts[effortIndex], priorities[priorityIndex])
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
        )
        Toast.makeText(this, "タスクを保存しました", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun priorityLabel() = when (priorities[priorityIndex]) { "LOW" -> "低"; "HIGH" -> "高"; else -> "通常" }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object { val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm") }
}
