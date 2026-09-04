package app.hisho.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.WeakHashMap

/** Static translucent surfaces: no live blur, continuous animation or offscreen screenshots. */
class GlassUi {
    private val styled = WeakHashMap<View, Boolean>()

    fun apply(shell: View, content: View) {
        fun visit(view: View) {
            val fresh = styled.put(view, true) == null
            val context = view.context
            val background = view.background
            if (view !== shell && background is ColorDrawable) {
                val isPage = view === content || (view.parent === content && content is ScrollView)
                view.background = if (isPage) null else surface(context, background.color)
            }
            if (fresh) {
                when (view) {
                    is EditText -> {
                        view.background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                                setColor(0xF5FFFFFF.toInt()); cornerRadius = dp(context, 22).toFloat()
                                setStroke(dp(context, 2), ACCENT)
                            })
                            addState(intArrayOf(), surface(context, Color.WHITE))
                        }
                        view.backgroundTintList = null
                        view.setTextColor(INK)
                        view.setHintTextColor(MUTED)
                        view.textSize = 17f
                        view.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
                        view.minimumHeight = dp(context, 58)
                        margins(view, 10)
                    }
                    is CheckBox -> {
                        view.buttonTintList = ColorStateList.valueOf(ACCENT)
                        view.setTextColor(INK)
                        view.textSize = 16f
                        view.minHeight = dp(context, 50)
                        view.setPadding(dp(context, 4), dp(context, 6), dp(context, 8), dp(context, 6))
                    }
                    is Button -> styleButton(view)
                    is TextView -> {
                        val sp = if (android.os.Build.VERSION.SDK_INT >= 34)
                            android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, view.textSize, context.resources.displayMetrics)
                        else view.textSize / context.resources.displayMetrics.scaledDensity
                        val title = sp >= 26
                        val section = sp >= 19 && !title
                        view.textSize = if (title) 32f else if (section) 20f else 15f
                        view.typeface = Typeface.create(if (title || section) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                        view.setLineSpacing(dp(context, if (title) 2 else 4).toFloat(), 1f)
                        if (title || section || view.currentTextColor == Color.BLACK || view.currentTextColor == Color.rgb(92, 101, 97))
                            view.setTextColor(if (title || section) INK else MUTED)
                        if (title) {
                            view.letterSpacing = -0.025f
                            view.setPadding(view.paddingLeft, dp(context, 10), view.paddingRight, dp(context, 14))
                        }
                        if (view.background != null) view.elevation = dp(context, 1).toFloat()
                        else if (!title) margins(view, 8)
                    }
                    is ScrollView -> {
                        view.isVerticalScrollBarEnabled = false
                        view.clipToPadding = false
                        view.isFillViewport = true
                    }
                }
                if (view is LinearLayout && view.parent === content && content is ScrollView) {
                    view.setPadding(dp(context, 22), dp(context, 8), dp(context, 22), dp(context, 28))
                }
                if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL) {
                    // Existing filter/bulk rows remain rows, but now fit without clipping actions.
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child is Button) child.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(context, 4)
                        }
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(shell)
    }

    private fun styleButton(button: Button) {
        val context = button.context
        val label = button.text.toString()
        val primary = label in setOf("保存して同期", "設定を保存", "ルールを保存", "タスクをすぐ追加", "タスクを確認する")
        val destructive = label.contains("削除")
        val color = if (destructive) Color.rgb(161, 47, 61) else if (primary) Color.WHITE else ACCENT
        button.isAllCaps = false
        button.textSize = 16f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minHeight = dp(context, 54)
        button.minimumHeight = dp(context, 54)
        button.minWidth = 0
        button.setPadding(dp(context, 14), dp(context, 13), dp(context, 14), dp(context, 13))
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(121, 132, 150), color)))
        val shape = if (primary && !destructive) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(61, 123, 226), Color.rgb(39, 88, 184))).apply {
                cornerRadius = dp(context, 20).toFloat()
                setStroke(dp(context, 1), Color.argb(130, 255, 255, 255))
            } else surface(context, Color.WHITE)
        button.backgroundTintList = null
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), surface(context, 0xFFD9E0EB.toInt()))
            addState(intArrayOf(), shape)
        }
        button.background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x18365EC1), states, null)
        button.elevation = dp(context, 2).toFloat()
        button.stateListAnimator = StateListAnimator().apply {
            fun animation(scale: Float) = AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(button, View.SCALE_X, scale), ObjectAnimator.ofFloat(button, View.SCALE_Y, scale))
                duration = 130
            }
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), animation(0.975f))
            addState(intArrayOf(), animation(1f))
        }
        margins(button, 8)
    }

    private fun margins(view: View, bottom: Int) {
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = maxOf(it.bottomMargin, dp(view.context, bottom))
            view.layoutParams = it
        }
    }

    companion object {
        val INK = Color.rgb(25, 38, 62)
        val MUTED = Color.rgb(78, 94, 119)
        val ACCENT = Color.rgb(35, 83, 169)
        fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun surface(context: Context, tint: Int): Drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(205, Color.red(tint), Color.green(tint), Color.blue(tint)))).apply {
                cornerRadius = dp(context, 22).toFloat()
                setStroke(dp(context, 1), Color.argb(245, 255, 255, 255))
            }
    }
}

class GlassBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFFF0F5FF.toInt(), 0xFFEDF4F7.toInt(), 0xFFF2EEFA.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(w * .9f, h * .12f, maxOf(w, 1f) * .85f, 0x5584B7EE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(w * .03f, h * .7f, maxOf(w, 1f) * .8f, 0x3391DCCB, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Required Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
}
