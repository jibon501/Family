package com.example.familytree

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** সেটিংস: থিম, মেয়ে/স্ত্রীর নাম দেখানো, ট্রির ধরন। SharedPreferences-এ সংরক্ষিত থাকে */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "system" | "day" | "night" */
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set

    var showDaughters by mutableStateOf(prefs.getBoolean("showDaughters", true))
        private set

    var showWives by mutableStateOf(prefs.getBoolean("showWives", true))
        private set

    /** "top" = উপর থেকে নিচে, "left" = বাম থেকে ডানে, "list" = তালিকা */
    var layout by mutableStateOf(prefs.getString("layout", "top") ?: "top")
        private set

    fun updateTheme(v: String) { theme = v; prefs.edit().putString("theme", v).apply() }
    fun updateShowDaughters(v: Boolean) { showDaughters = v; prefs.edit().putBoolean("showDaughters", v).apply() }
    fun updateShowWives(v: Boolean) { showWives = v; prefs.edit().putBoolean("showWives", v).apply() }
    fun updateLayout(v: String) { layout = v; prefs.edit().putString("layout", v).apply() }
}
