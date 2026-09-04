package app.hisho.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Explicit roles, not decorative effects, establish the action hierarchy. */
object HishoDesign {
    const val OWNED = "hisho:owned"
    const val PRIMARY = "hisho:primary"
    const val QUIET = "hisho:quiet"
    const val SELECTED = "hisho:selected"
    fun dp(context: Context, value: Int) = GlassUi.dp(context, value)
    fun motion(context: Context) = ValueAnimator.areAnimatorsEnabled() &&
        !context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
    fun text(context: Context, value: String, size: Float = 16f, strong: Boolean = false) = TextView(context).apply {
        tag = OWNED; text = value; textSize = size
        setTextColor(if (strong) GlassUi.INK else GlassUi.MUTED)
        typeface = Typeface.create(if (strong) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setPadding(0, dp(context, 4), 0, dp(context, 4))
        if (size >= 22) androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    fun button(context: Context, value: String, role: String = QUIET, action: () -> Unit) = Button(context).apply {
        tag = role; text = value; setOnClickListener { action() }
    }
    fun card(context: Context) = LinearLayout(context).apply {
        tag = OWNED; orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(context, 22).toFloat()
            setStroke(dp(context, 1), 0xFFE3E7EE.toInt())
        }
    }
    fun spacing(context: Context, bottom: Int = 12) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(context, bottom)
    }
    fun reveal(view: View) {
        if (!motion(view.context)) return
        view.alpha = .6f
        view.animate().alpha(1f).setDuration(140).start()
    }
}
