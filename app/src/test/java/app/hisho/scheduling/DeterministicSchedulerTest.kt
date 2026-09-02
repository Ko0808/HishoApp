package app.hisho.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.LocalTime

class DeterministicSchedulerTest {
    private val scheduler = DeterministicScheduler(
        zoneId = ZoneId.of("Asia/Tokyo"),
        bufferMinutes = 10,
        dailyCapacityMinutes = 360,
    )

    @Test
    fun schedulesIntoFirstFreeAllowedSlot() {
        val now = Instant.parse("2026-09-02T00:12:00Z") // 09:12 JST
        val slot = scheduler.findSlot(
            now = now,
            durationMinutes = 25,
            deadline = Instant.parse("2026-09-03T14:59:00Z"),
            busy = listOf(
                DeterministicScheduler.BusyInterval(
                    Instant.parse("2026-09-02T00:00:00Z"),
                    Instant.parse("2026-09-02T01:00:00Z"),
                ),
            ),
        )!!
        assertEquals(Instant.parse("2026-09-02T01:10:00Z"), slot.start)
        assertEquals(Instant.parse("2026-09-02T01:35:00Z"), slot.end)
    }

    @Test
    fun neverSchedulesDuringNight() {
        val now = Instant.parse("2026-09-02T16:00:00Z") // 01:00 JST
        val slot = scheduler.findSlot(now, 10, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-03T00:00:00Z"), slot.start) // 09:00 JST
    }

    @Test
    fun movesToNextDayWhenDailyCapacityWouldBeExceeded() {
        val now = Instant.parse("2026-09-02T00:00:00Z")
        val slot = scheduler.findSlot(
            now = now,
            durationMinutes = 120,
            deadline = null,
            busy = listOf(
                DeterministicScheduler.BusyInterval(
                    Instant.parse("2026-09-02T00:00:00Z"),
                    Instant.parse("2026-09-02T05:30:00Z"),
                ),
            ),
        )!!
        assertEquals(Instant.parse("2026-09-03T00:00:00Z"), slot.start)
    }

    @Test
    fun skipsWeekendByDefault() {
        val fridayNight = Instant.parse("2026-09-04T11:00:00Z") // Friday 20:00 JST
        val slot = scheduler.findSlot(fridayNight, 25, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), slot.start) // Monday 09:00 JST
    }

    @Test
    fun avoidsLunchBreakWithBuffer() {
        val beforeLunch = Instant.parse("2026-09-02T02:55:00Z") // 11:55 JST
        val slot = scheduler.findSlot(beforeLunch, 25, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-02T04:10:00Z"), slot.start) // 13:10 JST
    }

    @Test
    fun usesPerWeekdayWindowAndSkipsDisabledDay() {
        val custom = DeterministicScheduler(
            zoneId = ZoneId.of("Asia/Tokyo"),
            bufferMinutes = 0,
            dailyCapacityMinutes = 480,
            dailyWindows = mapOf(
                DayOfWeek.WEDNESDAY to null,
                DayOfWeek.THURSDAY to DeterministicScheduler.WorkingWindow(
                    LocalTime.of(10, 0), LocalTime.of(16, 0),
                ),
            ),
        )
        val wednesday = Instant.parse("2026-09-02T00:00:00Z")
        val slot = custom.findSlot(wednesday, 25, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-03T01:00:00Z"), slot.start)
    }

    @Test
    fun respectsCustomBreakTime() {
        val custom = DeterministicScheduler(
            zoneId = ZoneId.of("Asia/Tokyo"),
            bufferMinutes = 5,
            dailyCapacityMinutes = 480,
            breakStart = LocalTime.of(14, 30),
            breakEnd = LocalTime.of(15, 15),
        )
        val now = Instant.parse("2026-09-02T05:20:00Z") // 14:20 JST
        val slot = custom.findSlot(now, 25, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-02T06:20:00Z"), slot.start) // 15:20 JST
    }
}
