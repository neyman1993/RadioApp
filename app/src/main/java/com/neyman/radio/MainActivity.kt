package com.neyman.radio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null

    private val stations = ArrayList<Station>()
    private val countries = ArrayList<String>()
    private val favorites = ArrayList<Station>()
    private var currentPlaylist = ArrayList<Station>()
    private var currentStationIndex = -1

    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var searchInput: EditText
    private lateinit var stationNameText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var btnPlayPause: Button

    private var currentMode = "SEARCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFavoritesFromStorage()
        setupExoPlayer()

        // Главный контейнер (Вертикальный)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Вкладки
        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSearchTab = Button(this).apply { text = "Поиск" }
        val btnCountriesTab = Button(this).apply { text = "Страны" }
        val btnFavTab = Button(this).apply { text = "Избранное" }
        navLayout.addView(btnSearchTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnCountriesTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnFavTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Блок поиска (виден только на вкладке Поиск)
        val searchBox = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
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

        // Список (занимает всё свободное место посередине)
        val listView = ListView(this)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter

        // МИНИ-ПЛЕЕР (Закреплен в самом низу)
        val miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
        }

        stationNameText = TextView(this).apply {
            text = "Радио не выбрано"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
        }
        songInfoText = TextView(this).apply {
            text = "Метаданные появятся здесь..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
            isFocusable = true
        }
        
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        val btnPrev = Button(this).apply {
            text = "Пред"
            contentDescription = "Предыдущая станция"
            setOnClickListener { playPrev() }
        }
        btnPlayPause = Button(this).apply {
            text = "Плей"
            contentDescription = "Воспроизвести"
            setOnClickListener { togglePlayPause() }
        }
        val btnNext = Button(this).apply {
            text = "След"
            contentDescription = "Следующая станция"
            setOnClickListener { playNext() }
        }

        controlsLayout.addView(btnPrev)
        controlsLayout.addView(btnPlayPause)
        controlsLayout.addView(btnNext)

        miniPlayerLayout.addView(stationNameText)
        miniPlayerLayout.addView(songInfoText)
        miniPlayerLayout.addView(controlsLayout)

        // Собираем всё вместе
        mainLayout.addView(navLayout)
        mainLayout.addView(searchBox)
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout)

        setContentView(mainLayout)

        // Обработчики вкладок
        btnSearchTab.setOnClickListener {
            currentMode = "SEARCH"
            searchBox.visibility = View.VISIBLE
            updateList(stations.map { it.name })
            searchInput.requestFocus() // Фокус сразу на поле ввода
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
                playStation(position, list)
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

    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) "Пауза" else "Плей"
                btnPlayPause.contentDescription = if (isPlaying) "Пауза" else "Воспроизвести"
                updateMediaSessionState(isPlaying)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val songInfo = if (artist.isNotEmpty() && title.isNotEmpty()) "$artist - $title" else title
                
                if (songInfo.isNotEmpty()) {
                    songInfoText.text = songInfo
                    songInfoText.announceForAccessibility("Сейчас играет: $songInfo")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                stationNameText.text = "Ошибка воспроизведения"
                stationNameText.announceForAccessibility("Ошибка воспроизведения станции")
            }
        })

        // Инициализация MediaSession для жестов TalkBack и кнопок наушников
        mediaSession = MediaSessionCompat(this, "RadioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { exoPlayer?.play() }
                override fun onPause() { exoPlayer?.pause() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrev() }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrev(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun playStation(index: Int, playlist: List<Station>) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        
        currentPlaylist = ArrayList(playlist)
        currentStationIndex = index
        val station = currentPlaylist[index]

        stationNameText.text = station.name
        songInfoText.text = "Загрузка..."
        stationNameText.announceForAccessibility("Включаю: ${station.name}")

        val mediaItem = MediaItem.fromUri(station.url)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    private fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) exoPlayer?.pause()
        else exoPlayer?.play()
    }

    private fun playNext() {
        if (currentPlaylist.isNotEmpty()) {
            val nextIndex = (currentStationIndex + 1) % currentPlaylist.size
            playStation(nextIndex, currentPlaylist)
        }
    }

    private fun playPrev() {
        if (currentPlaylist.isNotEmpty()) {
            val prevIndex = if (currentStationIndex - 1 < 0) currentPlaylist.size - 1 else currentStationIndex - 1
            playStation(prevIndex, currentPlaylist)
        }
    }

    private fun searchStations(name: String) {
        stationNameText.announceForAccessibility("Поиск станций...")
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
                stationNameText.announceForAccessibility("Найдено станций: " + stations.size)
                updateList(stations.map { it.name })
            }
        }
    }

    private fun loadCountries() {
        stationNameText.announceForAccessibility("Загрузка списка стран...")
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
                stationNameText.announceForAccessibility("Стран загружено: " + countries.size)
                updateList(countries)
            }
        }
    }

    private fun loadStationsByCountry(country: String) {
        stationNameText.announceForAccessibility("Загрузка станций для страны " + country)
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
                stationNameText.announceForAccessibility(country + ": " + stations.size + " станций")
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
        mediaSession?.release()
        exoPlayer?.release()
    }
}
