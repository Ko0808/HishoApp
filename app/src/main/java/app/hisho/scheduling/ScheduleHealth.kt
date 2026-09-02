package app.hisho.scheduling

object ScheduleHealth {
    fun isAtRisk(nowMillis: Long, deadlineMillis: Long?, scheduledEndMillis: Long?): Boolean {
        if (deadlineMillis == null) return false
        return deadlineMillis < nowMillis || scheduledEndMillis == null || scheduledEndMillis > deadlineMillis
    }
}
