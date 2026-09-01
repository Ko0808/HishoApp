package app.hisho.intelligence

import app.hisho.capture.NormalizedNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LocalTaskProcessorTest {
    private val processor = LocalTaskProcessor(JapaneseDeadlineParser(ZoneId.of("Asia/Tokyo")))

    @Test
    fun requestIsCommunicationTaskWithSoftDeadline() {
        val result = processor.process(notification("確認して返信お願いします"))
        assertTrue(result.isCandidate)
        assertEquals(TaskCategory.COMMUNICATION, result.category)
        assertEquals(EffortBucket.S, result.effort)
        assertEquals(DeadlineType.SOFT, result.deadlineType)
    }

    @Test
    fun explicitDateCreatesHardDeadline() {
        val result = processor.process(notification("9月3日までに資料を提出してください"))
        assertTrue(result.isCandidate)
        assertEquals(DeadlineType.HARD, result.deadlineType)
        assertEquals(TaskCategory.DOCUMENT, result.category)
    }

    @Test
    fun obviousPromotionWithoutTaskSignalIsIgnored() {
        val result = processor.process(notification("本日限定タイムセール開催中"))
        assertFalse(result.isCandidate)
        assertEquals(DeadlineType.NONE, result.deadlineType)
    }

    @Test
    fun ambiguousMessageFallsBackToCandidate() {
        assertTrue(processor.process(notification("先日の件です")).isCandidate)
    }

    private fun notification(text: String) = NormalizedNotification(
        packageName = "com.Slack",
        notificationId = 1,
        notificationKey = "key-$text",
        title = "message",
        text = text,
        subText = "",
        channelId = "dm",
        postedAtEpochMillis = 1_788_220_800_000,
    )
}

