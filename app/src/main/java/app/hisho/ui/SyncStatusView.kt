package app.hisho.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.hisho.sync.SyncStatusStore
import app.hisho.sync.SyncPresentation

/** Updates only this panel, not the task list or scroll position on every tick. */
class SyncStatusView(context: Context, onSync: (() -> Unit)? = null, private val onFinished: () -> Unit = {}) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val label = HishoDesign.text(context, "", 16f, true).apply { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val detail = HishoDesign.text(context, "", 14f)
    private var expanded = false
    private val disclosure = HishoDesign.button(context, "詳細 ▾") { expanded = !expanded; refresh() }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private var previous: SyncStatusStore.Snapshot? = null
    private val tick = object : Runnable {
        override fun run() { refresh(); if (isAttachedToWindow) handler.postDelayed(this, 1000) }
    }
    init {
        orientation = VERTICAL
        val heading = LinearLayout(context).apply { tag = HishoDesign.OWNED; orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(disclosure, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        if (onSync != null) heading.addView(HishoDesign.button(context, "同期") { onSync() }.apply { contentDescription = "今すぐ同期" })
        if (context.resources.configuration.fontScale >= 1.3f) {
            heading.orientation = VERTICAL
            label.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(heading); addView(progress); addView(detail); refresh()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }
    private fun refresh() {
        val snapshot = SyncStatusStore(context).snapshot()
        val stale = snapshot.state == "RUNNING" && System.currentTimeMillis() - snapshot.updatedAt > 10 * 60 * 1000L
        val time = if (snapshot.updatedAt == 0L) "未実行" else java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(snapshot.updatedAt))
        val text = if (stale) "同期の更新が停止" else when (snapshot.state) {
            "REVIEW_REQUIRED" -> "同期完了\n要確認あり"
            "NETWORK_ERROR" -> "通信待ち"
            "API_ERROR", "ERROR" -> "同期エラー"
            "AUTH_REQUIRED" -> "再接続が必要"
            "WAITING" -> "続きの処理待ち"
            else -> SyncPresentation.label(snapshot.state)
        }
        if (label.text.toString() != text) label.text = text
        val active = SyncPresentation.busy(snapshot.state) && !stale
        progress.visibility = if (active && HishoDesign.motion(context)) VISIBLE else GONE
        val needsExplanation = snapshot.state in setOf("API_ERROR", "NETWORK_ERROR", "AUTH_REQUIRED", "ERROR", "INTERRUPTED", "WAITING")
        detail.text = if (expanded || active || needsExplanation || stale) "${snapshot.detail.orEmpty()}\n最終更新: $time" else "最終更新: $time"
        disclosure.text = if (expanded) "閉じる ▴" else "詳細 ▾"
        disclosure.contentDescription = if (expanded) "同期の詳細を閉じる" else "同期の詳細を表示"
        val finished = previous?.let { it.updatedAt != snapshot.updatedAt && !SyncPresentation.busy(snapshot.state) } == true
        previous = snapshot
        if (finished) onFinished()
    }
}
