package app.hisho.auth

import android.content.Context
import android.util.Base64
import app.hisho.security.EncryptedPayloadStore

class EncryptedAuthStore(context: Context) {
    private val preferences = context.getSharedPreferences("google_authorization", Context.MODE_PRIVATE)

    fun saveAccessToken(token: String) {
        val encrypted = EncryptedPayloadStore().encrypt(token)
        preferences.edit()
            .putString(TOKEN, Base64.encodeToString(encrypted.cipherText, Base64.NO_WRAP))
            .putString(NONCE, Base64.encodeToString(encrypted.nonce, Base64.NO_WRAP))
            .putLong(UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun accessToken(): String? {
        val cipherText = preferences.getString(TOKEN, null) ?: return null
        val nonce = preferences.getString(NONCE, null) ?: return null
        return runCatching {
            EncryptedPayloadStore().decrypt(
                EncryptedPayloadStore.EncryptedValue(
                    Base64.decode(cipherText, Base64.NO_WRAP),
                    Base64.decode(nonce, Base64.NO_WRAP),
                ),
            )
        }.getOrNull()
    }

    fun isConnected(): Boolean = accessToken() != null

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val TOKEN = "access_token_cipher"
        const val NONCE = "access_token_nonce"
        const val UPDATED_AT = "updated_at"
    }
}

