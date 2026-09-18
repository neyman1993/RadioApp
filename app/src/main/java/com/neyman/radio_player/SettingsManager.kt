package com.neyman.radio_player

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONArray

class SettingsManager(context: Context) {
    private val sp = context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)

    var recFolderUri: String
        get() = sp.getString("rec_folder", "") ?: ""
        set(value) = sp.edit().putString("rec_folder", value).apply()

    var recFormat: String
        get() = sp.getString("rec_format", ".mp3") ?: ".mp3"
        set(value) = sp.edit().putString("rec_format", value).apply()
        
    var hideDuplicates: Boolean
        get() = sp.getBoolean("hide_duplicates", false)
        set(value) = sp.edit().putBoolean("hide_duplicates", value).apply()
        
    var minBitrate: Int
        get() = sp.getInt("min_bitrate", 0)
        set(value) = sp.edit().putInt("min_bitrate", value).apply()

    // НОВОЕ: Настройка буферизации
    var bufferSeconds: Int
        get() = sp.getInt("buffer_seconds", 5)
        set(value) = sp.edit().putInt("buffer_seconds", value).apply()

    fun applySettings() {
        val lang = sp.getString("app_lang", "system")
        if (lang != "system") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
        when (sp.getString("app_theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun saveLanguage(lang: String) = sp.edit().putString("app_lang", lang).apply()
    fun getLanguage(): String = sp.getString("app_lang", "system") ?: "system"
    
    fun saveTheme(theme: String) = sp.edit().putString("app_theme", theme).apply()
    fun getTheme(): String = sp.getString("app_theme", "system") ?: "system"

    fun loadFavorites(): ArrayList<Station> {
        val list = ArrayList<Station>()
        val raw = sp.getString("favorites_json", null) ?: return list
        try { 
            val arr = JSONArray(raw)
            for(i in 0 until arr.length()) list.add(Station.fromJson(arr.getJSONObject(i))) 
        } catch (e: Exception) {}
        return list
    }

    fun saveFavorites(favorites: List<Station>) {
        val arr = JSONArray()
        favorites.forEach { arr.put(it.toJson()) }
        sp.edit().putString("favorites_json", arr.toString()).apply()
    }
}