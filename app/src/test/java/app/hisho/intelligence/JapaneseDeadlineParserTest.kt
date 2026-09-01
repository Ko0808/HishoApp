package app.hisho.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class JapaneseDeadlineParserTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val parser = JapaneseDeadlineParser(zone)
    private val now = ZonedDateTime.of(2026, 9, 1, 10, 0, 0, 0, zone)

    @Test
    fun parsesRelativeDateAndJapaneseTime() {
        val parsed = parser.parse("明日の17時までに提出", now)
        assertNotNull(parsed)
        assertEquals(
            ZonedDateTime.of(2026, 9, 2, 17, 0, 0, 0, zone).toInstant().toEpochMilli(),
            parsed!!.epochMillis,
        )
    }

    @Test
    fun parsesFullWidthSlashDateAndRollsToNextYear() {
        val parsed = parser.parse("8／31まで", now)
        assertEquals(
            ZonedDateTime.of(2027, 8, 31, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            parsed!!.epochMillis,
        )
    }

    @Test
    fun parsesWeekdayAsNextOccurrence() {
        val parsed = parser.parse("金曜までに返信", now)
        assertEquals(
            ZonedDateTime.of(2026, 9, 4, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            parsed!!.epochMillis,
        )
    }
}

