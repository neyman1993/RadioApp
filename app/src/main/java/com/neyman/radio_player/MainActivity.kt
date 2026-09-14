package com.neyman.radio_player

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
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
    private var currentSongMetadata = ""

    // Переменные таймера и записи
    private var sleepTimerHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var isRecording = false
    private var recordThread: Thread? = null

    // Настройки
    private var prefRecFolderUri: String = ""
    private var prefRecFormat: String = ".mp3"

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefRecFolderUri = uri.toString()
            getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).edit().putString("rec_folder", prefRecFolderUri).apply()
            Toast.makeText(this, getStr("Папка успешно выбрана", "Klasör başarıyla seçildi"), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applySavedSettings()
        isTr = Locale.getDefault().language == "tr"
        
        loadFavoritesFromStorage()
        
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Вкладки
        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSearchTab = Button(this).apply { text = getStr("Поиск", "Arama") }
        val btnCountriesTab = Button(this).apply { text = getStr("Страны", "Ülkeler") }
        val btnFavTab = Button(this).apply { text = getStr("Избранное", "Favoriler") }
        val btnSettingsTab = Button(this).apply { text = getStr("Настройки", "Ayarlar") }
        
        navLayout.addView(btnSearchTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnCountriesTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnFavTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navLayout.addView(btnSettingsTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Основные списки
        val listView = ListView(this)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter

        // Экран настроек
        val settingsScroll = ScrollView(this).apply { visibility = View.GONE }
        val settingsLayout = createSettingsLayout()
        settingsScroll.addView(settingsLayout)

        // Мини-плеер
        val miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
        }

        stationNameText = TextView(this).apply {
            text = getStr("Радио не выбрано", "Radyo seçilmedi")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            setOnClickListener { showMiniPlayerMenu() }
        }
        songInfoText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
        }
        
        val controlsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnPrevBtn = Button(this).apply { text = getStr("Пред", "Önceki"); setOnClickListener { playPrev() } }
        btnPlayPause = Button(this).apply { text = getStr("Плей", "Oynat"); setOnClickListener { togglePlayPause() } }
        val btnNextBtn = Button(this).apply { text = getStr("След", "Sonraki"); setOnClickListener { playNext() } }
        
        controlsLayout.addView(btnPrevBtn)
        controlsLayout.addView(btnPlayPause)
        controlsLayout.addView(btnNextBtn)

        miniPlayerLayout.addView(stationNameText)
        miniPlayerLayout.addView(songInfoText)
        miniPlayerLayout.addView(controlsLayout)

        mainLayout.addView(navLayout)
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout)
        
        setContentView(mainLayout)

        // Обработчики вкладок
        btnSearchTab.setOnClickListener {
            listView.visibility = View.VISIBLE
            settingsScroll.visibility = View.GONE
            showSearchDialog()
        }
        btnCountriesTab.setOnClickListener {
            currentMode = "COUNTRIES"
            listView.visibility = View.VISIBLE
            settingsScroll.visibility = View.GONE
            if (countries.isEmpty()) loadCountries() else updateList(countries)
        }
        btnFavTab.setOnClickListener {
            currentMode = "FAVORITES"
            listView.visibility = View.VISIBLE
            settingsScroll.visibility = View.GONE
            updateList(favorites.map { it.name })
        }
        btnSettingsTab.setOnClickListener {
            currentMode = "SETTINGS"
            listView.visibility = View.GONE
            settingsScroll.visibility = View.VISIBLE
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") { loadStationsByCountry(countries[position].split(" (")[0]) } 
            else { playStation(position, if (currentMode == "FAVORITES") favorites else stations) }
        }
        
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") return@setOnItemLongClickListener false
            val list = if (currentMode == "FAVORITES") favorites else stations
            if (position in list.indices) {
                showStationListMenu(list[position])
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

    private fun getStr(ru: String, tr: String): String = if (isTr) tr else ru

    private fun applySavedSettings() {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        prefRecFolderUri = sp.getString("rec_folder", "") ?: ""
        prefRecFormat = sp.getString("rec_format", ".mp3") ?: ".mp3"
        
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

    private fun createSettingsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)

        fun addHeader(text: String) {
            layout.addView(TextView(this).apply { this.text = text; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) })
        }

        // Тема
        addHeader(getStr("Тема оформления", "Tema"))
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf(
                getStr("Системная", "Sistem"), getStr("Светлая", "Açık"), getStr("Темная", "Koyu")
            ))
            val currentTheme = sp.getString("app_theme", "system")
            setSelection(if (currentTheme == "light") 1 else if (currentTheme == "dark") 2 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val newTheme = arrayOf("system", "light", "dark")[pos]
                    if (newTheme != currentTheme) {
                        sp.edit().putString("app_theme", newTheme).apply()
                        applySavedSettings()
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(themeSpinner)

        // Язык
        addHeader(getStr("Язык приложения", "Uygulama Dili"))
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf(
                getStr("Системный", "Sistem"), "Русский", "Türkçe"
            ))
            val currentLang = sp.getString("app_lang", "system")
            setSelection(if (currentLang == "ru") 1 else if (currentLang == "tr") 2 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val newLang = arrayOf("system", "ru", "tr")[pos]
                    if (newLang != currentLang) {
                        sp.edit().putString("app_lang", newLang).apply()
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(langSpinner)

        // Формат записи
        addHeader(getStr("Формат записи", "Kayıt Formatı"))
        val formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("MP3", "AAC"))
            setSelection(if (prefRecFormat == ".aac") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    prefRecFormat = if (pos == 1) ".aac" else ".mp3"
                    sp.edit().putString("rec_format", prefRecFormat).apply()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(formatSpinner)

        // Папка записи
        addHeader(getStr("Папка для записей", "Kayıt Klasörü"))
        layout.addView(TextView(this).apply { text = getStr("По умолчанию: корень телефона / radio_player_recordings", "Varsayılan: telefon kök dizini / radio_player_recordings"); textSize = 12f; setPadding(0,0,0,16) })
        layout.addView(Button(this).apply {
            text = getStr("Выбрать папку вручную", "Klasörü Manuel Seç")
            setOnClickListener { folderPickerLauncher.launch(null) }
        })

        return layout
    }

    private fun showMiniPlayerMenu() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        
        val options = mutableListOf<String>()
        options.add(getStr("Скопировать название песни", "Şarkı adını kopyala"))
        options.add(if (isRecording) getStr("Остановить запись", "Kaydı Durdur") else getStr("Начать запись потока", "Akışı Kaydet"))
        options.add(if (sleepTimerHandler != null) getStr("Отменить таймер выключения", "Zamanlayıcıyı İptal Et") else getStr("Таймер выключения", "Kapanış Zamanlayıcısı"))

        AlertDialog.Builder(this)
            .setTitle(currentPlaylist[currentStationIndex].name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> copySongMetadata()
                    1 -> toggleRecording()
                    2 -> if (sleepTimerHandler != null) cancelSleepTimer() else showTimerDialog()
                }
            }.show()
    }

    private fun copySongMetadata() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = if (currentSongMetadata.isNotEmpty()) currentSongMetadata else currentPlaylist[currentStationIndex].name
        val clip = ClipData.newPlainText("Song Info", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getStr("Скопировано: ", "Kopyalandı: ") + textToCopy, Toast.LENGTH_SHORT).show()
    }

    private fun showTimerDialog() {
        val times = arrayOf("15 " + getStr("мин", "dk"), "30 " + getStr("мин", "dk"), "45 " + getStr("мин", "dk"), "60 " + getStr("мин", "dk"))
        val values = arrayOf(15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle(getStr("Остановить через:", "Şu süre sonra durdur:"))
            .setItems(times) { _, which -> startSleepTimer(values[which]) }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerHandler = Handler(Looper.getMainLooper())
        sleepRunnable = Runnable {
            player?.stop()
            if (isRecording) toggleRecording()
            cancelSleepTimer()
        }
        sleepTimerHandler?.postDelayed(sleepRunnable!!, minutes * 60 * 1000L)
        Toast.makeText(this, getStr("Таймер установлен на $minutes мин.", "Zamanlayıcı $minutes dakikaya ayarlandı."), Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
        sleepTimerHandler = null
        sleepRunnable = null
    }

    private fun toggleRecording() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        val station = currentPlaylist[currentStationIndex]

        if (isRecording) {
            isRecording = false
            Toast.makeText(this, getStr("Запись остановлена", "Kayıt durduruldu"), Toast.LENGTH_SHORT).show()
            stationNameText.announceForAccessibility(getStr("Запись остановлена", "Kayıt durduruldu"))
            return
        }

        isRecording = true
        Toast.makeText(this, getStr("Запись началась...", "Kayıt başladı..."), Toast.LENGTH_SHORT).show()
        stationNameText.announceForAccessibility(getStr("Запись началась", "Kayıt başladı"))

        recordThread = thread {
            var input: InputStream? = null
            var out: OutputStream? = null
            try {
                val conn = URL(station.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                input = conn.inputStream

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Record_${station.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_$timestamp$prefRecFormat"

                // Логика выбора папки: SAF (если выбрана) ИЛИ корень телефона
                if (prefRecFolderUri.isNotEmpty()) {
                    val treeUri = Uri.parse(prefRecFolderUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val newFileUri = DocumentsContract.createDocument(contentResolver, docUri, "audio/*", fileName)
                    out = newFileUri?.let { contentResolver.openOutputStream(it) }
                } else {
                    // Пытаемся создать папку в корне по умолчанию
                    val rootDir = File(Environment.getExternalStorageDirectory(), "radio_player_recordings")
                    if (!rootDir.exists()) rootDir.mkdirs()
                    val file = File(rootDir, fileName)
                    out = FileOutputStream(file)
                }

                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (isRecording && input.read(buffer).also { bytesRead = it } != -1) {
                    out?.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isRecording = false
                    Toast.makeText(this@MainActivity, getStr("Ошибка записи. Проверьте права или выберите папку в настройках.", "Kayıt hatası. İzinleri kontrol edin veya ayarlardan klasör seçin."), Toast.LENGTH_LONG).show()
                }
            } finally {
                try { out?.close() } catch (e: Exception) {}
                try { input?.close() } catch (e: Exception) {}
                isRecording = false
            }
        }
    }
    
    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = getStr("Название", "Adı") }
        AlertDialog.Builder(this)
            .setTitle(getStr("Поиск", "Arama"))
            .setView(input)
            .setPositiveButton(getStr("Найти", "Bul")) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    currentMode = "SEARCH"
                    searchStations(q)
                }
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
        input.requestFocus()
    }

    private fun setupPlayerListener() {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) getStr("Пауза", "Duraklat") else getStr("Плей", "Oynat")
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val info = if (artist.isNotEmpty() && title.isNotEmpty()) "$artist - $title" else title
                if (info.isNotEmpty()) {
                    currentSongMetadata = info
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

        currentSongMetadata = ""
        stationNameText.text = station.name
        songInfoText.text = "" 
        stationNameText.announceForAccessibility((if(isTr) "Çalınıyor: " else "Включаю: ") + station.name)

        val meta = MediaMetadata.Builder().setTitle(station.name).setArtist(if(isTr) "Radyo Yayını" else "Радио эфир").build()
        player?.setMediaItem(MediaItem.Builder().setUri(station.url).setMediaMetadata(meta).build())
        player?.prepare()
        player?.play()
    }

    private fun togglePlayPause() { if (player?.isPlaying == true) player?.pause() else player?.play() }
    private fun playNext() { if (currentPlaylist.isNotEmpty()) playStation((currentStationIndex + 1) % currentPlaylist.size, currentPlaylist) }
    private fun playPrev() { if (currentPlaylist.isNotEmpty()) playStation(if (currentStationIndex - 1 < 0) currentPlaylist.size - 1 else currentStationIndex - 1, currentPlaylist) }

    private fun searchStations(name: String) {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
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
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
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
    
    private fun showStationListMenu(station: Station) {
        val isFav = favorites.any { it.url == station.url }
        val favOption = if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle")
        val shareOption = getStr("Поделиться ссылкой", "Bağlantıyı paylaş")
        val infoOption = getStr("Информация о станции", "İstasyon bilgisi")
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
            Toast.makeText(this, getStr("Удалено из избранного", "Favorilerden çıkarıldı"), Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(station)
            Toast.makeText(this, getStr("Добавлено в избранное", "Favorilere eklendi"), Toast.LENGTH_SHORT).show()
        }
        saveFavoritesToStorage()
        if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
    }

    private fun shareStation(station: Station) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, station.name)
            putExtra(Intent.EXTRA_TEXT, getStr("Слушай ", "Dinle: ") + station.name + ": " + station.url)
        }
        startActivity(Intent.createChooser(shareIntent, getStr("Поделиться станцией", "İstasyonu paylaş")))
    }

    private fun showStationInfo(station: Station) {
        val info = getStr("Название: ", "Adı: ") + station.name + "\n" +
                   getStr("Страна: ", "Ülke: ") + station.country + "\n" +
                   "URL: " + station.url
        AlertDialog.Builder(this).setTitle(getStr("Информация", "Bilgi")).setMessage(info).setPositiveButton("OK", null).show()
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
        isRecording = false
        cancelSleepTimer()
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}