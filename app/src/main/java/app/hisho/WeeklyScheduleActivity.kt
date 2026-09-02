package app.hisho

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.hisho.scheduling.SchedulingPreferences
import java.time.DayOfWeek

class WeeklyScheduleActivity : Activity() {
    private lateinit var root: LinearLayout
    private val settings by lazy { SchedulingPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            setBackgroundColor(Color.rgb(246, 247, 242))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply { text = "曜日別の稼働時間"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "曜日をOFFにすると、その日には新しいタスクを配置しません。時刻ボタンを押すと1時間ずつ変更します。"
            setPadding(0, 8.dp, 0, 16.dp)
        })
        DayOfWeek.entries.forEach { day ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(CheckBox(this).apply {
                text = dayLabel(day)
                isChecked = settings.isDayEnabled(day)
                setOnCheckedChangeListener { _, checked -> settings.setDayEnabled(day, checked) }
            })
            row.addView(Button(this).apply {
                text = "開始 ${settings.startHour(day)}:00"
                setOnClickListener { settings.cycleStartHour(day); render() }
            })
            row.addView(Button(this).apply {
                text = "終了 ${settings.endHour(day)}:00"
                setOnClickListener { settings.cycleEndHour(day); render() }
            })
            root.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun dayLabel(day: DayOfWeek) = listOf("月", "火", "水", "木", "金", "土", "日")[day.value - 1]
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
