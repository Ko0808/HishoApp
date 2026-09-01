package app.hisho.intelligence

import app.hisho.capture.NormalizedNotification

class LocalTaskProcessor(
    private val deadlineParser: JapaneseDeadlineParser = JapaneseDeadlineParser(),
) {
    fun process(notification: NormalizedNotification): TaskMetadata {
        val text = "${notification.title} ${notification.text} ${notification.subText}".trim()
        val positiveSignals = TASK_SIGNALS.filter { text.contains(it, ignoreCase = true) }
        val promotionalSignals = PROMOTIONAL_SIGNALS.filter { text.contains(it, ignoreCase = true) }
        val deadline = deadlineParser.parse(text)
        val question = text.contains('?') || text.contains('？')
        val explicitTask = positiveSignals.isNotEmpty() || deadline != null || question
        val clearlyPromotional = promotionalSignals.isNotEmpty() && !explicitTask
        val candidate = !clearlyPromotional

        return TaskMetadata(
            isCandidate = candidate,
            candidateReason = when {
                clearlyPromotional -> "promotional:${promotionalSignals.first()}"
                deadline != null -> "deadline:${deadline.matchedExpression}"
                positiveSignals.isNotEmpty() -> "signal:${positiveSignals.first()}"
                question -> "question"
                else -> "recall_fallback"
            },
            deadlineEpochMillis = deadline?.epochMillis ?: if (candidate) {
                notification.postedAtEpochMillis + SOFT_DEADLINE_MILLIS
            } else null,
            deadlineType = when {
                deadline != null -> DeadlineType.HARD
                candidate -> DeadlineType.SOFT
                else -> DeadlineType.NONE
            },
            effort = estimateEffort(text),
            priority = estimatePriority(text, deadline != null),
            category = categorize(text),
        )
    }

    private fun estimateEffort(text: String): EffortBucket = when {
        text.containsAny("一言", "確認だけ", "承認", "リアクション") -> EffortBucket.XS
        text.containsAny("返信", "返事", "送って", "連絡", "確認", "予約") -> EffortBucket.S
        text.containsAny("資料作成", "企画書", "レポート", "実装", "分析", "調査") -> EffortBucket.M
        text.containsAny("設計", "プレゼン作成", "動画編集") -> EffortBucket.L
        else -> EffortBucket.S
    }

    private fun estimatePriority(text: String, hasDeadline: Boolean): Priority = when {
        text.containsAny("至急", "緊急", "急ぎ", "本日中", "今日中") -> Priority.HIGH
        hasDeadline -> Priority.NORMAL
        text.containsAny("参考", "FYI", "お知らせ") -> Priority.LOW
        else -> Priority.NORMAL
    }

    private fun categorize(text: String): TaskCategory = when {
        text.containsAny("返信", "返事", "連絡", "送って", "メール", "DM") -> TaskCategory.COMMUNICATION
        text.containsAny("資料", "企画書", "レポート", "提出", "文書") -> TaskCategory.DOCUMENT
        text.containsAny("会議", "面談", "ミーティング", "MTG", "予約") -> TaskCategory.MEETING
        text.containsAny("申請", "支払", "領収書", "経費", "登録") -> TaskCategory.ADMIN
        else -> TaskCategory.OTHER
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private companion object {
        val TASK_SIGNALS = listOf(
            "お願いします", "お願い", "確認", "返信", "返事", "送って", "提出",
            "ありますか", "できますか", "してください", "対応", "期限", "締切", "まで",
        )
        val PROMOTIONAL_SIGNALS = listOf(
            "セール", "キャンペーン", "クーポン", "ポイントアップ", "おすすめ商品",
            "タイムセール", "広告", "プロモーション",
        )
        const val SOFT_DEADLINE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
