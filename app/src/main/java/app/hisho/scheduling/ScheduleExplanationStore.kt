package app.hisho.scheduling

import android.content.Context

/** Records only scheduling rules and timestamps, never task or calendar content. */
class ScheduleExplanationStore(context: Context) {
    private val preferences = context.getSharedPreferences("schedule_explanations", Context.MODE_PRIVATE)

    fun save(eventId: String, start: Long, end: Long, explanation: String) {
        preferences.edit().putString(eventId, "$start|$end|$explanation").apply()
    }

    fun read(eventId: String, start: Long, end: Long): String {
        val record = preferences.getString(eventId, null)
            ?: return "この枠は配置理由の記録がありません（旧バージョンでの作成など）。"
        val prefix = "$start|$end|"
        return if (record.startsWith(prefix)) record.removePrefix(prefix)
        else "Calendarで時刻が変更されています。変更後の時刻を選んだ理由はHishoでは判断できません。"
    }
}
