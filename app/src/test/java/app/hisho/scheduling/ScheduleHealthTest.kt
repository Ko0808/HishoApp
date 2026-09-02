package app.hisho.scheduling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleHealthTest {
    @Test
    fun flagsMissingOrLateScheduleWhenDeadlineExists() {
        assertTrue(ScheduleHealth.isAtRisk(100, 1_000, null))
        assertTrue(ScheduleHealth.isAtRisk(100, 1_000, 1_001))
    }

    @Test
    fun acceptsScheduleFinishingByDeadline() {
        assertFalse(ScheduleHealth.isAtRisk(100, 1_000, 1_000))
    }

    @Test
    fun ignoresTasksWithoutDeadline() {
        assertFalse(ScheduleHealth.isAtRisk(100, null, null))
    }
}
