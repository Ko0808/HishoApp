package app.hisho

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hisho.data.CaptureQueueDatabase
import app.hisho.sync.CaptureSyncWorker

class MetadataActivity : Activity() {
    private lateinit var root: LinearLayout
    private val database by lazy { CaptureQueueDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "タスク推定の修正"
            textSize = 28f
            setTextColor(Color.rgb(24, 39, 34))
        })
        root.addView(TextView(this).apply {
            text = "「未同期」の項目だけ、名前・工数・除外設定を変更できます。変更後は下の「今すぐ同期」を押してください。"
            setPadding(0, 8.dp, 0, 16.dp)
        })

        root.addView(Button(this).apply {
            text = "今すぐ同期"
            setOnClickListener { enqueueSync() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 16.dp })

        val items = database.recentMetadata()
        if (items.isEmpty()) {
            root.addView(TextView(this).apply { text = "解析済み通知はありません" })
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                setBackgroundColor(Color.WHITE)
            }
            card.addView(TextView(this).apply {
                text = "${sourceLabel(item.sourcePackage)}  •  ${stateLabel(item.state)}"
                textSize = 17f
                setTextColor(stateColor(item.state))
            })
            card.addView(TextView(this).apply {
                text = "${item.category} / 優先度 ${priorityLabel(item.priority)}" +
                    "\n期限: ${formatDeadline(item.deadlineEpochMillis)}\n判定: ${item.reason}"
            })
            card.addView(TextView(this).apply {
                text = item.actionTitle.ifBlank { "（旧データ：タイトル未保存）" }
                textSize = 18f
                setPadding(0, 8.dp, 0, 4.dp)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editable = item.state !in setOf("SYNCED", "FAILED", "COMPLETED")
            actions.addView(Button(this).apply {
                text = "名前を編集"
                isEnabled = editable
                setOnClickListener { showTitleEditor(item.id, item.actionTitle) }
            })
            actions.addView(Button(this).apply {
                text = "工数 ${item.effort}"
                isEnabled = editable
                setOnClickListener {
                    database.cycleEffort(item.id)
                    render()
                }
            })
            actions.addView(Button(this).apply {
                text = if (item.state == "IGNORED") "タスク化" else "除外"
                isEnabled = item.state !in setOf("SYNCED", "FAILED")
                setOnClickListener {
                    database.toggleCandidate(item.id)
                    enqueueSync()
                    render()
                }
            })
            card.addView(actions)
            val metadataActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            metadataActions.addView(Button(this).apply {
                text = "優先度 ${priorityLabel(item.priority)}"
                isEnabled = editable
                setOnClickListener {
                    database.cyclePriority(item.id)
                    render()
                }
            })
            metadataActions.addView(Button(this).apply {
                text = "期限を設定"
                isEnabled = editable
                setOnClickListener { showDeadlineEditor(item.id, item.deadlineEpochMillis) }
            })
            metadataActions.addView(Button(this).apply {
                text = "期限なし"
                isEnabled = editable && item.deadlineEpochMillis != null
                setOnClickListener {
                    database.updateDeadline(item.id, null)
                    render()
                }
            })
            card.addView(metadataActions)
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12.dp })
        }
    }

    private fun showTitleEditor(id: Long, currentTitle: String) {
        val input = EditText(this).apply {
            setText(currentTitle)
            hint = "例: 会議資料を田中さんへ送る"
            if (text.isNotEmpty()) setSelection(text.length)
            maxLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("タスク名を編集")
            .setView(input)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                database.updateActionTitle(id, input.text.toString())
                enqueueSync()
                render()
            }
            .show()
    }

    private fun showDeadlineEditor(id: Long, currentDeadline: Long?) {
        val initial = currentDeadline?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        } ?: ZonedDateTime.now().plusDays(1).withHour(17).withMinute(0)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val deadline = ZonedDateTime.of(
                            year, month + 1, day, hour, minute, 0, 0, ZoneId.systemDefault(),
                        ).toInstant().toEpochMilli()
                        database.updateDeadline(id, deadline)
                        render()
                    },
                    initial.hour,
                    initial.minute,
                    true,
                ).show()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    private fun formatDeadline(epochMillis: Long?): String = epochMillis?.let {
        DEADLINE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "なし"

    private fun priorityLabel(priority: String): String = when (priority) {
        "HIGH" -> "高"
        "LOW" -> "低"
        else -> "通常"
    }

    private fun enqueueSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
        )
        Toast.makeText(this, "同期を開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun sourceLabel(packageName: String): String = when (packageName) {
        "com.google.android.gm" -> "Gmail"
        "com.Slack" -> "Slack"
        "com.discord" -> "Discord"
        "jp.naver.line.android" -> "LINE"
        else -> packageName
    }

    private fun stateLabel(state: String): String = when (state) {
        "PENDING" -> "未同期"
        "RETRY" -> "再試行待ち"
        "SYNCED" -> "同期済み"
        "COMPLETED" -> "完了"
        "IGNORED" -> "除外"
        "FAILED" -> "同期失敗"
        else -> state
    }

    private fun stateColor(state: String): Int = when (state) {
        "PENDING", "RETRY" -> Color.rgb(181, 91, 28)
        "SYNCED" -> Color.rgb(28, 112, 76)
        "COMPLETED" -> Color.rgb(58, 91, 160)
        "FAILED" -> Color.rgb(168, 62, 48)
        else -> Color.rgb(80, 86, 82)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val DEADLINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    }
}
