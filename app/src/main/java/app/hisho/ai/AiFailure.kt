package app.hisho.ai

/** Never persist server messages: they can contain request data or credentials. */
object AiFailure {
    fun http(status: Int, code: String?): String {
        val known = code?.takeIf { it in setOf("insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "slow_down", "invalid_api_key", "model_not_found", "rate_limit_exceeded", "unsupported_parameter", "invalid_value") }
        val help = when {
            known == "insufficient_quota" -> "APIの残高・利用上限を確認してください"
            known == "credit_balance_exhausted" -> "APIの前払い残高がありません。Billingを確認してください"
            known == "organization_spend_limit_exceeded" -> "組織の支出上限に達しています"
            known == "project_spend_limit_exceeded" -> "プロジェクトの支出上限に達しています"
            known == "organization_usage_limit_exceeded" -> "組織のAPI利用上限に達しています"
            status == 401 -> "APIキーの認証に失敗しました"
            status == 403 -> "APIキー・プロジェクトの利用権限を確認してください"
            status == 404 -> "モデルの利用可否を確認してください"
            status == 429 -> "APIの利用制限です。利用枠・リクエスト頻度を確認してください"
            status >= 500 -> "API側の一時的な障害です"
            else -> "APIリクエストが拒否されました"
        }
        return "HTTP $status${known?.let { " / $it" }.orEmpty()}: $help"
    }
}
