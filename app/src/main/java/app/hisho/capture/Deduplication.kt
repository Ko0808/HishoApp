package app.hisho.capture

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object Deduplication {
    fun key(notification: NormalizedNotification): String {
        if (notification.notificationKey.isNotBlank()) {
            return sha256("${notification.packageName}|${notification.notificationKey}")
        }

        val timeBucket = notification.postedAtEpochMillis / WINDOW_MILLIS
        val identity = buildString {
            append(notification.packageName)
            append('|')
            append(notification.notificationId)
            append('|')
            append(notification.title.lowercase())
            append('|')
            append(notification.text.lowercase())
            append('|')
            append(timeBucket)
        }
        return sha256(identity)
    }

    fun contentHash(notification: NormalizedNotification): String =
        sha256("${notification.title.lowercase()}|${notification.text.lowercase()}")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val WINDOW_MILLIS = 5 * 60 * 1_000L
}
