package app.hisho.ai

import android.content.Context
import android.util.Base64
import app.hisho.security.EncryptedPayloadStore

class AiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("ai_scheduling", Context.MODE_PRIVATE)

    var consentGranted: Boolean
        get() = preferences.getBoolean(CONSENT, false)
        set(value) { preferences.edit().putBoolean(CONSENT, value).apply() }

    fun saveApiKey(apiKey: String) {
        val encrypted = EncryptedPayloadStore().encrypt(apiKey.trim())
        preferences.edit()
            .putString(API_KEY, Base64.encodeToString(encrypted.cipherText, Base64.NO_WRAP))
            .putString(API_KEY_NONCE, Base64.encodeToString(encrypted.nonce, Base64.NO_WRAP))
            .apply()
    }

    fun apiKey(): String? {
        val cipher = preferences.getString(API_KEY, null) ?: return null
        val nonce = preferences.getString(API_KEY_NONCE, null) ?: return null
        return runCatching {
            EncryptedPayloadStore().decrypt(
                EncryptedPayloadStore.EncryptedValue(
                    Base64.decode(cipher, Base64.NO_WRAP),
                    Base64.decode(nonce, Base64.NO_WRAP),
                ),
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clearApiKey() = preferences.edit().remove(API_KEY).remove(API_KEY_NONCE).apply()

    fun isReady(): Boolean = consentGranted && apiKey() != null

    var lastStatus: String
        get() = preferences.getString(LAST_STATUS, "未使用") ?: "未使用"
        set(value) { preferences.edit().putString(LAST_STATUS, value).apply() }

    private companion object {
        const val CONSENT = "consent"
        const val API_KEY = "api_key_cipher"
        const val API_KEY_NONCE = "api_key_nonce"
        const val LAST_STATUS = "last_status"
    }
}
