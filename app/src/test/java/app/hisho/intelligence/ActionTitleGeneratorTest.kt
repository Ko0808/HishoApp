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
        assertEquals("先日の件を確認する", ActionTitleGenerator.generate("message", "先日の件です"))
    }

    @Test
    fun chatQuestionUsesSenderAndReplyAction() {
        assertEquals(
            "田中さんへ返信する",
            ActionTitleGenerator.generate("田中さん", "明日の会議に参加できますか？", "jp.naver.line.android"),
        )
    }

    @Test
    fun removesChatSenderPrefix() {
        assertEquals(
            "見積書を送る",
            ActionTitleGenerator.generate("営業", "佐藤: 見積書を送ってください", "com.Slack"),
        )
    }

    @Test
    fun removesMailReplyPrefix() {
        assertEquals(
            "契約書を確認する",
            ActionTitleGenerator.generate("山田", "Re: 契約書を確認してください", "com.google.android.gm"),
        )
    }
}
