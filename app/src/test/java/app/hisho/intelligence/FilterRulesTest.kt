package app.hisho.intelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterRulesTest {
    private val empty = FilterRules.Rules(emptyList(), emptyList(), emptyList(), emptyList())
    @Test fun unmatchedRequiresAi() {
        assertEquals(FilterRules.Decision.AI, FilterRules.decide(empty, "mail", "", "hello"))
    }
    @Test fun excludeWinsOverForce() {
        val rules = empty.copy(excludedWords = listOf("広告"), forcedWords = listOf("返信"))
        assertEquals(FilterRules.Decision.EXCLUDE, FilterRules.decide(rules, "mail", "広告", "返信してください"))
    }
    @Test fun sourceExclusionWinsOverForcedWord() {
        val rules = empty.copy(excludedSources = listOf("mail"), forcedWords = listOf("返信"))
        assertEquals(FilterRules.Decision.EXCLUDE, FilterRules.decide(rules, "mail", "返信", ""))
    }
    @Test fun wordExclusionWinsOverForcedSource() {
        val rules = empty.copy(excludedWords = listOf("広告"), forcedSources = listOf("mail"))
        assertEquals(FilterRules.Decision.EXCLUDE, FilterRules.decide(rules, "mail", "広告", ""))
    }
    @Test fun normalizesWidthAndCase() {
        val rules = empty.copy(excludedWords = listOf("SALE"))
        assertEquals(FilterRules.Decision.EXCLUDE, FilterRules.decide(rules, "mail", "ｓａｌｅ情報", ""))
    }
    @Test fun blankRuleDoesNotExcludeEverything() {
        assertEquals(FilterRules.Decision.AI, FilterRules.decide(empty.copy(excludedWords = listOf(" ")), "mail", "test", ""))
    }
    @Test fun matchesBody() {
        assertEquals(FilterRules.Decision.FORCE, FilterRules.decide(empty.copy(forcedWords = listOf("提出")), "mail", "お知らせ", "提出してください"))
    }
    @Test fun sourceIsExactMatch() {
        assertEquals(FilterRules.Decision.AI, FilterRules.decide(empty.copy(excludedSources = listOf("mail")), "mail.other", "test", ""))
    }
}
