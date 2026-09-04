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
    override fun onResume() {
        super.onResume()
        if (!HishoDesign.motion(this)) {
            fun stopMotion(view: View) {
                view.stateListAnimator = null
                if (view is ViewGroup) for (i in 0 until view.childCount) stopMotion(view.getChildAt(i))
            }
            stopMotion(window.decorView)
        }
    }
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
        val toolbar = LinearLayout(this).apply {
            tag = HishoDesign.OWNED; orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(GlassUi.dp(context, 16), GlassUi.dp(context, 4), GlassUi.dp(context, 16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEFFFFFFF.toInt()); cornerRadius = GlassUi.dp(context, 24).toFloat()
            }
        }
        if (this !is app.hisho.MainActivity) toolbar.addView(HishoDesign.button(this, "‹ 戻る") { finish() }.apply {
            contentDescription = "前の画面へ戻る"
        }) else toolbar.addView(HishoDesign.text(this, "Hisho", 18f, true))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (this is app.hisho.MainActivity) toolbar.addView(HishoDesign.button(this, "設定") {
            startActivity(android.content.Intent(this, app.hisho.SettingsActivity::class.java))
        })
        shell.addView(toolbar)
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
