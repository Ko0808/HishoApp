package app.hisho.sync

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object TaskDueDate {
    fun format(epoch: Long?, zone: ZoneId = ZoneId.systemDefault()): String? = epoch?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toString()
    }
}
