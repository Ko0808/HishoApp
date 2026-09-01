package app.hisho.capture

data class NormalizedNotification(
    val packageName: String,
    val notificationId: Int,
    val notificationKey: String,
    val title: String,
    val text: String,
    val subText: String,
    val channelId: String?,
    val postedAtEpochMillis: Long,
) {
    val sourceCategory: String
        get() = when (packageName) {
            "com.google.android.gm" -> "email"
            "com.Slack" -> "chat"
            "com.discord" -> "chat"
            "jp.naver.line.android" -> "chat"
            else -> "other"
        }
}

