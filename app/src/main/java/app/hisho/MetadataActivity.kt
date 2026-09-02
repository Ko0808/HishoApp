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
            text = "工数ボタンは XS → S → M → L → XL の順に変更します。同期済みタスクの候補状態は変更されません。"
            setPadding(0, 8.dp, 0, 16.dp)
        })

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
                text = "${sourceLabel(item.sourcePackage)}  •  ${item.state}"
                textSize = 17f
            })
            card.addView(TextView(this).apply {
                text = "${item.category} / ${item.priority} / ${item.deadlineType}\n判定: ${item.reason}"
            })
            card.addView(TextView(this).apply {
                text = item.actionTitle.ifBlank { "（旧データ：タイトル未保存）" }
                textSize = 18f
                setPadding(0, 8.dp, 0, 4.dp)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "名前を編集"
                isEnabled = item.state !in setOf("SYNCED", "FAILED") && item.actionTitle.isNotBlank()
                setOnClickListener { showTitleEditor(item.id, item.actionTitle) }
            })
            actions.addView(Button(this).apply {
                text = "工数 ${item.effort}"
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
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12.dp })
        }
    }

    private fun showTitleEditor(id: Long, currentTitle: String) {
        val input = EditText(this).apply {
            setText(currentTitle)
            setSelection(text.length)
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

    private fun enqueueSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            CaptureSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CaptureSyncWorker>().build(),
        )
        Toast.makeText(this, "修正を保存しました", Toast.LENGTH_SHORT).show()
    }

    private fun sourceLabel(packageName: String): String = when (packageName) {
        "com.google.android.gm" -> "Gmail"
        "com.Slack" -> "Slack"
        "com.discord" -> "Discord"
        "jp.naver.line.android" -> "LINE"
        else -> packageName
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
