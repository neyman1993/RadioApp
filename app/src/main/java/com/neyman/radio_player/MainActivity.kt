package com.neyman.radio_player

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

data class Station(val name: String, val url: String, val country: String) {
    fun toJson() = JSONObject().apply { put("name", name); put("url", url); put("country", country) }
    companion object {
        fun fromJson(j: JSONObject) = Station(j.optString("name"), j.optString("url"), j.optString("country"))
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var player: MediaController? = null

    private val stations = ArrayList<Station>()
    private val countries = ArrayList<String>()
    private val favorites = ArrayList<Station>()
    private var currentPlaylist = ArrayList<Station>()
    private var currentStationIndex = -1

    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var stationNameText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var btnPlayPause: Button

    private var currentMode = "SEARCH"
    
    private var isTr = false
    private var txtSearch = ""
    private var txtCountries = ""
    private var txtFav = ""
    private var txtFind = ""
    private var txtLoading = ""
    private var txtNotSelected = ""
    private var txtCancel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        isTr = Locale.getDefault().language == "tr"
        txtSearch = if (isTr) "Arama" else "Поиск"
        txtCountries = if (isTr) "Ülkeler" else "Страны"
        txtFav = if (isTr) "Favoriler" else "Избранное"
        txtFind = if (isTr) "Bul" else "Найти"
        txtLoading = if (isTr) "Yükleniyor..." else "Загрузка..."
        txtNotSelected = if (isTr) "Radyo seçilmedi" else "Радио не выбрано"
        txtCancel = if (isTr) "İptal" else "Отмена"
        
        val txtPrev = if (isTr) "Önceki" else "Пред"
        val txtNext = if (isTr) "Sonraki" else "След"

        loadFavoritesFromStorage()
        
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSearchTab = Button(this).apply { text = txtSearch }
        val btnCountriesTab = Button(this).apply { text = txtCountries }
        val btnFavTab = Button(this).apply { text = txtFav }
        navLayout.addView(btnSearchTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnCountriesTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnFavTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val listView = ListView(this)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter

        val miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
        }

        stationNameText = TextView(this).apply {
            text = txtNotSelected
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        songInfoText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
        }
        
        val controlsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnPrevBtn = Button(this).apply { text = txtPrev; setOnClickListener { playPrev() } }
        btnPlayPause = Button(this).apply { text = if(isTr) "Oynat" else "Плей"; setOnClickListener { togglePlayPause() } }
        val btnNextBtn = Button(this).apply { text = txtNext; setOnClickListener { playNext() } }
        
        controlsLayout.addView(btnPrevBtn)
        controlsLayout.addView(btnPlayPause)
        controlsLayout.addView(btnNextBtn)

        miniPlayerLayout.addView(stationNameText)
        miniPlayerLayout.addView(songInfoText)
        miniPlayerLayout.addView(controlsLayout)

        mainLayout.addView(navLayout)
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout)
        
        setContentView(mainLayout)

        btnSearchTab.setOnClickListener { showSearchDialog() }
        btnCountriesTab.setOnClickListener { currentMode = "COUNTRIES"; if (countries.isEmpty()) loadCountries() else updateList(countries) }
        btnFavTab.setOnClickListener { currentMode = "FAVORITES"; updateList(favorites.map { it.name }) }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") { loadStationsByCountry(countries[position].split(" (")[0]) } 
            else { playStation(position, if (currentMode == "FAVORITES") favorites else stations) }
        }
        
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") return@setOnItemLongClickListener false
            val list = if (currentMode == "FAVORITES") favorites else stations
            if (position in list.indices) {
                showStationMenu(list[position])
                true
            } else false
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            player = controllerFuture.get()
            setupPlayerListener()
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = txtSearch
            contentDescription = txtSearch
        }
        AlertDialog.Builder(this)
            .setTitle(txtSearch)
            .setView(input)
            .setPositiveButton(txtFind) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    currentMode = "SEARCH"
                    searchStations(q)
                }
            }
            .setNegativeButton(txtCancel, null)
            .show()
            
        input.requestFocus()
    }

    private fun setupPlayerListener() {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) (if(isTr) "Duraklat" else "Пауза") else (if(isTr) "Oynat" else "Плей")
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val info = if (artist.isNotEmpty() && title.isNotEmpty()) "$artist - $title" else title
                if (info.isNotEmpty()) {
                    songInfoText.text = info
                    songInfoText.announceForAccessibility(info)
                }
            }
        })
    }

    private fun playStation(index: Int, playlist: List<Station>) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        currentPlaylist = ArrayList(playlist)
        currentStationIndex = index
        val station = currentPlaylist[index]

        stationNameText.text = station.name
        songInfoText.text = "" 
        stationNameText.announceForAccessibility(if(isTr) "Çalınıyor: " else "Включаю: " + station.name)

        val meta = MediaMetadata.Builder().setTitle(station.name).setArtist(if(isTr) "Radyo Yayını" else "Радио эфир").build()
        player?.setMediaItem(MediaItem.Builder().setUri(station.url).setMediaMetadata(meta).build())
        player?.prepare()
        player?.play()
    }

    private fun togglePlayPause() { if (player?.isPlaying == true) player?.pause() else player?.play() }
    private fun playNext() { if (currentPlaylist.isNotEmpty()) playStation((currentStationIndex + 1) % currentPlaylist.size, currentPlaylist) }
    private fun playPrev() { if (currentPlaylist.isNotEmpty()) playStation(if (currentStationIndex - 1 < 0) currentPlaylist.size - 1 else currentStationIndex - 1, currentPlaylist) }

    private fun searchStations(name: String) {
        stationNameText.announceForAccessibility(txtLoading)
        thread {
            val res = fetchJson("https://all.api.radio-browser.info/json/stations/byname/" + URLEncoder.encode(name, "UTF-8") + "?limit=100")
            stations.clear()
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                val url = if (o.optString("url_resolved").isNotEmpty()) o.optString("url_resolved") else o.optString("url")
                if (url.isNotEmpty()) stations.add(Station.fromJson(o))
            }
            runOnUiThread { updateList(stations.map { it.name }) }
        }
    }

    private fun loadCountries() {
        thread {
            val res = fetchJson("https://all.api.radio-browser.info/json/countries")
            countries.clear()
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                if (o.optInt("stationcount") > 0) countries.add(o.optString("name") + " (" + o.optInt("stationcount") + ")")
            }
            countries.sort()
            runOnUiThread { updateList(countries) }
        }
    }

    private fun loadStationsByCountry(country: String) {
        stationNameText.announceForAccessibility(txtLoading)
        thread {
            val res = fetchJson("https://all.api.radio-browser.info/json/stations/bycountry/" + URLEncoder.encode(country, "UTF-8") + "?limit=100")
            stations.clear()
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                val url = if (o.optString("url_resolved").isNotEmpty()) o.optString("url_resolved") else o.optString("url")
                if (url.isNotEmpty()) stations.add(Station.fromJson(o))
            }
            runOnUiThread { currentMode = "SEARCH"; updateList(stations.map { it.name }) }
        }
    }

    private fun fetchJson(url: String): JSONArray {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=10000; readTimeout=10000; setRequestProperty("User-Agent", "radio_player") }
            JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) { JSONArray() }
    }

    private fun updateList(items: List<String>) { listAdapter.clear(); listAdapter.addAll(items); listAdapter.notifyDataSetChanged() }
    
    private fun showStationMenu(station: Station) {
        val isFav = favorites.any { it.url == station.url }
        val favOption = if (isFav) (if(isTr) "Favorilerden çıkar" else "Удалить из избранного") else (if(isTr) "Favorilere ekle" else "Добавить в избранное")
        val shareOption = if(isTr) "Bağlantıyı paylaş" else "Поделиться ссылкой"
        val infoOption = if(isTr) "İstasyon bilgisi" else "Информация о станции"
        
        val options = arrayOf(favOption, shareOption, infoOption)

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
            Toast.makeText(this, if(isTr) "Favorilerden çıkarıldı" else "Удалено из избранного", Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(station)
            Toast.makeText(this, if(isTr) "Favorilere eklendi" else "Добавлено в избранное", Toast.LENGTH_SHORT).show()
        }
        saveFavoritesToStorage()
        if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
    }

    private fun shareStation(station: Station) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, station.name)
            putExtra(Intent.EXTRA_TEXT, (if(isTr) "Dinle: " else "Слушай ") + station.name + ": " + station.url)
        }
        startActivity(Intent.createChooser(shareIntent, if(isTr) "İstasyonu paylaş" else "Поделиться станцией"))
    }

    private fun showStationInfo(station: Station) {
        val info = (if(isTr) "Adı: " else "Название: ") + station.name + "\n" +
                   (if(isTr) "Ülke: " else "Страна: ") + station.country + "\n" +
                   "URL: " + station.url
                   
        AlertDialog.Builder(this).setTitle(if(isTr) "Bilgi" else "Информация").setMessage(info).setPositiveButton("OK", null).show()
    }

    private fun saveFavoritesToStorage() {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray()
        favorites.forEach { arr.put(it.toJson()) }
        sp.edit().putString("favorites_json", arr.toString()).apply()
    }

    private fun loadFavoritesFromStorage() {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString("favorites_json", null) ?: return
        try { val arr = JSONArray(raw); favorites.clear(); for(i in 0 until arr.length()) favorites.add(Station.fromJson(arr.getJSONObject(i))) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}