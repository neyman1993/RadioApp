package com.neyman.radio_player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

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
        // Use JSON for reliable storage (StringSet can lose data on app updates)
        val arr = JSONArray()
        for (station in favorites) {
            arr.put(station.toJson())
        }
        prefs.edit().putString("favorites_json", arr.toString()).apply()
    }

    fun loadFavorites(): ArrayList<Station> {
        val favList = ArrayList<Station>()
        
        // Try loading from new JSON format first
        val jsonStr = prefs.getString("favorites_json", null)
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    favList.add(Station.fromJson(arr.getJSONObject(i)))
                }
                return favList
            } catch (e: Exception) { }
        }
        
        // Fallback: migrate from old StringSet format
        val favSet = prefs.getStringSet("favorites_set", null)
        if (favSet != null && favSet.isNotEmpty()) {
            for (str in favSet) {
                val parts = str.split("|")
                if (parts.size >= 3) {
                    favList.add(Station(parts[0], parts[1], parts[2]))
                } else if (parts.size >= 2) {
                    favList.add(Station(parts[0], parts[1], ""))
                }
            }
            // Migrate to new format and clean up old one
            saveFavorites(favList)
            prefs.edit().remove("favorites_set").apply()
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
