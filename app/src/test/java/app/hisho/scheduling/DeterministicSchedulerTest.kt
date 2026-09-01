package app.hisho.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DeterministicSchedulerTest {
    private val scheduler = DeterministicScheduler(ZoneId.of("Asia/Tokyo"))

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
        assertEquals(Instant.parse("2026-09-02T01:00:00Z"), slot.start)
        assertEquals(Instant.parse("2026-09-02T01:25:00Z"), slot.end)
    }

    @Test
    fun neverSchedulesDuringNight() {
        val now = Instant.parse("2026-09-02T16:00:00Z") // 01:00 JST
        val slot = scheduler.findSlot(now, 10, null, emptyList())!!
        assertEquals(Instant.parse("2026-09-02T23:00:00Z"), slot.start) // 08:00 JST
    }
}
