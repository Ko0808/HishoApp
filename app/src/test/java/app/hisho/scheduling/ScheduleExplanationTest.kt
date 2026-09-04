package app.hisho.scheduling

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleExplanationTest {
    private val start = Instant.parse("2026-09-04T00:00:00Z")
    private val slot = DeterministicScheduler.Slot(start, start.plusSeconds(1500))
    @Test fun explainsMissingDeadline() {
        assertTrue(DeterministicScheduler().explanation(slot, null).contains("期限は指定されていません"))
    }
    @Test fun warnsWhenDeadlineExceeded() {
        assertTrue(DeterministicScheduler().explanation(slot, start).contains("期限を超えています"))
    }
    @Test fun acceptsExactDeadline() {
        assertTrue(DeterministicScheduler().explanation(slot, slot.end).contains("指定期限までに終了"))
    }
    @Test fun usesActualSchedulerSettings() {
        val text = DeterministicScheduler(bufferMinutes = 15, dailyCapacityMinutes = 240).explanation(slot, null)
        assertTrue(text.contains("余白は15分"))
        assertTrue(text.contains("上限は240分"))
    }
}
