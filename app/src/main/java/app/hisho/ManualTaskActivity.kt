package app.hisho

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        root.addView(TextView(this).apply { text = "タスクを追加"; textSize = 28f })
        titleInput = EditText(this).apply { hint = "例: 見積書を田中さんへ送る"; maxLines = 2 }
        root.addView(titleInput, matchWidth())
        deadlineButton = Button(this).apply {
            text = "期限なし"
            setOnClickListener { selectDeadline() }
        }
        root.addView(deadlineButton, matchWidth())
        root.addView(Button(this).apply {
            text = "期限を解除"
            setOnClickListener { deadlineEpochMillis = null; deadlineButton.text = "期限なし" }
        }, matchWidth())
        val effortButton = Button(this).apply { text = "工数 ${efforts[effortIndex]}" }
        effortButton.setOnClickListener {
            effortIndex = (effortIndex + 1) % efforts.size
            effortButton.text = "工数 ${efforts[effortIndex]}"
        }
        root.addView(effortButton, matchWidth())
        val priorityButton = Button(this).apply { text = "優先度 ${priorityLabel()}" }
        priorityButton.setOnClickListener {
            priorityIndex = (priorityIndex + 1) % priorities.size
            priorityButton.text = "優先度 ${priorityLabel()}"
        }
        root.addView(priorityButton, matchWidth())
        root.addView(Button(this).apply {
            text = "保存して同期"
            setOnClickListener { save() }
        }, matchWidth())
        setContentView(root)
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
        val title = titleInput.text.toString().trim()
        if (title.isBlank()) {
            Toast.makeText(this, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
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
