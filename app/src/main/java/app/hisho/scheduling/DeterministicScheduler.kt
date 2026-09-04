package app.hisho.scheduling

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek

class DeterministicScheduler(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val allowedStart: LocalTime = LocalTime.of(9, 0),
    private val allowedEnd: LocalTime = LocalTime.of(18, 0),
    private val bufferMinutes: Int = 10,
    private val dailyCapacityMinutes: Int = 360,
    private val weekendsEnabled: Boolean = false,
    private val breakStart: LocalTime? = LocalTime.NOON,
    private val breakEnd: LocalTime? = LocalTime.of(13, 0),
    private val dailyWindows: Map<DayOfWeek, WorkingWindow?>? = null,
) {
    data class BusyInterval(val start: Instant, val end: Instant)
    data class Slot(val start: Instant, val end: Instant)
    data class WorkingWindow(val start: LocalTime, val end: LocalTime)

    fun explanation(slot: Slot, deadline: Instant?): String = buildString {
        val window = dailyWindows?.get(slot.start.atZone(zoneId).dayOfWeek)
            ?: WorkingWindow(allowedStart, allowedEnd)
        append("作成時の稼働枠${window.start}〜${window.end}（${zoneId.id}）内で、既存予定")
        if (breakStart != null && breakEnd != null) append("と休憩${breakStart}〜${breakEnd}")
        append("を避け、${Duration.between(slot.start, slot.end).toMinutes()}分が収まる最初の空き枠を選びました。")
        append("予定間の余白は${bufferMinutes}分、1日の上限は${dailyCapacityMinutes}分（既存予定・休憩を含む）です。")
        append(" AIを利用していても、最終的な時刻は端末内のルールで決定します。")
        append(if (deadline == null) " 期限は指定されていません。"
            else if (slot.end.isAfter(deadline)) " この枠は期限を超えています。期限または工数を見直してください。"
            else " この枠は指定期限までに終了します。")
    }

    fun findSlot(
        now: Instant,
        durationMinutes: Int,
        deadline: Instant?,
        busy: List<BusyInterval>,
    ): Slot? {
        val duration = Duration.ofMinutes(durationMinutes.toLong())
        val minimumHorizon = now.plus(7, ChronoUnit.DAYS)
        val horizon = maxOf(deadline ?: minimumHorizon, minimumHorizon)
        var day = now.atZone(zoneId).toLocalDate()
        val finalDay = horizon.atZone(zoneId).toLocalDate()
        val sortedBusy = busy.sortedBy { it.start }

        while (!day.isAfter(finalDay)) {
            val window = dailyWindows?.get(day.dayOfWeek) ?: if (
                dailyWindows == null && (weekendsEnabled || day.dayOfWeek !in WEEKEND_DAYS)
            ) WorkingWindow(allowedStart, allowedEnd) else null
            if (window == null) {
                day = day.plusDays(1)
                continue
            }
            val dayStart = day.atTime(window.start).atZone(zoneId).toInstant()
            val dayEnd = if (window.end == LocalTime.MIDNIGHT) {
                day.plusDays(1).atStartOfDay(zoneId).toInstant()
            } else {
                day.atTime(window.end).atZone(zoneId).toInstant()
            }
            val breakInterval = if (breakStart != null && breakEnd != null) {
                BusyInterval(
                    day.atTime(breakStart).atZone(zoneId).toInstant(),
                    day.atTime(breakEnd).atZone(zoneId).toInstant(),
                ).takeIf { it.end.isAfter(dayStart) && it.start.isBefore(dayEnd) }
            } else null
            val dayBusy = (sortedBusy.filter { it.end.isAfter(dayStart) && it.start.isBefore(dayEnd) } +
                listOfNotNull(breakInterval)).sortedBy { it.start }
            val occupiedMinutes = mergedDurationMinutes(dayBusy, dayStart, dayEnd)
            if (occupiedMinutes + durationMinutes > dailyCapacityMinutes) {
                day = day.plusDays(1)
                continue
            }
            var cursor = maxOf(roundUp(now), dayStart)
            if (cursor.isBefore(dayEnd)) {
                dayBusy.forEach { interval ->
                    val bufferedStart = interval.start.minus(bufferMinutes.toLong(), ChronoUnit.MINUTES)
                    val bufferedEnd = interval.end.plus(bufferMinutes.toLong(), ChronoUnit.MINUTES)
                    if (!bufferedEnd.isAfter(cursor) || !bufferedStart.isBefore(dayEnd)) return@forEach
                    if (!cursor.plus(duration).isAfter(bufferedStart)) {
                        return Slot(cursor, cursor.plus(duration))
                    }
                    if (bufferedEnd.isAfter(cursor)) cursor = roundUp(bufferedEnd)
                }
                if (!cursor.plus(duration).isAfter(dayEnd)) return Slot(cursor, cursor.plus(duration))
            }
            day = day.plusDays(1)
        }
        return null
    }

    private fun mergedDurationMinutes(intervals: List<BusyInterval>, dayStart: Instant, dayEnd: Instant): Int {
        if (intervals.isEmpty()) return 0
        var total = 0L
        var start = maxOf(intervals.first().start, dayStart)
        var end = minOf(intervals.first().end, dayEnd)
        intervals.drop(1).forEach { interval ->
            val nextStart = maxOf(interval.start, dayStart)
            val nextEnd = minOf(interval.end, dayEnd)
            if (!nextStart.isAfter(end)) end = maxOf(end, nextEnd)
            else {
                total += Duration.between(start, end).toMinutes()
                start = nextStart
                end = nextEnd
            }
        }
        return (total + Duration.between(start, end).toMinutes()).toInt()
    }

    private fun roundUp(instant: Instant): Instant {
        val zoned = instant.atZone(zoneId)
        val truncated = zoned.truncatedTo(ChronoUnit.MINUTES)
        val remainder = truncated.minute % SLOT_GRANULARITY_MINUTES
        val add = if (remainder == 0 && zoned.second == 0 && zoned.nano == 0) 0
        else SLOT_GRANULARITY_MINUTES - remainder
        return truncated.plusMinutes(add.toLong()).withSecond(0).withNano(0).toInstant()
    }

    private companion object {
        const val SLOT_GRANULARITY_MINUTES = 5
        val WEEKEND_DAYS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
