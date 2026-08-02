package com.gitdroidstore.data

import android.content.Context
import com.gitdroidstore.StoreConfig

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var githubUser: String
        get() = prefs.getString("github_user", StoreConfig.OFFICIAL_GITHUB_OWNER)
            ?: StoreConfig.OFFICIAL_GITHUB_OWNER
        set(value) = prefs.edit().putString("github_user", value.trim()).apply()
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value.trim()).apply()
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(value) = prefs.edit().putBoolean("auto_check", value).apply()
}
