package app.hisho.ai

import org.junit.Assert.*
import org.junit.Test

class AiFailureTest {
    @Test fun quotaIsDistinctFromRateLimit() {
        assertTrue(AiFailure.http(429, "insufficient_quota").contains("残高"))
        assertTrue(AiFailure.http(429, "rate_limit_exceeded").contains("頻度"))
        assertTrue(AiFailure.http(429, "credit_balance_exhausted").contains("前払い残高"))
        assertTrue(AiFailure.http(429, "project_spend_limit_exceeded").contains("プロジェクト"))
    }
    @Test fun unknownServerContentIsNeverShown() {
        assertFalse(AiFailure.http(401, "sk-secret").contains("sk-secret"))
        assertTrue(AiFailure.http(401, null).contains("認証"))
    }
}
