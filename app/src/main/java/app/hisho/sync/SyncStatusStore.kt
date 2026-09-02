package app.hisho.sync

import android.content.Context

class SyncStatusStore(context: Context) {
    data class Snapshot(val state: String, val updatedAt: Long, val detail: String?)

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun markRunning() = save("RUNNING")
    fun markSuccess() = save("SUCCESS")
    fun markWaiting() = save("WAITING", "未処理のタスクがあります")
    fun markAuthRequired() = save("AUTH_REQUIRED", "Googleアカウントを再接続してください")
    fun markNetworkError() = save("NETWORK_ERROR", "通信できませんでした。自動で再試行します")
    fun markApiError(service: String, status: Int) = save("API_ERROR", "$service API: HTTP $status")

    fun snapshot(): Snapshot = Snapshot(
        state = preferences.getString(KEY_STATE, "NEVER") ?: "NEVER",
        updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
        detail = preferences.getString(KEY_DETAIL, null),
    )

    private fun save(state: String, detail: String? = null) {
        preferences.edit()
            .putString(KEY_STATE, state)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply {
                if (detail == null) remove(KEY_DETAIL) else putString(KEY_DETAIL, detail)
            }
            .apply()
    }

    private companion object {
        const val FILE_NAME = "hisho_sync_status"
        const val KEY_STATE = "state"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_DETAIL = "detail"
    }
}
