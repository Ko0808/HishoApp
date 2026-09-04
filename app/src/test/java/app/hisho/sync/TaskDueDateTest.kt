package app.hisho.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TaskDueDateTest {
    @Test fun noDeadlineStaysAbsent() { assertNull(TaskDueDate.format(null)) }
    @Test fun preservesJapaneseCalendarDateNotUtcDate() {
        assertEquals("2026-09-05T00:00:00Z", TaskDueDate.format(Instant.parse("2026-09-04T15:30:00Z").toEpochMilli(), ZoneId.of("Asia/Tokyo")))
    }
    @Test fun discardsTimeOnSameDate() {
        assertEquals("2026-09-04T00:00:00Z", TaskDueDate.format(Instant.parse("2026-09-04T08:30:00Z").toEpochMilli(), ZoneId.of("Asia/Tokyo")))
    }
}
