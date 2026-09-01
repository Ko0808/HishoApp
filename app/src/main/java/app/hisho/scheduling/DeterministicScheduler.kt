package app.hisho.scheduling

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class DeterministicScheduler(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val allowedStart: LocalTime = LocalTime.of(8, 0),
    private val allowedEnd: LocalTime = LocalTime.MIDNIGHT,
) {
    data class BusyInterval(val start: Instant, val end: Instant)
    data class Slot(val start: Instant, val end: Instant)

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
            val dayStart = day.atTime(allowedStart).atZone(zoneId).toInstant()
            val dayEnd = if (allowedEnd == LocalTime.MIDNIGHT) {
                day.plusDays(1).atStartOfDay(zoneId).toInstant()
            } else {
                day.atTime(allowedEnd).atZone(zoneId).toInstant()
            }
            var cursor = maxOf(roundUp(now), dayStart)
            if (cursor.isBefore(dayEnd)) {
                sortedBusy.forEach { interval ->
                    if (!interval.end.isAfter(cursor) || !interval.start.isBefore(dayEnd)) return@forEach
                    if (!cursor.plus(duration).isAfter(interval.start)) {
                        return Slot(cursor, cursor.plus(duration))
                    }
                    if (interval.end.isAfter(cursor)) cursor = roundUp(interval.end)
                }
                if (!cursor.plus(duration).isAfter(dayEnd)) return Slot(cursor, cursor.plus(duration))
            }
            day = day.plusDays(1)
        }
        return null
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
    }
}

