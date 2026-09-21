package com.neyman.radio_player

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)

    var hideDuplicates: Boolean
        get() = prefs.getBoolean("hide_duplicates", false)
        set(value) = prefs.edit().putBoolean("hide_duplicates", value).apply()

    var minBitrate: Int
        get() = prefs.getInt("min_bitrate", 0)
        set(value) = prefs.edit().putInt("min_bitrate", value).apply()

    var bufferSeconds: Int
        get() = prefs.getInt("buffer_seconds", 5)
        set(value) = prefs.edit().putInt("buffer_seconds", value).apply()

    var recFormat: String
        get() = prefs.getString("rec_format", ".mp3") ?: ".mp3"
        set(value) = prefs.edit().putString("rec_format", value).apply()

    var recFolderUri: String
        get() = prefs.getString("rec_folder_uri", "") ?: ""
        set(value) = prefs.edit().putString("rec_folder_uri", value).apply()

    var autoplayAtStart: Boolean // Новый параметр для автовоспроизведения
        get() = prefs.getBoolean("autoplay_at_start", false)
        set(value) = prefs.edit().putBoolean("autoplay_at_start", value).apply()

    fun getTheme(): String = prefs.getString("app_theme", "system") ?: "system"
    fun saveTheme(theme: String) = prefs.edit().putString("app_theme", theme).apply()

    fun getLanguage(): String = prefs.getString("app_language", "system") ?: "system"
    fun saveLanguage(lang: String) = prefs.edit().putString("app_language", lang).apply()

    fun applySettings() {
        // Здесь можно добавить логику применения темы, если нужно
    }

    fun saveFavorites(favorites: List<Station>) {
        val favStrings = favorites.map { "${it.name}|${it.url}|${it.country}" }.toSet()
        prefs.edit().putStringSet("favorites_set", favStrings).apply()
    }

    fun loadFavorites(): ArrayList<Station> {
        val favSet = prefs.getStringSet("favorites_set", setOf()) ?: setOf()
        val favList = ArrayList<Station>()
        for (str in favSet) {
            val parts = str.split("|")
            if (parts.size >= 3) {
                favList.add(Station(parts[0], parts[1], parts[2]))
            } else if (parts.size >= 2) {
                favList.add(Station(parts[0], parts[1], ""))
            }
        }
        return favList
    }

    // Сохранение и загрузка последней станции
    fun saveLastStation(station: Station) {
        prefs.edit()
            .putString("last_station_name", station.name)
            .putString("last_station_url", station.url)
            .putString("last_station_country", station.country)
            .apply()
    }

    fun getLastStation(): Station? {
        val name = prefs.getString("last_station_name", null)
        val url = prefs.getString("last_station_url", null)
        val country = prefs.getString("last_station_country", "") ?: ""
        if (name != null && url != null) {
            return Station(name, url, country)
        }
        return null
    }
}