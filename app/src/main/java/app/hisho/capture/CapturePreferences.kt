package app.hisho.capture

import android.content.Context

class CapturePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE)

    fun isEnabled(packageName: String): Boolean =
        preferences.getBoolean(packageName, packageName in DEFAULT_PACKAGES)

    fun setEnabled(packageName: String, enabled: Boolean) {
        preferences.edit().putBoolean(packageName, enabled).apply()
    }

    companion object {
        val SUPPORTED_PACKAGES = linkedMapOf(
            "com.google.android.gm" to "Gmail",
            "com.Slack" to "Slack",
            "com.discord" to "Discord",
            "jp.naver.line.android" to "LINE",
        )
        private val DEFAULT_PACKAGES = SUPPORTED_PACKAGES.keys
    }
}

