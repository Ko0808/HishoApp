package app.hisho.intelligence

import android.content.Context

class FilterPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("smart_filter", Context.MODE_PRIVATE)
    fun value(key: String) = prefs.getString(key, "").orEmpty()
    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun saveAll(values: Map<String, String>) {
        val edit = prefs.edit()
        values.forEach { (key, value) -> edit.putString(key, value) }
        edit.apply()
    }
    private fun lines(key: String) = value(key).lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
    fun rules() = FilterRules.Rules(lines("excluded_words"), lines("excluded_sources"), lines("forced_words"), lines("forced_sources"))
}
