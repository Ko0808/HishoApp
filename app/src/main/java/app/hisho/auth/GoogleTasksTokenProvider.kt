package app.hisho.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class GoogleTasksTokenProvider(private val context: Context) {
    /** Returns null only when fresh user interaction is required. */
    suspend fun accessToken(): String? = suspendCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleTasksAuthorization.REQUIRED_SCOPES.map(::Scope))
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val token = result.accessToken
                when {
                    result.hasResolution() -> {
                        EncryptedAuthStore(context).clear()
                        continuation.resume(null)
                    }
                    !token.isNullOrBlank() -> {
                        EncryptedAuthStore(context).saveAccessToken(token)
                        continuation.resume(token)
                    }
                    else -> continuation.resumeWithException(
                        IOException("Google authorization returned no token"),
                    )
                }
            }
            .addOnFailureListener { error ->
                continuation.resumeWithException(IOException("Google authorization failed", error))
            }
    }
}
