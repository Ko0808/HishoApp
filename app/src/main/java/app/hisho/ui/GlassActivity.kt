package app.hisho.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Presentation only: existing screen actions and navigation destinations remain unchanged. */
open class GlassActivity : Activity() {
    override fun setContentView(view: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = if (android.os.Build.VERSION.SDK_INT < 27) GlassUi.INK else Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassBackdrop()
        }
        if (this !is app.hisho.MainActivity) {
            shell.addView(Button(this).apply {
                text = "‹  戻る"
                contentDescription = "前の画面へ戻る"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(GlassUi.dp(this, 112), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = GlassUi.dp(this@GlassActivity, 20)
                topMargin = GlassUi.dp(this@GlassActivity, 8)
                bottomMargin = GlassUi.dp(this@GlassActivity, 4)
            })
        }
        shell.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(shell) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val skin = GlassUi()
        skin.apply(shell, view)
        shell.viewTreeObserver.addOnGlobalLayoutListener { skin.apply(shell, view) }
        super.setContentView(shell)
        // Do not let Android scroll the first actionable control into view on entry.
        shell.isFocusableInTouchMode = true
        shell.requestFocus()
        ViewCompat.requestApplyInsets(shell)
    }
}
