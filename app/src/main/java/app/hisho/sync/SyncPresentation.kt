package app.hisho.sync

object SyncPresentation {
    fun label(state: String) = when (state) {
        "QUEUED" -> "同期の開始待ち"
        "RUNNING" -> "同期中"
        "SUCCESS" -> "同期完了"
        "REVIEW_REQUIRED" -> "同期完了・確認が必要"
        "WAITING" -> "続きの処理を待っています"
        "AUTH_REQUIRED" -> "Googleへの再接続が必要"
        "NETWORK_ERROR" -> "通信エラー・自動再試行待ち"
        "API_ERROR" -> "APIエラー・自動再試行待ち"
        "INTERRUPTED" -> "同期が中断されました"
        "ERROR" -> "同期エラー"
        else -> "まだ同期していません"
    }
    fun busy(state: String) = state in setOf("QUEUED", "RUNNING")
}
