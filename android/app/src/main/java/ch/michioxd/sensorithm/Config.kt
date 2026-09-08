package ch.michioxd.sensorithm

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREFS_NAME = "SensorithmPrefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveString(context: Context, key: String, value: String) {
        getPrefs(context).edit().putString(key, value).apply()
    }

    fun getString(context: Context, key: String, defaultValue: String): String {
        return getPrefs(context).getString(key, defaultValue) ?: defaultValue
    }

    fun saveInt(context: Context, key: String, value: Int) {
        getPrefs(context).edit().putInt(key, value).apply()
    }

    fun getInt(context: Context, key: String, defaultValue: Int): Int {
        return getPrefs(context).getInt(key, defaultValue)
    }

    fun saveBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
        return getPrefs(context).getBoolean(key, defaultValue)
    }
}
