package app.hisho.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskBlockPlannerTest {
    @Test
    fun keepsShortTasksInOneBlock() {
        assertEquals(listOf(25), TaskBlockPlanner.split(25))
        assertEquals(listOf(60), TaskBlockPlanner.split(60))
    }

    @Test
    fun splitsLongTasksIntoOneHourBlocks() {
        assertEquals(listOf(60, 60), TaskBlockPlanner.split(120))
        assertEquals(listOf(60, 60, 60, 60), TaskBlockPlanner.split(240))
    }

    @Test
    fun appliesAiSuggestedMaximumBlockSizeWithoutLosingEffort() {
        assertEquals(listOf(45, 45, 30), TaskBlockPlanner.split(120, 45))
        assertEquals(120, TaskBlockPlanner.split(120, 25).sum())
    }
}
