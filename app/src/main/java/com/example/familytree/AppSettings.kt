package com.example.familytree

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** সেটিংস: থিম, স্ত্রীর নাম দেখানো, ট্রির ধরন, লুকানো নামের তালিকা। SharedPreferences-এ সংরক্ষিত থাকে */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "system" | "day" | "night" */
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set

    var showWives by mutableStateOf(prefs.getBoolean("showWives", true))
        private set

    /** "top" = উপর থেকে নিচে, "left" = বাম থেকে ডানে, "list" = তালিকা */
    var layout by mutableStateOf(prefs.getString("layout", "top") ?: "top")
        private set

    /** শো/হাইড: যাদের লুকানো হয়েছে (তাদের ও তাদের নিচের সবার নাম দেখানো হয় না) */
    var hidden by mutableStateOf(
        prefs.getStringSet("hidden", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    )
        private set

    fun updateTheme(v: String) { theme = v; prefs.edit().putString("theme", v).apply() }
    fun updateShowWives(v: Boolean) { showWives = v; prefs.edit().putBoolean("showWives", v).apply() }
    fun updateLayout(v: String) { layout = v; prefs.edit().putString("layout", v).apply() }

    fun toggleHidden(id: Long) {
        hidden = if (id in hidden) hidden - id else hidden + id
        saveHidden()
    }

    fun clearHidden() {
        hidden = emptySet()
        saveHidden()
    }

    private fun saveHidden() {
        prefs.edit().putStringSet("hidden", hidden.map { it.toString() }.toSet()).apply()
    }
}
