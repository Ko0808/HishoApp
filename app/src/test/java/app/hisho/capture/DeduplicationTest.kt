package app.hisho.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeduplicationTest {
    @Test
    fun identicalNotificationInSameWindowHasSameKey() {
        assertEquals(Deduplication.key(notification()), Deduplication.key(notification()))
    }

    @Test
    fun notificationUpdateWithSameSystemKeyIsDeduplicated() {
        assertEquals(
            Deduplication.key(notification(text = "original")),
            Deduplication.key(notification(text = "updated")),
        )
    }

    @Test
    fun fallbackKeyIncludesContentWhenSystemKeyIsMissing() {
        assertNotEquals(
            Deduplication.key(notification(text = "original", key = "")),
            Deduplication.key(notification(text = "updated", key = "")),
        )
    }

    private fun notification(text: String = "please reply", key: String = "key") = NormalizedNotification(
        packageName = "com.Slack",
        notificationId = 7,
        notificationKey = key,
        title = "sender",
        text = text,
        subText = "",
        channelId = "dm",
        postedAtEpochMillis = 1_800_000,
    )
}
