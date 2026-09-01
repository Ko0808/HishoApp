package app.hisho.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

object NotificationNormalizer {
    fun normalize(sbn: StatusBarNotification): NormalizedNotification {
        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val title = extras.text(Notification.EXTRA_CONVERSATION_TITLE)
            .ifBlank { extras.text(Notification.EXTRA_TITLE) }
        val body = extras.text(Notification.EXTRA_BIG_TEXT)
            .ifBlank { extras.text(Notification.EXTRA_TEXT) }
            .ifBlank { messagingText(extras) }

        return NormalizedNotification(
            packageName = sbn.packageName,
            notificationId = sbn.id,
            notificationKey = sbn.key.orEmpty(),
            title = title.clean(),
            text = body.clean(),
            subText = extras.text(Notification.EXTRA_SUB_TEXT).clean(),
            channelId = sbn.notification.channelId,
            postedAtEpochMillis = sbn.postTime,
        )
    }

    private fun Bundle.text(key: String): String = getCharSequence(key)?.toString().orEmpty()

    private fun messagingText(extras: Bundle): String {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty()
        return messages.lastOrNull()
            ?.let { it as? Bundle }
            ?.getCharSequence("text")
            ?.toString()
            .orEmpty()
    }

    private fun String.clean(): String =
        replace(Regex("\\s+"), " ").trim().take(MAX_FIELD_LENGTH)

    private const val MAX_FIELD_LENGTH = 8_192
}

