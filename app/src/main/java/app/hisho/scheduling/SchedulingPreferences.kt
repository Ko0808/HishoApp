package app.hisho.scheduling

import android.content.Context
import java.time.LocalTime

class SchedulingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val workdayStartHour: Int get() = preferences.getInt(KEY_START_HOUR, DEFAULT_START_HOUR)
    val workdayEndHour: Int get() = preferences.getInt(KEY_END_HOUR, DEFAULT_END_HOUR)
    val bufferMinutes: Int get() = preferences.getInt(KEY_BUFFER_MINUTES, DEFAULT_BUFFER_MINUTES)
    val dailyCapacityMinutes: Int get() = preferences.getInt(KEY_DAILY_CAPACITY, DEFAULT_DAILY_CAPACITY)
    var weekendsEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEEKENDS_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_WEEKENDS_ENABLED, value).apply()
    var lunchBreakEnabled: Boolean
        get() = preferences.getBoolean(KEY_LUNCH_BREAK_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_LUNCH_BREAK_ENABLED, value).apply()

    fun cycleWorkHours() {
        val currentIndex = WORK_HOUR_PRESETS.indexOf(workdayStartHour to workdayEndHour).coerceAtLeast(0)
        val next = WORK_HOUR_PRESETS[(currentIndex + 1) % WORK_HOUR_PRESETS.size]
        preferences.edit().putInt(KEY_START_HOUR, next.first).putInt(KEY_END_HOUR, next.second).apply()
    }

    fun cycleBuffer() {
        val currentIndex = BUFFER_PRESETS.indexOf(bufferMinutes).coerceAtLeast(0)
        preferences.edit().putInt(KEY_BUFFER_MINUTES, BUFFER_PRESETS[(currentIndex + 1) % BUFFER_PRESETS.size]).apply()
    }

    fun cycleDailyCapacity() {
        val currentIndex = CAPACITY_PRESETS.indexOf(dailyCapacityMinutes).coerceAtLeast(0)
        preferences.edit().putInt(KEY_DAILY_CAPACITY, CAPACITY_PRESETS[(currentIndex + 1) % CAPACITY_PRESETS.size]).apply()
    }

    fun scheduler() = DeterministicScheduler(
        allowedStart = LocalTime.of(workdayStartHour, 0),
        allowedEnd = LocalTime.of(workdayEndHour, 0),
        bufferMinutes = bufferMinutes,
        dailyCapacityMinutes = dailyCapacityMinutes,
        weekendsEnabled = weekendsEnabled,
        breakStart = if (lunchBreakEnabled) LocalTime.NOON else null,
        breakEnd = if (lunchBreakEnabled) LocalTime.of(13, 0) else null,
    )

    private companion object {
        const val FILE_NAME = "hisho_scheduling"
        const val KEY_START_HOUR = "start_hour"
        const val KEY_END_HOUR = "end_hour"
        const val KEY_BUFFER_MINUTES = "buffer_minutes"
        const val KEY_DAILY_CAPACITY = "daily_capacity"
        const val KEY_WEEKENDS_ENABLED = "weekends_enabled"
        const val KEY_LUNCH_BREAK_ENABLED = "lunch_break_enabled"
        const val DEFAULT_START_HOUR = 9
        const val DEFAULT_END_HOUR = 18
        const val DEFAULT_BUFFER_MINUTES = 10
        const val DEFAULT_DAILY_CAPACITY = 360
        val WORK_HOUR_PRESETS = listOf(8 to 17, 9 to 18, 10 to 19)
        val BUFFER_PRESETS = listOf(0, 5, 10, 15)
        val CAPACITY_PRESETS = listOf(240, 300, 360, 420)
    }
}
