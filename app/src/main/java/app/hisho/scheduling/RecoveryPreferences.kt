package app.hisho.scheduling

import android.content.Context

class RecoveryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    private companion object {
        const val FILE_NAME = "hisho_recovery"
        const val KEY_ENABLED = "enabled"
    }
}
