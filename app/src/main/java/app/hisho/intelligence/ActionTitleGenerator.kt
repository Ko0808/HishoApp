package app.hisho.intelligence

object ActionTitleGenerator {
    fun generate(
        notificationTitle: String,
        notificationBody: String,
        sourcePackage: String = "",
    ): String {
        val sender = cleanSender(notificationTitle)
        val source = serviceContent(notificationBody.ifBlank { notificationTitle }, sourcePackage)
        var title = source
            .replace(URL, "")
            .replace(LEADING_GREETING, "")
            .replace(DEADLINE_PHRASE, "")
            .replace("送ってください", "送る")
            .replace("送って", "送る")
            .replace(Regex("(?:して)?いただけますでしょうか[？?]?"), "する")
            .replace(Regex("(?:して)?いただけますか[？?]?"), "する")
            .replace(Regex("してください(?:ませんか)?[。．.!！]?"), "する")
            .replace(Regex("お願い(?:いた)?します[。．.!！]?"), "")
            .replace(Regex("返信して(?:ください)?"), "返信する")
            .replace(Regex("確認して(?:ください)?$"), "確認する")
            .replace(Regex("提出して(?:ください)?"), "提出する")
            .replace(Regex("連絡して(?:ください)?"), "連絡する")
            .replace(Regex("共有して(?:ください)?"), "共有する")
            .replace(Regex("対応して(?:ください)?"), "対応する")
            .replace(Regex("[「」『』\"“”]"), "")
            .replace(Regex("(?:です|でした|とのことです)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '。', '．', '.', '!', '！', '?', '？', '、', ',')

        if (title.isBlank()) title = fallback(notificationTitle)
        if (sourcePackage in CHAT_PACKAGES && isReplyExpected(source) && sender.isNotBlank()) {
            title = "${sender}へ返信する"
        }
        if (!looksActionable(title)) title = "${title}を確認する"
        return title.take(MAX_TITLE_LENGTH).trim()
    }

    private fun looksActionable(value: String): Boolean = ACTION_ENDINGS.any { value.endsWith(it) }

    private fun fallback(notificationTitle: String): String = notificationTitle
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "通知内容を確認する" }

    private fun serviceContent(value: String, sourcePackage: String): String {
        var result = value.trim()
        if (sourcePackage == GMAIL_PACKAGE) {
            result = result.replace(Regex("^(?:Re|Fwd|Fw):\\s*", RegexOption.IGNORE_CASE), "")
        }
        if (sourcePackage in CHAT_PACKAGES) {
            result = result.replace(Regex("^[^:：]{1,30}[:：]\\s*"), "")
        }
        return result
    }

    private fun cleanSender(value: String): String = value
        .substringBefore(" (")
        .substringBefore("（")
        .substringBefore(" • ")
        .replace(Regex("^[#@]"), "")
        .trim()
        .take(24)

    private fun isReplyExpected(value: String): Boolean =
        value.contains('?') || value.contains('？') || REPLY_SIGNALS.any { value.contains(it) }

    private const val MAX_TITLE_LENGTH = 60
    private val URL = Regex("https?://\\S+")
    private val LEADING_GREETING = Regex("^(?:お疲れさまです|お疲れ様です|こんにちは|こんばんは|お世話になっております)[、。\\s]*")
    private val DEADLINE_PHRASE = Regex(
        "(?:今日|本日|明日|明後日|今週中|今週|\\d{1,2}月\\d{1,2}日|\\d{1,2}/\\d{1,2})" +
            "(?:の)?(?:朝|昼|夕方|\\d{1,2}(?::\\d{2}|時(?:\\d{1,2}分)?))?までに?",
    )
    private val ACTION_ENDINGS = listOf(
        "する", "送る", "返す", "返信", "確認", "提出", "連絡", "共有", "回答",
        "予約", "支払う", "探す", "作る", "読む", "見る", "登録する", "対応する",
    )
    private const val GMAIL_PACKAGE = "com.google.android.gm"
    private val CHAT_PACKAGES = setOf("com.Slack", "com.discord", "jp.naver.line.android")
    private val REPLY_SIGNALS = listOf("できますか", "でしょうか", "教えて", "返信", "返事", "確認してください")
}
