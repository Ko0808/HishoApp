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
import app.hisho.ui.HishoDesign as D

class MetadataActivity : app.hisho.ui.GlassActivity() {
    private lateinit var root: LinearLayout
    private val database by lazy { CaptureQueueDatabase(this) }
    private var selectedFilter = TaskFilter.INBOX
    private var searchQuery = ""
    private val selectedTaskIds = mutableSetOf<Long>()
    private lateinit var bulkCompleteButton: Button
    private lateinit var bulkDeleteButton: Button
    private lateinit var clearSelectionButton: Button
    private var selectionMode = false
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("show_all", false)) selectedFilter = TaskFilter.ALL
        val filterName = savedInstanceState?.getString("filter") ?: intent.getStringExtra("filter")
        TaskFilter.entries.firstOrNull { it.name == filterName }?.let { selectedFilter = it }
        searchQuery = savedInstanceState?.getString("query").orEmpty()
        selectionMode = savedInstanceState?.getBoolean("selection", false) ?: false
        savedInstanceState?.getLongArray("selected")?.forEach { selectedTaskIds += it }
        val padding = (20 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        render()
        if (savedInstanceState == null && intent.hasExtra("open_task_id")) {
            val id = intent.getLongExtra("open_task_id", -1)
            database.recentMetadata(10000).firstOrNull { it.id == id }?.let { root.post { showTaskOverview(it) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("filter", selectedFilter.name)
        outState.putString("query", searchQuery)
        outState.putBoolean("selection", selectionMode)
        outState.putLongArray("selected", selectedTaskIds.toLongArray())
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val previousScroll = scroll.scrollY
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "タスク"
            textSize = 28f
            setTextColor(Color.rgb(24, 39, 34))
        })
        root.addView(TextView(this).apply {
            text = "必要な用事だけ、ひとつずつ。"
            setPadding(0, 0, 0, 4.dp)
        })

        val syncCard = D.card(this)
        syncCard.addView(app.hisho.ui.SyncStatusView(this, onSync = { enqueueSync() }) { root.post { render() } })
        root.addView(syncCard, D.spacing(this))

        val quickFilters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = D.OWNED }
        listOf(TaskFilter.ATTENTION, TaskFilter.PENDING, TaskFilter.SYNCED).forEach { filter ->
            quickFilters.addView(D.button(this, filter.label, if (selectedFilter == filter) D.SELECTED else D.QUIET) {
                selectedFilter = filter; selectedTaskIds.clear(); render()
            }.apply { isSelected = selectedFilter == filter }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 4.dp })
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quickFilters) })

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(Button(this).apply {
            text = if (selectedFilter in listOf(TaskFilter.ATTENTION, TaskFilter.PENDING, TaskFilter.SYNCED)) "その他 ▾" else "${selectedFilter.label} ▾"
            tag = D.QUIET
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
            tag = D.QUIET
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
        filterRow.addView(D.button(this, if (selectionMode) "選択終了" else "選択") {
            selectionMode = !selectionMode
            selectedTaskIds.clear(); render()
        })
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
        bulkRow.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
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
            val card = D.card(this)
            if (selectionMode) card.addView(CheckBox(this).apply {
                text = "選択"
                contentDescription = "${item.actionTitle}を選択"
                isChecked = item.id in selectedTaskIds
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedTaskIds += item.id else selectedTaskIds -= item.id
                    updateBulkButtons()
                }
            })
            card.addView(TextView(this).apply {
                val risk = item.state == "SYNCED" && selectedFilter in setOf(TaskFilter.INBOX, TaskFilter.RISK)
                text = "${sourceLabel(item.sourcePackage)}  •  ${if (risk) "期限注意" else stateLabel(item.state)}"
                textSize = 15f
                setTextColor(if (risk) Color.rgb(168, 62, 48) else stateColor(item.state))
            })
            card.addView(TextView(this).apply {
                text = item.actionTitle.ifBlank { "タイトル未保存" }
                textSize = 20f
                setPadding(0, 8.dp, 0, 4.dp)
            })
            card.addView(TextView(this).apply {
                text = "期限: ${formatDeadline(item.deadlineEpochMillis)}" + errorDescription(item.lastErrorCode)
            })
            card.addView(Button(this).apply {
                text = if (item.state == "REVIEW") "内容を確認する  ›" else "タスクを開く  ›"
                tag = D.QUIET
                contentDescription = "${item.actionTitle}を開く"
                setOnClickListener { showTaskOverview(item) }
            })
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12.dp })
        }
        root.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun showTaskOverview(item: CaptureQueueDatabase.MetadataItem) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 12.dp)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (item.state == "REVIEW") "通知を確認" else "タスク")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("閉じる", null).create()
        content.addView(D.text(this, item.actionTitle, 20f, true))
        content.addView(D.text(this, "${sourceLabel(item.sourcePackage)} · ${stateLabel(item.state)}", 15f))
        content.addView(D.text(this, "判定理由", 17f, true))
        content.addView(D.text(this, item.reason.ifBlank { "判定理由は記録されていません" }))
        if (item.state == "REVIEW") {
            content.addView(D.text(this, "元の通知", 17f, true))
            val original = D.text(this, database.notificationPreview(item.id)).apply {
                setTextIsSelectable(true); maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(original)
            content.addView(D.button(this, "通知の全文を表示") {
                original.maxLines = Int.MAX_VALUE; original.ellipsize = null
            })
            content.addView(D.text(this, "登録前に名前を直す場合は「名前・期限などを編集」へ。", 14f))
            content.addView(D.button(this, "確認してタスクに登録", D.PRIMARY) {
                database.approveReview(item.id); dialog.dismiss(); enqueueSync(); render()
            })
            content.addView(D.button(this, "AIで再判定する") {
                database.retryReview(item.id); dialog.dismiss(); enqueueSync(); render()
            })
            content.addView(D.button(this, "不要として除外") {
                database.filterResult(item.id, "IGNORED", "ユーザーが除外"); dialog.dismiss(); render()
            })
        } else if (item.state in setOf("SYNCED", "NEEDS_ATTENTION")) {
            content.addView(D.text(this, "期限: ${formatDeadline(item.deadlineEpochMillis)}"))
            content.addView(D.button(this, "完了にする", D.PRIMARY) {
                database.requestCompletion(item.id); dialog.dismiss(); enqueueSync(); render()
            })
        }
        content.addView(D.button(this, "名前・期限などを編集 / 詳細・操作") { dialog.dismiss(); showTaskActions(item) })
        dialog.show()
        app.hisho.ui.GlassUi().apply(content, content)
        D.reveal(content)
    }

    private fun showTaskActions(item: CaptureQueueDatabase.MetadataItem) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun option(label: String, action: () -> Unit) { labels += label; actions += action }
        option("判定理由と詳細を見る") { showTaskDetail(item.id) }
        if (item.state == "REVIEW") {
            option("元の通知を読む") {
                AlertDialog.Builder(this).setTitle("元の通知（最大4000文字）").setMessage(database.notificationPreview(item.id)).setPositiveButton("閉じる", null).show()
            }
            option("確認してタスクに登録") { database.approveReview(item.id); enqueueSync(); render() }
            option("ルール・AIで再判定") { database.retryReview(item.id); enqueueSync(); render() }
            option("不要として除外") { database.filterResult(item.id, "IGNORED", "ユーザーが除外"); render() }
        }
        if (item.state == "NEEDS_ATTENTION") {
            option("Google同期を再実行") { enqueueSync() }
        }
        if (item.state in setOf("SYNCED", "NEEDS_ATTENTION")) {
            option("完了にする") { database.requestCompletion(item.id); enqueueSync(); render() }
        }
        if (item.state == "SYNCED") {
            option("Googleへ編集内容を同期") { database.requestReschedule(item.id); enqueueSync(); render() }
        }
        if (item.state in setOf("PENDING", "RETRY")) option("同期を再実行") { enqueueSync() }
        if (item.state in setOf("PENDING", "RETRY", "SYNCED", "IGNORED", "REVIEW")) {
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
            append("判定理由: ${database.recentMetadata(10000).firstOrNull { it.id == id }?.reason ?: "記録なし"}\n")
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
        app.hisho.sync.SyncStatusStore(this).markQueued()
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build(),
        )
        Toast.makeText(this, "同期を受け付けました。上部に進行状況を表示します", Toast.LENGTH_SHORT).show()
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
        "REVIEW" -> "登録前の確認待ち"
        "PENDING" -> "未同期"
        "RETRY" -> "再試行待ち"
        "SYNCED" -> "同期済み"
        "COMPLETED" -> "完了"
        "IGNORED" -> "除外"
        "FAILED" -> "同期失敗"
        "NEEDS_ATTENTION" -> "旧タスクの確認が必要"
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
        ATTENTION("要確認", setOf("NEEDS_ATTENTION", "FAILED", "REVIEW")),
        IGNORED("除外履歴", setOf("IGNORED")),
        COMPLETED("完了", setOf("COMPLETED"));

        fun next(): TaskFilter = entries[(ordinal + 1) % entries.size]
    }
}
