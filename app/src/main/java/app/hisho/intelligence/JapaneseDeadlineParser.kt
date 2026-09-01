package app.hisho.intelligence

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

class JapaneseDeadlineParser(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    data class ParsedDeadline(val epochMillis: Long, val matchedExpression: String)

    fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now(zoneId)): ParsedDeadline? {
        val normalized = normalize(text)
        val dateMatch = findDate(normalized, now.toLocalDate()) ?: return null
        val time = findTime(normalized) ?: defaultTime(normalized)
        val dateTime = LocalDateTime.of(dateMatch.first, time).atZone(zoneId)
        return ParsedDeadline(dateTime.toInstant().toEpochMilli(), dateMatch.second)
    }

    private fun findDate(text: String, today: LocalDate): Pair<LocalDate, String>? {
        RELATIVE_DATES.forEach { (expression, offset) ->
            if (text.contains(expression)) return today.plusDays(offset) to expression
        }
        if (text.contains("今週中") || text.contains("今週まで")) {
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)) to "今週中"
        }

        WEEKDAYS.forEach { (label, dayOfWeek) ->
            if (text.contains(label)) {
                return today.with(TemporalAdjusters.nextOrSame(dayOfWeek)) to label
            }
        }

        MONTH_DAY.find(text)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            return safeAnnualDate(today, month, day)?.let { it to match.value }
        }
        SLASH_DATE.find(text)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            return safeAnnualDate(today, month, day)?.let { it to match.value }
        }
        return null
    }

    private fun findTime(text: String): LocalTime? {
        JAPANESE_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour in 0..23 && minute in 0..59) return LocalTime.of(hour, minute)
        }
        COLON_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) return LocalTime.of(hour, minute)
        }
        return when {
            text.contains("朝まで") -> LocalTime.of(9, 0)
            text.contains("昼まで") -> LocalTime.of(12, 0)
            text.contains("夕方まで") -> LocalTime.of(18, 0)
            else -> null
        }
    }

    private fun defaultTime(text: String): LocalTime =
        if (text.contains("まで") || text.contains("締切") || text.contains("期限")) {
            LocalTime.of(23, 59)
        } else {
            LocalTime.of(18, 0)
        }

    private fun safeAnnualDate(today: LocalDate, month: Int, day: Int): LocalDate? = runCatching {
        var candidate = LocalDate.of(today.year, month, day)
        if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
        candidate
    }.getOrNull()

    private fun normalize(text: String): String = text
        .replace('／', '/')
        .replace('：', ':')
        .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3')
        .replace('４', '4').replace('５', '5').replace('６', '6').replace('７', '7')
        .replace('８', '8').replace('９', '9')

    private companion object {
        val RELATIVE_DATES = linkedMapOf("明後日" to 2L, "明日" to 1L, "今日" to 0L)
        val WEEKDAYS = linkedMapOf(
            "月曜" to DayOfWeek.MONDAY,
            "火曜" to DayOfWeek.TUESDAY,
            "水曜" to DayOfWeek.WEDNESDAY,
            "木曜" to DayOfWeek.THURSDAY,
            "金曜" to DayOfWeek.FRIDAY,
            "土曜" to DayOfWeek.SATURDAY,
            "日曜" to DayOfWeek.SUNDAY,
        )
        val MONTH_DAY = Regex("(?<!\\d)(\\d{1,2})月(\\d{1,2})日")
        val SLASH_DATE = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)")
        val JAPANESE_TIME = Regex("(?<!\\d)(\\d{1,2})時(?:([0-5]?\\d)分)?")
        val COLON_TIME = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)")
    }
}

