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
class SyncStatusView(context: Context, private val onFinished: () -> Unit = {}) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val label = TextView(context).apply { textSize = 16f; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private var previous: SyncStatusStore.Snapshot? = null
    private val tick = object : Runnable {
        override fun run() { refresh(); if (isAttachedToWindow) handler.postDelayed(this, 1000) }
    }
    init { orientation = VERTICAL; addView(label); addView(progress); refresh() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }
    private fun refresh() {
        val snapshot = SyncStatusStore(context).snapshot()
        val stale = snapshot.state == "RUNNING" && System.currentTimeMillis() - snapshot.updatedAt > 10 * 60 * 1000L
        val time = if (snapshot.updatedAt == 0L) "" else java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(snapshot.updatedAt))
        val text = "${if (stale) "同期の更新が止まっています。再実行してください" else SyncPresentation.label(snapshot.state)}\n${snapshot.detail.orEmpty()}\n最終更新: $time".trim()
        if (label.text.toString() != text) label.text = text
        progress.visibility = if (SyncPresentation.busy(snapshot.state) && !stale) VISIBLE else GONE
        val finished = previous?.let { it.updatedAt != snapshot.updatedAt && !SyncPresentation.busy(snapshot.state) } == true
        previous = snapshot
        if (finished) onFinished()
    }
}
