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
import android.widget.CheckBox
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
    private var selectedFilter = TaskFilter.INBOX
    private var searchQuery = ""
    private val selectedTaskIds = mutableSetOf<Long>()
    private lateinit var bulkCompleteButton: Button
    private lateinit var bulkDeleteButton: Button
    private lateinit var clearSelectionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("show_all", false)) selectedFilter = TaskFilter.ALL
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
            text = "タスクの確認"
            textSize = 28f
            setTextColor(Color.rgb(24, 39, 34))
        })
        root.addView(TextView(this).apply {
            text = "対応が必要なタスクをまとめました。通常の予定はCalendarで確認できます。編集は「詳細・操作」から行えます。"
            setPadding(0, 8.dp, 0, 16.dp)
        })

        root.addView(Button(this).apply {
            text = "今すぐ同期"
            setOnClickListener { enqueueSync() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 16.dp })

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(Button(this).apply {
            text = "絞り込み: ${selectedFilter.label}"
            setOnClickListener {
                AlertDialog.Builder(this@MetadataActivity)
                    .setTitle("表示するタスク")
                    .setSingleChoiceItems(TaskFilter.entries.map { it.label }.toTypedArray(), selectedFilter.ordinal) { dialog, index ->
                        selectedFilter = TaskFilter.entries[index]
                        selectedTaskIds.clear()
                        dialog.dismiss()
                        render()
                    }.show()
            }
        })
        filterRow.addView(Button(this).apply {
            text = if (searchQuery.isBlank()) "検索" else "検索: $searchQuery"
            setOnClickListener { showSearchDialog() }
        })
        if (searchQuery.isNotBlank()) {
            filterRow.addView(Button(this).apply {
                text = "解除"
                setOnClickListener {
                    searchQuery = ""
                    selectedTaskIds.clear()
                    render()
                }
            })
        }
        root.addView(filterRow)

        val bulkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bulkCompleteButton = Button(this).apply {
            setOnClickListener { confirmBulkCompletion() }
        }
        bulkDeleteButton = Button(this).apply {
            setTextColor(Color.rgb(168, 62, 48))
            setOnClickListener { confirmBulkDeletion() }
        }
        clearSelectionButton = Button(this).apply {
            text = "選択解除"
            setOnClickListener {
                selectedTaskIds.clear()
                render()
            }
        }
        bulkRow.addView(bulkCompleteButton)
        bulkRow.addView(bulkDeleteButton)
        bulkRow.addView(clearSelectionButton)
        root.addView(bulkRow)
        updateBulkButtons()

        val items = database.recentMetadata(
            states = selectedFilter.states,
            searchQuery = searchQuery,
            attentionOnly = selectedFilter == TaskFilter.INBOX,
            riskOnly = selectedFilter == TaskFilter.RISK,
        )
        if (items.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (selectedFilter == TaskFilter.ALL && searchQuery.isBlank()) {
                    "解析済み通知はありません"
                } else if (selectedFilter == TaskFilter.INBOX && searchQuery.isBlank()) "対応が必要なタスクはありません。Hishoに任せて大丈夫です。"
                else "条件に一致するタスクはありません"
                setPadding(0, 16.dp, 0, 0)
            })
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                setBackgroundColor(Color.WHITE)
            }
            card.addView(CheckBox(this).apply {
                text = "選択"
                isChecked = item.id in selectedTaskIds
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedTaskIds += item.id else selectedTaskIds -= item.id
                    updateBulkButtons()
                }
            })
            card.addView(TextView(this).apply {
                val risk = item.state == "SYNCED" && selectedFilter in setOf(TaskFilter.INBOX, TaskFilter.RISK)
                text = "${sourceLabel(item.sourcePackage)}  •  ${if (risk) "期限注意" else stateLabel(item.state)}"
                textSize = 17f
                setTextColor(if (risk) Color.rgb(168, 62, 48) else stateColor(item.state))
            })
            card.addView(TextView(this).apply {
                text = item.actionTitle.ifBlank { "タイトル未保存" }
                textSize = 20f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 8.dp, 0, 4.dp)
            })
            card.addView(TextView(this).apply {
                text = "期限: ${formatDeadline(item.deadlineEpochMillis)}" + errorDescription(item.lastErrorCode)
            })
            card.addView(Button(this).apply {
                text = "詳細・操作"
                setOnClickListener { showTaskActions(item) }
            })
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12.dp })
        }
    }

    private fun showTaskActions(item: CaptureQueueDatabase.MetadataItem) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun option(label: String, action: () -> Unit) { labels += label; actions += action }
        option("配置時刻と詳細を見る") { showTaskDetail(item.id) }
        if (item.state == "NEEDS_ATTENTION") {
            option("自動再配置を再開") { database.restartRecovery(item.id); enqueueSync(); render() }
        }
        if (item.state in setOf("SYNCED", "NEEDS_ATTENTION")) {
            option("完了にする") { database.requestCompletion(item.id); enqueueSync(); render() }
        }
        if (item.state == "SYNCED") {
            option("空き時間へ再配置") { database.requestReschedule(item.id); enqueueSync(); render() }
        }
        if (item.state in setOf("PENDING", "RETRY")) option("同期を再実行") { enqueueSync() }
        if (item.state in setOf("PENDING", "RETRY", "SYNCED", "IGNORED")) {
            option("名前を編集") { showTitleEditor(item.id, item.actionTitle) }
            option("工数を変更（現在 ${item.effort}）") { database.cycleEffort(item.id); enqueueSync(); render() }
            option("優先度を変更（現在 ${priorityLabel(item.priority)}）") { database.cyclePriority(item.id); enqueueSync(); render() }
            option("期限を変更") { showDeadlineEditor(item.id, item.deadlineEpochMillis) }
            option("期限を解除") { database.updateDeadline(item.id, null); enqueueSync(); render() }
        }
        if (item.state in setOf("PENDING", "RETRY", "IGNORED")) {
            option(if (item.state == "IGNORED") "タスクとして復帰" else "不要なタスクとして除外") {
                database.toggleCandidate(item.id); enqueueSync(); render()
            }
        }
        if (item.state !in setOf("DELETE_REQUESTED", "DELETED")) {
            option("削除…") { confirmDeletion(item.id, item.actionTitle) }
        }
        AlertDialog.Builder(this).setTitle(item.actionTitle)
            .setItems(labels.toTypedArray()) { _, index -> actions[index]() }
            .setNegativeButton("閉じる", null).show()
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

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            setText(searchQuery)
            hint = "タスク名または通知元"
            if (text.isNotEmpty()) setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("タスクを検索")
            .setView(input)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("検索") { _, _ ->
                searchQuery = input.text.toString().trim()
                selectedTaskIds.clear()
                render()
            }
            .show()
    }

    private fun confirmDeletion(id: Long, title: String) {
        AlertDialog.Builder(this)
            .setTitle("タスクを削除しますか？")
            .setMessage(
                if (title.isBlank()) "このタスクを削除します。同期済みの場合はGoogle TasksとCalendarからも削除されます。"
                else "「$title」を削除します。同期済みの場合はGoogle TasksとCalendarからも削除されます。",
            )
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                database.requestDeletion(id)
                enqueueSync()
                render()
            }
            .show()
    }

    private fun updateBulkButtons() {
        val count = selectedTaskIds.size
        bulkCompleteButton.text = "完了 ($count)"
        bulkDeleteButton.text = "削除 ($count)"
        bulkCompleteButton.isEnabled = count > 0
        bulkDeleteButton.isEnabled = count > 0
        clearSelectionButton.isEnabled = count > 0
    }

    private fun confirmBulkCompletion() {
        val ids = selectedTaskIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("${ids.size}件を完了にしますか？")
            .setMessage("Google Tasksへ同期済みの項目を完了にします。未同期・完了済みの項目は変更しません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("完了にする") { _, _ ->
                ids.forEach(database::requestCompletion)
                selectedTaskIds.clear()
                enqueueSync()
                render()
            }
            .show()
    }

    private fun confirmBulkDeletion() {
        val ids = selectedTaskIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("${ids.size}件を削除しますか？")
            .setMessage("同期済みの場合は、Google Tasksと追跡中のCalendar枠からも削除されます。この操作は元に戻せません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                ids.forEach(database::requestDeletion)
                selectedTaskIds.clear()
                enqueueSync()
                render()
            }
            .show()
    }

    private fun showTaskDetail(id: Long) {
        val detail = database.taskDetail(id) ?: return
        val message = buildString {
            append("状態: ${stateLabel(detail.state)}")
            append("\n通知元: ${sourceLabel(detail.sourcePackage)}")
            append("\n工数: ${detail.effort}  /  優先度: ${priorityLabel(detail.priority)}")
            append("\nカテゴリ: ${detail.category}")
            append("\n期限: ${formatDeadline(detail.deadlineEpochMillis)}")
            append("\n配置: ${formatDateTime(detail.scheduledStartEpochMillis)}")
            if (detail.scheduledEndEpochMillis != null) {
                append(" 〜 ${formatDateTime(detail.scheduledEndEpochMillis)}")
            }
            append("\n再計画: ${detail.recoveryCount}回")
            append("\nGoogle Task: ${detail.googleTaskId?.take(16) ?: "未同期"}")
            if (detail.blocks.isEmpty()) append("\nCalendar枠: 未追跡または未配置")
            else {
                append("\n\nCalendar枠 (${detail.blocks.size}件)")
                detail.blocks.forEach { block ->
                    append("\n${block.blockIndex}. ${formatDateTime(block.startEpochMillis)}")
                    append(" 〜 ${formatDateTime(block.endEpochMillis)}")
                    append("\n配置理由: " + app.hisho.scheduling.ScheduleExplanationStore(this@MetadataActivity)
                        .read(block.calendarEventId, block.startEpochMillis, block.endEpochMillis))
                }
            }
            if (detail.lastErrorCode != null) append(errorDescription(detail.lastErrorCode))
        }
        AlertDialog.Builder(this)
            .setTitle(detail.actionTitle)
            .setMessage(message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun formatDateTime(epochMillis: Long?): String = epochMillis?.let {
        DETAIL_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "未配置"

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
                        enqueueSync()
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

    private fun errorDescription(errorCode: String?): String = when (errorCode) {
        "calendar_event_missing" -> "\n要確認理由: Calendar予定が削除されています"
        "recovery_limit" -> "\n要確認理由: 再計画の上限に達しました"
        null -> ""
        else -> "\nエラー: $errorCode"
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
        "app.hisho.manual" -> "手動入力"
        else -> packageName
    }

    private fun stateLabel(state: String): String = when (state) {
        "PENDING" -> "未同期"
        "RETRY" -> "再試行待ち"
        "SYNCED" -> "同期済み"
        "COMPLETED" -> "完了"
        "IGNORED" -> "除外"
        "FAILED" -> "同期失敗"
        "NEEDS_ATTENTION" -> "再配置の確認が必要"
        "COMPLETE_REQUESTED" -> "完了を同期中"
        "DELETE_REQUESTED" -> "削除を同期中"
        else -> state
    }

    private fun stateColor(state: String): Int = when (state) {
        "PENDING", "RETRY" -> Color.rgb(181, 91, 28)
        "SYNCED" -> Color.rgb(28, 112, 76)
        "COMPLETED" -> Color.rgb(58, 91, 160)
        "FAILED" -> Color.rgb(168, 62, 48)
        "NEEDS_ATTENTION" -> Color.rgb(168, 62, 48)
        "COMPLETE_REQUESTED" -> Color.rgb(58, 91, 160)
        "DELETE_REQUESTED" -> Color.rgb(168, 62, 48)
        else -> Color.rgb(80, 86, 82)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val DEADLINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        val DETAIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    }

    private enum class TaskFilter(val label: String, val states: Set<String>) {
        INBOX("対応が必要", emptySet()),
        RISK("期限注意", emptySet()),
        ALL("すべて", emptySet()),
        PENDING("未同期", setOf("PENDING", "RETRY")),
        SYNCED("同期済み", setOf("SYNCED", "COMPLETE_REQUESTED")),
        ATTENTION("要確認", setOf("NEEDS_ATTENTION", "FAILED")),
        COMPLETED("完了", setOf("COMPLETED"));

        fun next(): TaskFilter = entries[(ordinal + 1) % entries.size]
    }
}
