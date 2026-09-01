package app.hisho.auth

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class GoogleTasksAuthorization(private val activity: Activity) {
    interface Callback {
        fun onAuthorized(accessToken: String)
        fun onResolutionRequired(sender: IntentSender)
        fun onError(message: String)
    }

    fun authorize(callback: Callback) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(REQUIRED_SCOPES.map(::Scope))
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result -> deliver(result, callback) }
            .addOnFailureListener { error ->
                callback.onError(error.localizedMessage ?: error.javaClass.simpleName)
            }
    }

    fun resultFromIntent(data: Intent?): AuthorizationResult? {
        if (data == null) return null
        return runCatching {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        }.getOrNull()
    }

    private fun deliver(result: AuthorizationResult, callback: Callback) {
        val pendingIntent = result.pendingIntent
        val accessToken = result.accessToken
        when {
            result.hasResolution() && pendingIntent != null -> {
                callback.onResolutionRequired(pendingIntent.intentSender)
            }
            !accessToken.isNullOrBlank() -> callback.onAuthorized(accessToken)
            else -> callback.onError("Google Tasksのアクセストークンを取得できませんでした")
        }
    }

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"
        const val CALENDAR_EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events"
        const val CALENDAR_FREEBUSY_SCOPE = "https://www.googleapis.com/auth/calendar.events.freebusy"
        val REQUIRED_SCOPES = listOf(TASKS_SCOPE, CALENDAR_EVENTS_SCOPE, CALENDAR_FREEBUSY_SCOPE)
        const val REQUEST_CODE = 4107
    }
}
