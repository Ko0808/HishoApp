package app.hisho.intelligence

import java.text.Normalizer
import java.util.Locale

object FilterRules {
    enum class Decision { EXCLUDE, FORCE, AI }
    data class Rules(val excludedWords: List<String>, val excludedSources: List<String>, val forcedWords: List<String>, val forcedSources: List<String>)
    fun decide(rules: Rules, source: String, title: String, body: String): Decision {
        val content = normalize("$title\n$body")
        fun matches(words: List<String>) = words.any { it.isNotBlank() && content.contains(normalize(it.trim())) }
        if (rules.excludedSources.any { it.trim() == source } || matches(rules.excludedWords)) return Decision.EXCLUDE
        if (rules.forcedSources.any { it.trim() == source } || matches(rules.forcedWords)) return Decision.FORCE
        return Decision.AI
    }
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun explanation(rules: Rules, source: String, title: String, body: String): String {
        val content = normalize("$title\n$body")
        fun word(words: List<String>) = words.firstOrNull { it.isNotBlank() && content.contains(normalize(it.trim())) }
        return when (decide(rules, source, title, body)) {
            Decision.EXCLUDE -> if (source in rules.excludedSources) "除外通知元: $source" else "除外ワード: ${word(rules.excludedWords)}"
            Decision.FORCE -> if (source in rules.forcedSources) "最優先通知元: $source" else "最優先ワード: ${word(rules.forcedWords)}"
            Decision.AI -> "ユーザールール未一致"
        }
    }
}
