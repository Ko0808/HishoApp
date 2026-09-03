package app.hisho.ai

import app.hisho.scheduling.DeterministicScheduler
import app.hisho.scheduling.SchedulingPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class AnonymousAvailabilityDay(val date: String, val availableMinutes: Int) {
    fun toJson() = JSONObject().put("date", date).put("available_minutes", availableMinutes)
}

object AnonymousAvailabilityCalculator {
    fun calculate(
        settings: SchedulingPreferences,
        busy: List<DeterministicScheduler.BusyInterval>,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
        numberOfDays: Int = 8,
    ): List<AnonymousAvailabilityDay> = List(numberOfDays) { offset ->
        val date = now.atZone(zoneId).toLocalDate().plusDays(offset.toLong())
        if (!settings.isDayEnabled(date.dayOfWeek)) return@List AnonymousAvailabilityDay(date.toString(), 0)
        val start = maxOf(date.atTime(settings.startHour(date.dayOfWeek), 0).atZone(zoneId).toInstant(), now)
        val end = date.atTime(settings.endHour(date.dayOfWeek), 0).atZone(zoneId).toInstant()
        if (!end.isAfter(start)) return@List AnonymousAvailabilityDay(date.toString(), 0)
        val unavailable = busy.mapNotNull { interval ->
            val clippedStart = maxOf(interval.start, start)
            val clippedEnd = minOf(interval.end, end)
            if (clippedEnd.isAfter(clippedStart)) clippedStart to clippedEnd else null
        }.toMutableList()
        if (settings.lunchBreakEnabled) {
            val breakStart = date.atTime(LocalTime.ofSecondOfDay(settings.breakStartMinutes * 60L)).atZone(zoneId).toInstant()
            val breakEnd = date.atTime(LocalTime.ofSecondOfDay(settings.breakEndMinutes * 60L)).atZone(zoneId).toInstant()
            val clippedStart = maxOf(breakStart, start)
            val clippedEnd = minOf(breakEnd, end)
            if (clippedEnd.isAfter(clippedStart)) unavailable += clippedStart to clippedEnd
        }
        val unavailableMinutes = mergedMinutes(unavailable)
        val windowMinutes = Duration.between(start, end).toMinutes().toInt()
        AnonymousAvailabilityDay(
            date.toString(),
            (minOf(windowMinutes, settings.dailyCapacityMinutes) - unavailableMinutes).coerceAtLeast(0),
        )
    }

    fun toJson(days: List<AnonymousAvailabilityDay>) = JSONArray().apply { days.forEach { put(it.toJson()) } }

    private fun mergedMinutes(intervals: List<Pair<Instant, Instant>>): Int {
        if (intervals.isEmpty()) return 0
        val sorted = intervals.sortedBy { it.first }
        var start = sorted.first().first
        var end = sorted.first().second
        var total = 0L
        sorted.drop(1).forEach { (nextStart, nextEnd) ->
            if (!nextStart.isAfter(end)) end = maxOf(end, nextEnd)
            else {
                total += Duration.between(start, end).toMinutes()
                start = nextStart
                end = nextEnd
            }
        }
        return (total + Duration.between(start, end).toMinutes()).toInt()
    }
}
