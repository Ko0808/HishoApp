package app.hisho.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTitleGeneratorTest {
    @Test
    fun convertsLongPoliteRequestToAction() {
        assertEquals(
            "先日の資料を確認して返信する",
            ActionTitleGenerator.generate(
                "田中さん",
                "明日の17時までに先日の資料を確認して返信していただけますでしょうか",
            ),
        )
    }

    @Test
    fun preservesFullTextOutsideTitleLimitOnlyInNotes() {
        val title = ActionTitleGenerator.generate("message", "あ".repeat(100) + "を確認してください")
        assertTrue(title.length <= 60)
        assertTrue(title.endsWith("確認する") || title.length == 60)
    }

    @Test
    fun ambiguousMessageBecomesExplicitCheckAction() {
        assertEquals("先日の件ですを確認する", ActionTitleGenerator.generate("message", "先日の件です"))
    }
}
