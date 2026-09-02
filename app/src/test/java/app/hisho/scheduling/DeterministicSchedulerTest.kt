package app.hisho.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

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
}
