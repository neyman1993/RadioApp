package com.neyman.radio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class Station(
    val name: String,
    val url: String,
    val country: String,
    val codec: String,
    val bitrate: Int,
    val tags: String,
    val homepage: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("country", country)
        put("codec", codec)
        put("bitrate", bitrate)
        put("tags", tags)
        put("homepage", homepage)
    }

    companion object {
        fun fromJson(json: JSONObject): Station = Station(
            json.optString("name", ""),
            json.optString("url", ""),
            json.optString("country", ""),
            json.optString("codec", ""),
            json.optInt("bitrate", 0),
            json.optString("tags", ""),
            json.optString("homepage", "")
        )
    }
}

class MainActivity : AppCompatActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private val stations = ArrayList<Station>()
    private val countries = ArrayList<String>()
    private val favorites = ArrayList<Station>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private var currentMode = "SEARCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFavoritesFromStorage()

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusText = TextView(this).apply {
            text = "Радио остановлено"
            textSize = 18f
            setPadding(0, 0, 0, 16)
            isFocusable = true
        }
        mainLayout.addView(statusText)

        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSearchTab = Button(this).apply { text = "Поиск" }
        val btnCountriesTab = Button(this).apply { text = "Страны" }
        val btnFavTab = Button(this).apply { text = "Избранное" }

        navLayout.addView(btnSearchTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnCountriesTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnFavTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mainLayout.addView(navLayout)

        val searchBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        searchInput = EditText(this).apply {
            hint = "Введите название станции"
            contentDescription = "Поле для ввода названия станции"
        }
        val btnDoSearch = Button(this).apply {
            text = "Найти"
            contentDescription = "Кнопка Найти станции"
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchBox.addView(btnDoSearch)
        mainLayout.addView(searchBox)

        val listView = ListView(this)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btnStop = Button(this).apply {
            text = "Остановить"
            contentDescription = "Остановить воспроизведение"
            setOnClickListener { stopAudio() }
        }
        mainLayout.addView(btnStop)

        setContentView(mainLayout)

        btnSearchTab.setOnClickListener {
            currentMode = "SEARCH"
            searchBox.visibility = View.VISIBLE
            updateList(stations.map { it.name })
        }

        btnCountriesTab.setOnClickListener {
            currentMode = "COUNTRIES"
            searchBox.visibility = View.GONE
            if (countries.isEmpty()) loadCountries() else updateList(countries)
        }

        btnFavTab.setOnClickListener {
            currentMode = "FAVORITES"
            searchBox.visibility = View.GONE
            updateList(favorites.map { it.name })
        }

        btnDoSearch.setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) searchStations(query)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") {
                val country = countries[position].split(" (")[0]
                loadStationsByCountry(country)
            } else {
                val list = if (currentMode == "FAVORITES") favorites else stations
                if (position in list.indices) playStation(list[position])
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") return@setOnItemLongClickListener false
            val list = if (currentMode == "FAVORITES") favorites else stations
            if (position in list.indices) {
                showStationMenu(list[position])
                true
            } else false
        }
    }

    private fun showStationMenu(station: Station) {
        val isFav = favorites.any { it.url == station.url }
        val favOption = if (isFav) "Удалить из избранного" else "Добавить в избранное"
        val options = arrayOf(favOption, "Поделиться ссылкой", "Информация о станции")

        AlertDialog.Builder(this)
            .setTitle(station.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavorite(station)
                    1 -> shareStation(station)
                    2 -> showStationInfo(station)
                }
            }.show()
    }

    private fun toggleFavorite(station: Station) {
        val index = favorites.indexOfFirst { it.url == station.url }
        if (index != -1) {
            favorites.removeAt(index)
            Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(station)
            Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
        }
        saveFavoritesToStorage()
        if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
    }

    private fun shareStation(station: Station) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, station.name)
            putExtra(Intent.EXTRA_TEXT, "Слушай " + station.name + ": " + station.url)
        }
        startActivity(Intent.createChooser(shareIntent, "Поделиться станцией"))
    }

    private fun showStationInfo(station: Station) {
        val info = "Название: " + station.name + "\n" +
                   "Страна: " + station.country + "\n" +
                   "Кодек: " + station.codec + "\n" +
                   "Битрейт: " + station.bitrate + " kbps\n" +
                   "Теги: " + station.tags
                   
        AlertDialog.Builder(this).setTitle("Информация").setMessage(info).setPositiveButton("ОК", null).show()
    }

    private fun playStation(station: Station) {
        setStatus("Подключение: " + station.name)
        stopAudio()

        thread {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build()
                    )
                    setDataSource(station.url)
                    setOnPreparedListener {
                        it.start()
                        runOnUiThread { setStatus("Играет: " + station.name) }
                    }
                    setOnErrorListener { _, _, _ ->
                        runOnUiThread { setStatus("Ошибка воспроизведения") }
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Не удалось открыть поток") }
            }
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        setStatus("Радио остановлено")
    }
    
    private fun setStatus(msg: String) {
        statusText.text = msg
        statusText.announceForAccessibility(msg)
    }

    private fun searchStations(name: String) {
        setStatus("Поиск станций...")
        thread {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = "https://all.api.radio-browser.info/json/stations/byname/" + encoded + "?limit=100"
            val result = fetchJson(url)
            stations.clear()
            for (i in 0 until result.length()) {
                val obj = result.getJSONObject(i)
                val streamUrl = if (obj.optString("url_resolved").isNotEmpty()) obj.optString("url_resolved") else obj.optString("url")
                if (streamUrl.isNotEmpty()) stations.add(Station.fromJson(obj))
            }
            runOnUiThread {
                setStatus("Найдено станций: " + stations.size)
                updateList(stations.map { it.name })
            }
        }
    }

    private fun loadCountries() {
        setStatus("Загрузка списка стран...")
        thread {
            val result = fetchJson("https://all.api.radio-browser.info/json/countries")
            countries.clear()
            for (i in 0 until result.length()) {
                val obj = result.getJSONObject(i)
                val cName = obj.optString("name")
                val count = obj.optInt("stationcount")
                if (cName.isNotEmpty() && count > 0) countries.add(cName + " (" + count + ")")
            }
            countries.sort()
            runOnUiThread {
                setStatus("Стран загружено: " + countries.size)
                updateList(countries)
            }
        }
    }

    private fun loadStationsByCountry(country: String) {
        setStatus("Загрузка станций для страны " + country)
        thread {
            val encoded = URLEncoder.encode(country, "UTF-8")
            val url = "https://all.api.radio-browser.info/json/stations/bycountry/" + encoded + "?limit=1000"
            val result = fetchJson(url)
            stations.clear()
            for (i in 0 until result.length()) {
                val obj = result.getJSONObject(i)
                val streamUrl = if (obj.optString("url_resolved").isNotEmpty()) obj.optString("url_resolved") else obj.optString("url")
                if (streamUrl.isNotEmpty()) stations.add(Station.fromJson(obj))
            }
            runOnUiThread {
                currentMode = "SEARCH"
                setStatus(country + ": " + stations.size + " станций")
                updateList(stations.map { it.name })
            }
        }
    }

    private fun fetchJson(urlString: String): JSONArray {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(text)
        } catch (e: Exception) { JSONArray() }
    }

    private fun updateList(items: List<String>) {
        listAdapter.clear()
        listAdapter.addAll(items)
        listAdapter.notifyDataSetChanged()
    }

    private fun saveFavoritesToStorage() {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        favorites.forEach { array.put(it.toJson()) }
        sp.edit().putString("favorites_json", array.toString()).apply()
    }

    private fun loadFavoritesFromStorage() {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString("favorites_json", null) ?: return
        try {
            val array = JSONArray(raw)
            favorites.clear()
            for (i in 0 until array.length()) {
                favorites.add(Station.fromJson(array.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }
}
