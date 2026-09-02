package app.hisho.scheduling

import android.content.Context

class RecoveryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    val maximumAttempts: Int get() = preferences.getInt(KEY_MAXIMUM_ATTEMPTS, 3)

    fun cycleMaximumAttempts() {
        val presets = listOf(1, 3, 5)
        val index = presets.indexOf(maximumAttempts).coerceAtLeast(0)
        preferences.edit().putInt(KEY_MAXIMUM_ATTEMPTS, presets[(index + 1) % presets.size]).apply()
    }

    private companion object {
        const val FILE_NAME = "hisho_recovery"
        const val KEY_ENABLED = "enabled"
        const val KEY_MAXIMUM_ATTEMPTS = "maximum_attempts"
    }
}
