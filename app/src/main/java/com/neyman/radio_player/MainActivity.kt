package com.neyman.radio_player

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.*
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val TAG = "RadioAppLog"
    private lateinit var settings: SettingsManager

    private val stations = ArrayList<Station>()
    private val countries = ArrayList<String>()
    private val genres = ArrayList<String>()
    private var favorites = ArrayList<Station>()
    private var customStations = ArrayList<Station>()
    private var allLoadedCustomStations = ArrayList<Station>()
    private var currentPlaylist = ArrayList<Station>()
    private var currentStationIndex = -1
    private var recentStations = ArrayList<Station>()
    private lateinit var btnRecentTab: Button
    private lateinit var btnTrackHistoryTab: Button
    private var currentAudioCodec = ""
    private var currentAudioBitrate = 0
    private var currentAudioSampleRate = 0
    private var currentAudioChannels = 0

    private lateinit var navLayout: LinearLayout
    private lateinit var navScroll: ScrollView
    private lateinit var listView: ListView
    private lateinit var settingsScroll: ScrollView
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var stationNameText: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var miniPlayerLayout: LinearLayout
    private lateinit var folderDisplay: TextView
    private lateinit var countrySearchEdit: EditText
    private lateinit var countrySearchLayout: LinearLayout
    private var allCountries = ArrayList<String>()
    private var allLoadedStations = ArrayList<Station>()
    private var m3uImportTarget = "CUSTOM"

    private var currentMode = "MAIN_MENU"
    private var preSettingsMode = "MAIN_MENU"
    private var currentCountryName = ""
    private var currentGenreName = ""
    private var isTr = false
    private var currentSongMetadata = ""
    private var currentPlayingStationName = ""

    private var sleepTimerHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var isRecording = false
    private var recordThread: Thread? = null

    private val API_SERVERS = listOf(
        "all.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
        "at1.api.radio-browser.info",
        "fi1.api.radio-browser.info"
    )

    private val MAIN_GENRES = listOf(
        "60s", "70s", "80s", "90s", "2000s", "acoustic", "adult", "alternative", "ambient",
        "blues", "classical", "country", "dance", "disco", "electronic", "folk", "funk",
        "gospel", "hip hop", "hits", "house", "indie", "jazz", "latin", "lounge", "love",
        "metal", "oldies", "pop", "punk", "rap", "reggae", "rnb", "rock", "soul", "techno",
        "top 40", "african", "arabic", "asian", "bollywood", "christian", "christmas",
        "contemporary", "instrumental", "irish", "islamic", "japanese", "jewish", "korean",
        "meditation", "minimal", "nature", "news", "opera", "polish", "portuguese", "russian",
        "salsa", "smooth jazz", "spanish", "sports", "swing", "talk", "tango", "tropical",
        "turkish", "urban", "variety", "world"
    )

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.neyman.radio.READY" -> {
                    val name = intent.getStringExtra("name") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    if (name.isNotEmpty()) {
                        currentPlayingStationName = name
                    }
                    if (title.isNotEmpty()) {
                        currentSongMetadata = title
                    }
                    val stName = if (currentPlayingStationName.isNotEmpty()) currentPlayingStationName else name
                    val displayText = if (currentSongMetadata.isNotEmpty() && !currentSongMetadata.equals(stName, ignoreCase = true)) {
                        "$stName\n$currentSongMetadata"
                    } else {
                        stName
                    }
                    stationNameText.text = displayText
                    btnPlayPause.text = "⏸ " + getStr("Пауза", "Duraklat")
                }
                "com.neyman.radio.METADATA" -> {
                    val title = intent.getStringExtra("title") ?: ""
                    updateSongInfo(title)
                }
                "com.neyman.radio.STATE" -> {
                    val isPlaying = intent.getBooleanExtra("isPlaying", false)
                    btnPlayPause.text = if (isPlaying) "⏸ " + getStr("Пауза", "Duraklat") else "▶ " + getStr("Играть", "Oynat")
                }
                "com.neyman.radio.ERROR" -> {
                    val errCode = intent.getIntExtra("error_code", -1)
                    val stName = if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name else ""
                    val errText = if (errCode != -1) " (Code: $errCode)" else ""
                    stationNameText.text = getStr("Ошибка: ", "Hata: ") + stName + errText
                    btnPlayPause.text = "▶ " + getStr("Играть", "Oynat")
                    stationNameText.announceForAccessibility(getStr("Ошибка воспроизведения", "Çalma hatası"))
                }
                "com.neyman.radio.AUDIO_INFO" -> {
                    currentAudioCodec = intent.getStringExtra("codec") ?: ""
                    currentAudioBitrate = intent.getIntExtra("bitrate", 0)
                    currentAudioSampleRate = intent.getIntExtra("sample_rate", 0)
                    currentAudioChannels = intent.getIntExtra("channels", 0)
                }
                "com.neyman.radio.RECONNECTING" -> {
                    val attempt = intent.getIntExtra("attempt", 1)
                    val max = intent.getIntExtra("max", 5)
                    val stName = if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name else ""
                    val msg = getStr("Переподключение ($attempt/$max): ", "Yeniden bağlanıyor ($attempt/$max): ") + stName
                    stationNameText.text = msg
                    btnPlayPause.text = "⏳ " + getStr("Загрузка...", "Yükleniyor...")
                    stationNameText.announceForAccessibility(msg)
                }
                "com.neyman.radio.UNPLUGGED" -> {
                    btnPlayPause.text = "▶ " + getStr("Играть", "Oynat")
                    val msg = getStr("Наушники отключены. Воспроизведение на паузе.", "Kulaklık çıkarıldı. Oynatma duraklatıldı.")
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    stationNameText.announceForAccessibility(msg)
                }
                "com.neyman.radio.NEXT" -> playNext()
                "com.neyman.radio.PREV" -> playPrev()
                "com.neyman.radio.STOP_APP" -> attemptExit(force = true)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.recFolderUri = uri.toString()
            folderDisplay.text = getStr("Выбрана своя папка", "Özel klasör seçildi")
            Toast.makeText(this, getStr("Папка выбрана", "Klasör seçildi"), Toast.LENGTH_SHORT).show()
        }
    }

    private val logExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            thread {
                try {
                    val process = Runtime.getRuntime().exec("logcat -d -t 1000")
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val logText = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) { logText.append(line).append("\n") }
                    contentResolver.openOutputStream(uri)?.use { it.write(logText.toString().toByteArray()) }
                    runOnUiThread { Toast.makeText(this@MainActivity, getStr("Лог сохранен!", "Günlük kaydedildi!"), Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {}
            }
        }
    }

    private val exportFavoritesLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            thread {
                try {
                    val json = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).getString("favorites_json", "[]") ?: "[]"
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    runOnUiThread { Toast.makeText(this@MainActivity, getStr("Избранное успешно сохранено", "Favoriler başarıyla dışa aktarıldı"), Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {}
            }
        }
    }

    private val importFavoritesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@thread
                    val newFavs = ArrayList<Station>()

                    try {
                        val fileText = String(bytes, Charsets.UTF_8)
                        val arr = JSONArray(fileText)
                        for (i in 0 until arr.length()) newFavs.add(Station.fromJson(arr.getJSONObject(i)))
                    } catch (e: Exception) {
                        val unpickler = net.razorvine.pickle.Unpickler()
                        val data = unpickler.loads(bytes)
                        if (data is ArrayList<*>) {
                            for (item in data) {
                                if (item is HashMap<*, *>) {
                                    val name = item["name"] as? String ?: ""
                                    val url = item["url_resolved"] as? String ?: item["url"] as? String ?: ""
                                    if (name.isNotEmpty() && url.isNotEmpty() && newFavs.none { it.url == url }) {
                                        newFavs.add(Station(name, url, "Imported"))
                                    }
                                }
                            }
                        }
                    }
                    
                    if (newFavs.isNotEmpty()) {
                        runOnUiThread {
                            favorites.clear()
                            favorites.addAll(newFavs)
                            settings.saveFavorites(favorites)
                            if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
                            Toast.makeText(this@MainActivity, getStr("Восстановлено ${newFavs.size} станций!", "${newFavs.size} istasyon geri yüklendi!"), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, getStr("Станции не найдены", "İstasyonlar bulunamadı"), Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) { runOnUiThread { Toast.makeText(this@MainActivity, getStr("Ошибка файла", "Dosya hatası"), Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    
    private var m3uExportData: String? = null

    private val exportM3uLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null && !m3uExportData.isNullOrEmpty()) {
            val data = m3uExportData ?: ""
            thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread { Toast.makeText(this@MainActivity, getStr("M3U успешно сохранен", "M3U başarıyla kaydedildi"), Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, getStr("Ошибка сохранения M3U", "M3U kaydetme hatası"), Toast.LENGTH_SHORT).show() }
                } finally {
                    m3uExportData = null
                }
            }
        }
    }

    private val importM3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@thread
                    val fileContent = String(bytes, Charsets.UTF_8)
                    val imported = parseM3uContent(fileContent)
                    if (imported.isNotEmpty()) {
                        runOnUiThread {
                            var added = 0
                            if (m3uImportTarget == "FAVORITES") {
                                for (st in imported) {
                                    if (favorites.none { it.url == st.url }) {
                                        favorites.add(st)
                                        added++
                                    }
                                }
                                settings.saveFavorites(favorites)
                                if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
                                Toast.makeText(this@MainActivity, getStr("Добавлено $added станций из M3U в избранное!", "$added istasyon M3U'dan favorilere eklendi!"), Toast.LENGTH_LONG).show()
                            } else {
                                for (st in imported) {
                                    if (customStations.none { it.url == st.url }) {
                                        customStations.add(st)
                                        added++
                                    }
                                }
                                settings.saveCustomStations(customStations)
                                allLoadedCustomStations.clear()
                                allLoadedCustomStations.addAll(customStations)
                                if (currentMode == "CUSTOM_STATIONS") {
                                    filterCustomStations(countrySearchEdit.text.toString())
                                }
                                Toast.makeText(this@MainActivity, getStr("Добавлено $added пользовательских станций из M3U!", "$added kullanıcı istasyonu M3U'dan eklendi!"), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, getStr("В M3U файле станции не найдены", "M3U dosyasında istasyon bulunamadı"), Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, getStr("Ошибка чтения M3U файла", "M3U dosyası okuma hatası"), Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun isNightModeActive(): Boolean {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsManager(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        
        isTr = Locale.getDefault().language == "tr"
        favorites = settings.loadFavorites()
        customStations = settings.loadCustomStations()
        allLoadedCustomStations.clear()
        allLoadedCustomStations.addAll(customStations)
        checkBatteryOptimization()
        
        val filter = IntentFilter().apply {
            addAction("com.neyman.radio.READY")
            addAction("com.neyman.radio.METADATA")
            addAction("com.neyman.radio.STATE")
            addAction("com.neyman.radio.ERROR")
            addAction("com.neyman.radio.AUDIO_INFO")
            addAction("com.neyman.radio.RECONNECTING")
            addAction("com.neyman.radio.UNPLUGGED")
            addAction("com.neyman.radio.NEXT")
            addAction("com.neyman.radio.PREV")
            addAction("com.neyman.radio.STOP_APP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(playbackReceiver, filter)
        }
        
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissionsToRequest.isNotEmpty()) requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        navLayout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
        }
        val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        
        val btnSearchTab = Button(this).apply { text = getStr("Поиск", "Arama"); isAllCaps = false; layoutParams = btnParams }
        val btnCountriesTab = Button(this).apply { text = getStr("Страны", "Ülkeler"); isAllCaps = false; layoutParams = btnParams }
        val btnGenresTab = Button(this).apply { text = getStr("Жанры", "Türler"); isAllCaps = false; layoutParams = btnParams }
        val btnTopTab = Button(this).apply { text = getStr("Популярные", "Popüler"); isAllCaps = false; layoutParams = btnParams }
        val btnFavTab = Button(this).apply { text = getStr("Избранное", "Favoriler"); isAllCaps = false; layoutParams = btnParams }
        val btnCustomTab = Button(this).apply { text = getStr("Пользовательские станции", "Kullanıcı İstasyonları"); isAllCaps = false; layoutParams = btnParams }
        btnRecentTab = Button(this).apply { 
            text = getStr("История станций", "Son Çalınan İstasyonlar")
            isAllCaps = false
            layoutParams = btnParams
            visibility = if (settings.stationHistoryEnabled) View.VISIBLE else View.GONE
        }
        btnTrackHistoryTab = Button(this).apply {
            text = getStr("История треков", "Parça Geçmişi")
            isAllCaps = false
            layoutParams = btnParams
            visibility = if (settings.trackHistoryEnabled) View.VISIBLE else View.GONE
            setOnClickListener { showTrackHistoryDialog() }
        }
        
        navLayout.addView(btnSearchTab)
        navLayout.addView(btnCountriesTab)
        navLayout.addView(btnGenresTab)
        navLayout.addView(btnTopTab)
        navLayout.addView(btnFavTab)
        navLayout.addView(btnCustomTab)
        navLayout.addView(btnRecentTab)
        navLayout.addView(btnTrackHistoryTab)

        listView = ListView(this)
        listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                
                ViewCompat.setAccessibilityDelegate(view, null)
                if (currentMode == "COUNTRIES" || currentMode == "GENRES") return view

                val list = when (currentMode) {
                    "FAVORITES" -> favorites
                    "CUSTOM_STATIONS" -> customStations
                    "RECENT_STATIONS" -> recentStations
                    else -> stations
                }
                if (position >= 0 && position < list.size) {
                    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            val station = list[position]
                            val isFav = favorites.any { it.url == station.url }
                            
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(101, if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(102, getStr("Скопировать название песни", "Şarkı adını kopyala")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(103, getStr("Информация", "Bilgi")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(104, getStr("Поделиться", "Paylaş")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(105, getStr("Экспортировать в M3U", "M3U olarak dışa aktar")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(112, getStr("Искать песню в Spotify", "Şarkıyı Spotify'da Ara")))
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(113, getStr("Искать песню в Deezer", "Şarkıyı Deezer'da Ara")))
                            if (currentMode == "CUSTOM_STATIONS") {
                                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(106, getStr("Удалить из пользовательских", "Kullanıcı istasyonlarından sil")))
                            }
                            if (currentMode == "FAVORITES" && favorites.size > 1) {
                                if (position > 0) {
                                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(107, getStr("Переместить вверх", "Yukarı taşı")))
                                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(110, getStr("Переместить в самое начало", "En başa taşı")))
                                }
                                if (position < favorites.size - 1) {
                                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(108, getStr("Переместить вниз", "Aşağı taşı")))
                                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(111, getStr("Переместить в самый конец", "En sona taşı")))
                                }
                                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(109, getStr("Переместить на позицию...", "Konuma taşı...")))
                            }
                        }

                        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                            if (position >= list.size) return false
                            val station = list[position]
                            when (action) {
                                101 -> { toggleFavorite(station); return true }
                                102 -> { copySongMetadata(station); return true }
                                103 -> { showStationInfo(station); return true }
                                104 -> { shareStation(station); return true }
                                105 -> { exportStationsToM3u(listOf(station), "${station.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.m3u"); return true }
                                112 -> {
                                    val track = if (currentStationIndex != -1 && currentPlaylist.getOrNull(currentStationIndex)?.url == station.url && currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
                                    openSpotifySearch(track)
                                    return true
                                }
                                113 -> {
                                    val track = if (currentStationIndex != -1 && currentPlaylist.getOrNull(currentStationIndex)?.url == station.url && currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
                                    openDeezerSearch(track)
                                    return true
                                }
                                106 -> { if (currentMode == "CUSTOM_STATIONS") { removeCustomStation(station); return true } }
                                107 -> {
                                    if (currentMode == "FAVORITES") {
                                        val cur = favorites.indexOfFirst { it.url == station.url }
                                        if (cur > 0) moveFavorite(cur, cur - 1)
                                        return true
                                    }
                                }
                                108 -> {
                                    if (currentMode == "FAVORITES") {
                                        val cur = favorites.indexOfFirst { it.url == station.url }
                                        if (cur != -1 && cur < favorites.size - 1) moveFavorite(cur, cur + 1)
                                        return true
                                    }
                                }
                                109 -> {
                                    if (currentMode == "FAVORITES") {
                                        showMoveFavoriteDialog(station)
                                        return true
                                    }
                                }
                                110 -> {
                                    if (currentMode == "FAVORITES") {
                                        val cur = favorites.indexOfFirst { it.url == station.url }
                                        if (cur > 0) moveFavorite(cur, 0)
                                        return true
                                    }
                                }
                                111 -> {
                                    if (currentMode == "FAVORITES") {
                                        val cur = favorites.indexOfFirst { it.url == station.url }
                                        if (cur != -1 && cur < favorites.size - 1) moveFavorite(cur, favorites.size - 1)
                                        return true
                                    }
                                }
                            }
                            return super.performAccessibilityAction(host, action, args)
                        }
                    })
                }
                return view
            }
        }
        listView.adapter = listAdapter

        settingsScroll = ScrollView(this).apply { visibility = View.GONE }
        settingsScroll.addView(createSettingsLayout())

        val isNight = isNightModeActive()
        miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setBackgroundColor(if (isNight) Color.parseColor("#212121") else Color.parseColor("#ECEFF1"))
        }

        stationNameText = TextView(this).apply {
            text = getStr("Радио не выбрано", "Radyo seçilmedi")
            textSize = 18f
            setTextColor(if (isNight) Color.WHITE else Color.parseColor("#111111"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(6))
            isFocusable = true
            contentDescription = getStr("Текущая станция. Нажмите для меню управления", "Mevcut istasyon. Kontrol menüsü için tıklayın")
            setOnClickListener { showMiniPlayerMenu() }
        }
        
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnControlParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(4))
        }

        val btnPrevBtn = Button(this).apply {
            text = "⏮ " + getStr("Назад", "Geri")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            minHeight = dpToPx(56)
            setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
            layoutParams = btnControlParams
            contentDescription = getStr("Предыдущая станция", "Önceki istasyon")
            setOnClickListener { playPrev() }
        }
        btnPlayPause = Button(this).apply {
            text = "▶ " + getStr("Играть", "Oynat")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            minHeight = dpToPx(56)
            setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
            layoutParams = btnControlParams
            contentDescription = getStr("Воспроизведение и пауза", "Oynat ve duraklat")
            setOnClickListener { togglePlayPause() }
        }
        val btnNextBtn = Button(this).apply {
            text = "⏭ " + getStr("Вперед", "İleri")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            minHeight = dpToPx(56)
            setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
            layoutParams = btnControlParams
            contentDescription = getStr("Следующая станция", "Sonraki istasyon")
            setOnClickListener { playNext() }
        }
        
        controlsLayout.addView(btnPrevBtn)
        controlsLayout.addView(btnPlayPause)
        controlsLayout.addView(btnNextBtn)
        miniPlayerLayout.addView(stationNameText)
        miniPlayerLayout.addView(controlsLayout)

        navScroll = ScrollView(this).apply { isFillViewport = true }
        navScroll.addView(navLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        mainLayout.addView(navScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        countrySearchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 8, 16, 8)
        }
        countrySearchEdit = EditText(this).apply {
            hint = getStr("Поиск страны...", "Ülke ara...")
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    when (currentMode) {
                        "COUNTRIES" -> filterCountries(q)
                        "STATIONS_OF_COUNTRY" -> filterStations(q)
                        "CUSTOM_STATIONS" -> filterCustomStations(q)
                    }
                }
            })
        }
        countrySearchLayout.addView(countrySearchEdit)
        mainLayout.addView(countrySearchLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        
        setContentView(mainLayout)
        updateUIForMode()

        btnSearchTab.setOnClickListener { showSearchDialog() }
        btnCountriesTab.setOnClickListener { 
            currentMode = "COUNTRIES"; updateUIForMode()
            if (countries.isEmpty()) loadCountries() else { countrySearchEdit.setText(""); updateList(allCountries); countries.clear(); countries.addAll(allCountries); listView.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi")) }
        }
        btnGenresTab.setOnClickListener {
            currentMode = "GENRES"; updateUIForMode()
            if (genres.isEmpty()) loadGenres() else { updateList(genres); listView.announceForAccessibility(getStr("Загружено ${genres.size} жанров", "${genres.size} tür yüklendi")) }
        }
        btnTopTab.setOnClickListener {
            currentMode = "TOP_STATIONS"; updateUIForMode()
            fetchStations("topclick/100") 
        }
        btnFavTab.setOnClickListener { 
            currentMode = "FAVORITES"; updateUIForMode()
            updateList(favorites.map { it.name }); listView.announceForAccessibility(getStr("В избранном ${favorites.size} станций", "Favorilerde ${favorites.size} istasyon var"))
        }
        btnCustomTab.setOnClickListener { 
            currentMode = "CUSTOM_STATIONS"; updateUIForMode()
            countrySearchEdit.setText("")
            customStations.clear()
            customStations.addAll(allLoadedCustomStations)
            updateList(customStations.map { it.name })
            val cSize = customStations.size
            listView.announceForAccessibility(getStr("Пользовательских станций: $cSize", "Kullanıcı istasyonları: $cSize"))
        }
        btnRecentTab.setOnClickListener {
            currentMode = "RECENT_STATIONS"; updateUIForMode()
            recentStations = settings.loadStationHistory()
            updateList(recentStations.map { it.name })
            val rSize = recentStations.size
            listView.announceForAccessibility(if (rSize == 0) getStr("История станций пуста", "İstasyon geçmişi boş") else getStr("Недавних станций: $rSize", "Son çalınan istasyon: $rSize"))
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (currentMode) {
                "COUNTRIES" -> loadStationsByCountry(countries[position].split(" (")[0])
                "GENRES" -> loadStationsByGenre(genres[position].split(" (")[0])
                "FAVORITES" -> playStation(position, favorites)
                "CUSTOM_STATIONS" -> playStation(position, customStations)
                "RECENT_STATIONS" -> playStation(position, recentStations)
                else -> playStation(position, stations)
            }
        }
        
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") {
                if (position in countries.indices) {
                    val countryRaw = countries[position]
                    val countryName = countryRaw.split(" (")[0]
                    val options = arrayOf(
                        getStr("Экспортировать станции страны в M3U", "Ülke istasyonlarını M3U olarak dışa aktar")
                    )
                    AlertDialog.Builder(this)
                        .setTitle(countryName)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> exportCountryStationsToM3u(countryName)
                            }
                        }.show()
                    return@setOnItemLongClickListener true
                }
                return@setOnItemLongClickListener false
            }
            if (currentMode == "GENRES") return@setOnItemLongClickListener false
            val list = when (currentMode) {
                "FAVORITES" -> favorites
                "CUSTOM_STATIONS" -> customStations
                "RECENT_STATIONS" -> recentStations
                else -> stations
            }
            if (position in list.indices) { showStationListMenu(list[position]); true } else false
        }

        val lastStation = settings.getLastStation()
        if (lastStation != null) {
            currentPlayingStationName = lastStation.name
            stationNameText.text = lastStation.name
            if (settings.autoplayAtStart) {
                currentPlaylist = arrayListOf(lastStation)
                currentStationIndex = 0
                playStation(0, currentPlaylist)
            }
        }
    }

    private fun updateUIForMode() {
        countrySearchLayout.visibility = View.GONE
        when (currentMode) {
            "MAIN_MENU" -> { 
                navScroll.visibility = View.VISIBLE; navLayout.visibility = View.VISIBLE; listView.visibility = View.GONE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                btnRecentTab.visibility = if (settings.stationHistoryEnabled) View.VISIBLE else View.GONE
                btnTrackHistoryTab.visibility = if (settings.trackHistoryEnabled) View.VISIBLE else View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
                supportActionBar?.title = "Radio Player"
            }
            "SEARCH_RESULTS" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Результаты поиска", "Arama Sonuçları") 
            }
            "COUNTRIES" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE; countrySearchLayout.visibility = View.VISIBLE
                countrySearchEdit.hint = getStr("Поиск страны...", "Ülke ara...")
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Страны", "Ülkeler") 
            }
            "GENRES" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Жанры", "Türler") 
            }
            "STATIONS_OF_COUNTRY" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE; countrySearchLayout.visibility = View.VISIBLE
                countrySearchEdit.hint = getStr("Поиск станции...", "İstasyon ara...")
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = currentCountryName 
            }
            "STATIONS_OF_GENRE" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = currentGenreName 
            }
            "TOP_STATIONS" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Популярные станции", "Popüler İstasyonlar") 
            }
            "FAVORITES" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Избранное", "Favoriler") 
            }
            "CUSTOM_STATIONS" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE; countrySearchLayout.visibility = View.VISIBLE
                countrySearchEdit.hint = getStr("Поиск среди своих станций...", "Kullanıcı istasyonlarında ara...")
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Пользовательские станции", "Kullanıcı İstasyonları") 
            }
            "RECENT_STATIONS" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("История станций", "Son Çalınan İstasyonlar") 
            }
            "SETTINGS" -> { 
                navScroll.visibility = View.GONE; navLayout.visibility = View.GONE; listView.visibility = View.GONE; settingsScroll.visibility = View.VISIBLE
                miniPlayerLayout.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Настройки", "Ayarlar") 
            }
        }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getStr("Настройки", "Ayarlar"))
        menu.add(0, 3, 0, getStr("Добавить станцию", "İstasyon Ekle"))
        menu.add(0, 6, 0, getStr("Очистить историю", "Geçmişi Temizle"))
        menu.add(0, 4, 0, getStr("Экспорт в M3U", "M3U Olarak Dışa Aktar"))
        menu.add(0, 2, 0, getStr("Выход", "Çıkış"))
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showMenu = currentMode != "SETTINGS"
        for (i in 0 until menu.size()) { menu.getItem(i).isVisible = showMenu }
        if (showMenu) {
            menu.findItem(3)?.isVisible = (currentMode == "CUSTOM_STATIONS")
            menu.findItem(4)?.isVisible = (currentMode == "CUSTOM_STATIONS" || currentMode == "FAVORITES")
            menu.findItem(6)?.isVisible = (currentMode == "RECENT_STATIONS" && recentStations.isNotEmpty())
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            1 -> { preSettingsMode = currentMode; currentMode = "SETTINGS"; updateUIForMode() }
            2 -> attemptExit(force = true)
            3 -> showAddCustomStationMenu()
            4 -> {
                val toExport = when (currentMode) {
                    "FAVORITES" -> favorites
                    "CUSTOM_STATIONS" -> allLoadedCustomStations
                    "RECENT_STATIONS" -> recentStations
                    else -> if (stations.isNotEmpty()) stations else favorites
                }
                exportStationsToM3u(toExport, "radio_playlist.m3u")
            }
            6 -> clearStationHistoryDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when (currentMode) {
            "STATIONS_OF_COUNTRY" -> { currentMode = "COUNTRIES"; countrySearchEdit.setText(""); updateUIForMode(); countries.clear(); countries.addAll(allCountries); updateList(countries) }
            "STATIONS_OF_GENRE" -> { currentMode = "GENRES"; updateUIForMode(); updateList(genres) }
            "COUNTRIES", "GENRES", "TOP_STATIONS", "FAVORITES", "CUSTOM_STATIONS", "RECENT_STATIONS", "SEARCH_RESULTS" -> { currentMode = "MAIN_MENU"; updateUIForMode() }
            "SETTINGS" -> { currentMode = preSettingsMode; updateUIForMode() }
            else -> super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrev(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getStr(ru: String, tr: String): String = if (isTr) tr else ru

    private fun addSettingItem(layout: LinearLayout, title: String, displayNames: Array<String>, values: Array<String>, currentValue: () -> String, onSelected: (String) -> Unit) {
        layout.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) })
        val btn = Button(this).apply { isAllCaps = false
            val current = currentValue()
            val idx = values.indexOf(current).takeIf { it >= 0 } ?: 0
            text = displayNames[idx]
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 32, 40, 32)
            setOnClickListener {
                val currentDialogIdx = values.indexOf(currentValue()).takeIf { it >= 0 } ?: 0
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(displayNames, currentDialogIdx) { dialog, which ->
                        text = displayNames[which]
                        onSelected(values[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton(getStr("Отмена", "İptal"), null)
                    .show()
            }
        }
        layout.addView(btn)
    }

    private fun notifyEqUpdated() {
        try {
            val intent = Intent(this, PlaybackService::class.java).apply {
                action = "UPDATE_EQ"
            }
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getEqPresetDisplayName(preset: String): String {
        return when (preset.lowercase(Locale.ROOT)) {
            "flat" -> getStr("Обычный (Flat)", "Düz (Flat)")
            "bass_boost" -> getStr("Усиление басов", "Bas Güçlendirme")
            "rock" -> getStr("Рок", "Rock")
            "pop" -> getStr("Поп", "Pop")
            "jazz" -> getStr("Джаз", "Caz")
            "classical" -> getStr("Классика", "Klasik")
            "vocal" -> getStr("Вокал", "Vokal")
            "electronic" -> getStr("Электронная", "Elektronik")
            "custom" -> getStr("Пользовательский", "Özel")
            else -> preset
        }
    }

    private fun getEqSettingSummary(): String {
        return if (!settings.eqEnabled) {
            getStr("Эквалайзер: Выключен", "Ekolayzer: Kapalı")
        } else {
            getStr("Эквалайзер: ", "Ekolayzer: ") + getEqPresetDisplayName(settings.eqPreset)
        }
    }

    private fun showEqSelectionDialog(onUpdated: () -> Unit) {
        val options = arrayOf("off", "flat", "bass_boost", "rock", "pop", "jazz", "classical", "vocal", "electronic", "custom")
        val displayNames: Array<CharSequence> = options.map { opt ->
            if (opt == "off") getStr("Выключен", "Kapalı") as CharSequence
            else getEqPresetDisplayName(opt) as CharSequence
        }.toTypedArray()

        val currentIndex = if (!settings.eqEnabled) {
            0
        } else {
            val idx = options.indexOf(settings.eqPreset.lowercase(Locale.ROOT))
            if (idx >= 0) idx else 1
        }

        AlertDialog.Builder(this)
            .setTitle(getStr("Эквалайзер (5 полос)", "Ses Ekolayzeri (5 Bant)"))
            .setSingleChoiceItems(displayNames, currentIndex) { dialog: DialogInterface, which: Int ->
                val chosen = options[which]
                if (chosen == "off") {
                    settings.eqEnabled = false
                    notifyEqUpdated()
                    Toast.makeText(this, getStr("Эквалайзер выключен", "Ekolayzer kapalı"), Toast.LENGTH_SHORT).show()
                    onUpdated()
                    dialog.dismiss()
                } else if (chosen == "custom") {
                    settings.eqEnabled = true
                    settings.eqPreset = "custom"
                    notifyEqUpdated()
                    onUpdated()
                    dialog.dismiss()
                    showEqManualDialog {
                        onUpdated()
                    }
                } else {
                    settings.eqEnabled = true
                    settings.applyPreset(chosen)
                    notifyEqUpdated()
                    Toast.makeText(this, getStr("Эквалайзер: ", "Ekolayzer: ") + getEqPresetDisplayName(chosen), Toast.LENGTH_SHORT).show()
                    onUpdated()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun showEqManualDialog(onBandsChanged: () -> Unit) {
        val bandNamesRu = arrayOf("60 Гц (Глубокий бас)", "230 Гц (Бас)", "910 Гц (Средние)", "3.6 кГц (Высокие средние)", "14 кГц (Высокие)")
        val bandNamesTr = arrayOf("60 Hz (Derin Bas)", "230 Hz (Bas)", "910 Hz (Orta)", "3.6 kHz (Üst Orta)", "14 kHz (Tiz)")
        
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
        }
        val dialogScroll = ScrollView(this).apply { addView(dialogLayout) }
        
        val bands = settings.getEqBands()
        val valueLabels = mutableListOf<TextView>()
        val seekBars = mutableListOf<SeekBar>()
        
        for (i in 0 until 5) {
            val bandLabel = TextView(this).apply {
                text = getStr(bandNamesRu[i], bandNamesTr[i])
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dpToPx(8), 0, dpToPx(2))
            }
            val gainVal = bands[i].toInt()
            val gainText = if (gainVal > 0) "+$gainVal dB" else "$gainVal dB"
            val valLabel = TextView(this).apply {
                text = gainText
                textSize = 13f
            }
            valueLabels.add(valLabel)
            
            val seekBar = SeekBar(this).apply {
                max = 24
                progress = (bands[i] + 12f).toInt().coerceIn(0, 24)
                contentDescription = getStr(bandNamesRu[i], bandNamesTr[i])
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val g = progress - 12
                            bands[i] = g.toFloat()
                            valLabel.text = if (g > 0) "+$g dB" else "$g dB"
                            settings.saveEqBands(bands)
                            settings.eqPreset = "custom"
                            notifyEqUpdated()
                            onBandsChanged()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            seekBars.add(seekBar)
            
            dialogLayout.addView(bandLabel)
            dialogLayout.addView(valLabel)
            dialogLayout.addView(seekBar)
        }
        
        AlertDialog.Builder(this)
            .setTitle(getStr("Ручная настройка (5 полос)", "Manuel Ayar (5 Bant)"))
            .setView(dialogScroll)
            .setNeutralButton(getStr("Сбросить в 0", "Sıfırla")) { _, _ ->
                settings.saveEqBands(floatArrayOf(0f, 0f, 0f, 0f, 0f))
                settings.eqPreset = "custom"
                notifyEqUpdated()
                onBandsChanged()
            }
            .setPositiveButton(getStr("Готово", "Tamam"), null)
            .show()
    }

    private fun createSettingsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun addHeader(text: String) { layout.addView(TextView(this).apply { this.text = text; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) }) }

        addSettingItem(layout, getStr("Тема оформления", "Tema"), arrayOf(getStr("Системная", "Sistem"), getStr("Светлая", "Açık"), getStr("Темная", "Koyu")), arrayOf("system", "light", "dark"), { settings.getTheme() }) { newTheme ->
            settings.saveTheme(newTheme); settings.applySettings()
        }

        addSettingItem(layout, getStr("Язык приложения", "Uygulama Dili"), arrayOf(getStr("Системный", "Sistem"), "Русский", "Türkçe"), arrayOf("system", "ru", "tr"), { settings.getLanguage() }) { newLang ->
            settings.saveLanguage(newLang); AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
        }

        addHeader(getStr("Эквалайзер звука (5 полос)", "Ses Ekolayzeri (5 Bant)"))

        lateinit var btnTuneCustom: Button
        val btnEqSetting = Button(this).apply {
            isAllCaps = false
            text = getEqSettingSummary()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 32, 40, 32)
        }

        btnTuneCustom = Button(this).apply {
            isAllCaps = false
            text = getStr("Поднастроить эквалайзер (5 полос)...", "Ekolayzeri ince ayarla (5 bant)...")
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 32, 40, 32)
            visibility = if (settings.eqEnabled && settings.eqPreset.equals("custom", ignoreCase = true)) View.VISIBLE else View.GONE
            setOnClickListener {
                showEqManualDialog {
                    btnEqSetting.text = getEqSettingSummary()
                }
            }
        }

        btnEqSetting.setOnClickListener {
            showEqSelectionDialog {
                btnEqSetting.text = getEqSettingSummary()
                btnTuneCustom.visibility = if (settings.eqEnabled && settings.eqPreset.equals("custom", ignoreCase = true)) View.VISIBLE else View.GONE
            }
        }

        layout.addView(btnEqSetting)
        layout.addView(btnTuneCustom)

        addHeader(getStr("Автоматическое воспроизведение при запуске", "Başlangıçta otomatik oynat"))
        val autoplaySwitch = Switch(this).apply { 
            isChecked = settings.autoplayAtStart
            setOnCheckedChangeListener { _, isCheckedVal -> settings.autoplayAtStart = isCheckedVal } 
        }
        layout.addView(autoplaySwitch)

        addHeader(getStr("Скрывать дубликаты", "Kopyaları Gizle"))
        val dupSwitch = Switch(this).apply { isChecked = settings.hideDuplicates; setOnCheckedChangeListener { _, isCheckedVal -> settings.hideDuplicates = isCheckedVal } }
        layout.addView(dupSwitch)

        addSettingItem(layout, getStr("Качество записи", "Kayıt Kalitesi"), arrayOf(getStr("По умолчанию (то что дает сервер)", "Varsayılan (sunucunun sağladığı)"), "64 kbps", "128 kbps", "192 kbps", "320 kbps"), arrayOf("0", "64", "128", "192", "320"), { settings.minBitrate.toString() }) { newBit ->
            settings.minBitrate = newBit.toInt()
        }

        addSettingItem(layout, getStr("Буферизация (Защита от заиканий)", "Önbelleğe Alma (Saniye)"), arrayOf("3 " + getStr("сек", "sn"), "5 " + getStr("сек", "sn") + getStr(" (По умолчанию)", " (Varsayılan)"), "10 " + getStr("сек", "sn"), "20 " + getStr("сек", "sn")), arrayOf("3", "5", "10", "20"), { settings.bufferSeconds.toString() }) { newBuf ->
            settings.bufferSeconds = newBuf.toInt()
        }

        addHeader(getStr("Автопереподключение при обрыве сети", "Ağ kesildiğinde otomatik yeniden bağlan"))
        val autoReconnectSwitch = Switch(this).apply {
            isChecked = settings.autoReconnect
            setOnCheckedChangeListener { _, isCheckedVal -> settings.autoReconnect = isCheckedVal }
        }
        layout.addView(autoReconnectSwitch)

        addHeader(getStr("Аудиофокус", "Ses Odağı"))
        val audioFocusSwitch = Switch(this).apply {
            isChecked = settings.audioFocusEnabled
            setOnCheckedChangeListener { _, isCheckedVal -> settings.audioFocusEnabled = isCheckedVal }
        }
        layout.addView(audioFocusSwitch)

        addHeader(getStr("Автопауза при отключении наушников", "Kulaklık çıkarıldığında otomatik duraklat"))
        val unplugSwitch = Switch(this).apply {
            isChecked = settings.pauseOnUnplug
            setOnCheckedChangeListener { _, isCheckedVal -> settings.pauseOnUnplug = isCheckedVal }
        }
        layout.addView(unplugSwitch)

        addHeader(getStr("История прослушанных треков", "Çalınan parçaların geçmişi"))
        val trackHistSwitch = Switch(this).apply {
            isChecked = settings.trackHistoryEnabled
            setOnCheckedChangeListener { _, isCheckedVal ->
                settings.trackHistoryEnabled = isCheckedVal
                btnTrackHistoryTab.visibility = if (isCheckedVal) View.VISIBLE else View.GONE
            }
        }
        layout.addView(trackHistSwitch)

        addHeader(getStr("История станций (Недавние)", "Son çalınan istasyonlar geçmişi"))
        val stationHistSwitch = Switch(this).apply {
            isChecked = settings.stationHistoryEnabled
            setOnCheckedChangeListener { _, isCheckedVal ->
                settings.stationHistoryEnabled = isCheckedVal
                btnRecentTab.visibility = if (isCheckedVal) View.VISIBLE else View.GONE
            }
        }
        layout.addView(stationHistSwitch)

        addHeader(getStr("Папка для записей", "Kayıt Klasörü"))
        val currentFolderText = if (settings.recFolderUri.isNotEmpty()) getStr("Выбрана своя папка", "Özel klasör seçildi") else getStr("Все записи автоматически сохраняются в папку Music на вашем телефоне.", "Tüm kayıtlar telefonunuzdaki Music klasörüne otomatik olarak kaydedilir.")
        folderDisplay = TextView(this).apply { text = currentFolderText; textSize = 14f; setPadding(0,0,0,16) }
        layout.addView(folderDisplay)
        layout.addView(Button(this).apply { isAllCaps = false; text = getStr("Выбрать другую папку вручную", "Başka bir klasörü manuel seç"); setOnClickListener { folderPickerLauncher.launch(null) } })
        
        addHeader(getStr("Резервная копия избранного", "Favori Yedekleme"))
        val backupLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backupLayout.addView(Button(this).apply { isAllCaps = false; text = getStr("Экспорт", "Dışa Aktar"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { exportFavoritesLauncher.launch("radio_favorites.json") } })
        backupLayout.addView(Button(this).apply { isAllCaps = false; text = getStr("Импорт", "İçe Aktar"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { importFavoritesLauncher.launch(arrayOf("*/*")) } })
        layout.addView(backupLayout)

        addHeader(getStr("Фоновый режим и батарея", "Arka Plan ve Pil"))
        layout.addView(Button(this).apply {
            isAllCaps = false
            text = getStr("Отключить оптимизацию батареи", "Pil optimizasyonunu devre dışı bırak")
            setOnClickListener { requestBatteryOptimizationExemption() }
        })
        
        addHeader(getStr("Отладка", "Hata Ayıklama"))
        layout.addView(Button(this).apply { isAllCaps = false; text = getStr("Сохранить лог в файл", "Günlüğü dosyaya kaydet"); setOnClickListener { logExportLauncher.launch("RadioApp_Log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt") } })
        
        return layout
    }

    private fun showMiniPlayerMenu() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        val station = currentPlaylist[currentStationIndex]
        val isFav = favorites.any { it.url == station.url }
        val isCustom = allLoadedCustomStations.any { it.url == station.url }
        
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(Pair(getStr("Скопировать название песни", "Şarkı adını kopyala")) {
            copySongMetadata(station)
        })
        val trackToSearch = if (currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
        actions.add(Pair(getStr("Найти песню (Spotify, Deezer...)", "Şarkıyı ara (Spotify, Deezer...)")) {
            showTrackActionDialog(trackToSearch)
        })
        actions.add(Pair(if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle")) {
            toggleFavorite(station)
        })
        actions.add(Pair(getStr("Экспортировать станцию в M3U", "İstasyonu M3U olarak dışa aktar")) {
            exportStationsToM3u(listOf(station), "${station.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.m3u")
        })
        actions.add(Pair(if (isRecording) getStr("Остановить запись", "Kaydı Durdur") else getStr("Начать запись потока", "Akışı Kaydet")) {
            toggleRecording()
        })
        actions.add(Pair(if (sleepTimerHandler != null) getStr("Отменить таймер выключения", "Zamanlayıcıyı İptal Et") else getStr("Таймер выключения", "Kapanış Zamanlayıcısı")) {
            if (sleepTimerHandler != null) cancelSleepTimer() else showTimerDialog() 
        })
        actions.add(Pair(getStr("Информация о потоке и станции", "Akış ve İstasyon Bilgisi")) {
            showStationInfo(station)
        })
        if (settings.trackHistoryEnabled) {
            actions.add(Pair(getStr("История треков", "Parça Geçmişi")) {
                showTrackHistoryDialog()
            })
        }
        if (isCustom) {
            actions.add(Pair(getStr("Удалить из пользовательских", "Kullanıcı istasyonlarından sil")) {
                removeCustomStation(station)
            })
        }

        val titles = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle(station.name).setItems(titles) { _, w ->
            actions[w].second.invoke()
        }.show()
    }

    private fun showStationListMenu(station: Station) {
        val isFav = favorites.any { it.url == station.url }
        val isCustom = allLoadedCustomStations.any { it.url == station.url }
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        actions.add(Pair(if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle")) {
            toggleFavorite(station)
        })
        actions.add(Pair(getStr("Поделиться ссылкой", "Bağlantıyı paylaş")) {
            shareStation(station)
        })
        actions.add(Pair(getStr("Информация о станции", "İstasyon bilgisi")) {
            showStationInfo(station)
        })
        actions.add(Pair(getStr("Скопировать название песни", "Şarkı adını kopyala")) {
            copySongMetadata(station)
        })
        val listTrackToSearch = if (currentStationIndex != -1 && currentPlaylist.getOrNull(currentStationIndex)?.url == station.url && currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
        actions.add(Pair(getStr("Найти песню (Spotify, Deezer...)", "Şarkıyı ara (Spotify, Deezer...)")) {
            showTrackActionDialog(listTrackToSearch)
        })
        actions.add(Pair(getStr("Экспортировать станцию в M3U", "İstasyonu M3U olarak dışa aktar")) {
            exportStationsToM3u(listOf(station), "${station.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.m3u")
        })

        if (currentMode == "FAVORITES" && favorites.isNotEmpty()) {
            if (favorites.size > 1) {
                val idx = favorites.indexOfFirst { it.url == station.url }
                if (idx > 0) {
                    actions.add(Pair(getStr("Переместить выше", "Yukarı taşı")) {
                        val cur = favorites.indexOfFirst { it.url == station.url }
                        if (cur > 0) moveFavorite(cur, cur - 1)
                    })
                }
                if (idx != -1 && idx < favorites.size - 1) {
                    actions.add(Pair(getStr("Переместить ниже", "Aşağı taşı")) {
                        val cur = favorites.indexOfFirst { it.url == station.url }
                        if (cur != -1 && cur < favorites.size - 1) moveFavorite(cur, cur + 1)
                    })
                }
                actions.add(Pair(getStr("Переместить на позицию...", "Konuma taşı...")) {
                    showMoveFavoriteDialog(station)
                })
            }
            actions.add(Pair(getStr("Экспортировать ВСЕ избранные в M3U", "Tüm favorileri M3U olarak dışa aktar")) {
                exportStationsToM3u(favorites, "radio_favorites.m3u")
            })
        }

        if (currentMode == "CUSTOM_STATIONS" || isCustom) {
            actions.add(Pair(getStr("Экспортировать ВСЕ свои станции в M3U", "Tüm kullanıcı istasyonlarını M3U olarak dışa aktar")) {
                exportStationsToM3u(allLoadedCustomStations, "custom_radio_stations.m3u")
            })
            actions.add(Pair(getStr("Удалить из пользовательских", "Kullanıcı istasyonlarından sil")) {
                removeCustomStation(station)
            })
        }

        val titles = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle(station.name).setItems(titles) { _, which ->
            actions[which].second.invoke()
        }.show()
    }

    private fun showStationInfo(station: Station) {
        val isCurrentPlaying = currentStationIndex != -1 && currentPlaylist.isNotEmpty() && currentPlaylist[currentStationIndex].url == station.url
        val items = mutableListOf<String>()
        items.add(getStr("Название: ", "Adı: ") + station.name)
        if (station.country.isNotEmpty()) {
            items.add(getStr("Страна: ", "Ülke: ") + station.country)
        }
        items.add("URL: " + station.url)
        if (isCurrentPlaying) {
            if (currentAudioCodec.isNotEmpty()) {
                items.add(getStr("Аудиокодек: ", "Ses Kodeği: ") + currentAudioCodec)
            }
            if (currentAudioBitrate > 0) {
                items.add(getStr("Битрейт: ", "Bit Hızı: ") + "$currentAudioBitrate kbps")
            }
            if (currentAudioSampleRate > 0) {
                val chanStr = if (currentAudioChannels == 1) getStr("Моно", "Mono") else getStr("Стерео", "Stereo")
                items.add(getStr("Формат звука: ", "Ses Formatı: ") + "$currentAudioSampleRate Hz, $chanStr")
            }
        }

        val titles = items.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getStr("Информация о станции", "İstasyon Bilgisi"))
            .setItems(titles) { _, which ->
                val selected = titles[which]
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Station Info", selected))
                Toast.makeText(this, getStr("Скопировано: ", "Kopyalandı: ") + selected, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(getStr("Закрыть", "Kapat"), null)
            .show()
    }

    private fun updateSongInfo(title: String) {
        val cleanTitle = title.trim()
        val stName = if (currentPlayingStationName.isNotEmpty()) currentPlayingStationName
                     else if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name 
                     else settings.getLastStation()?.name ?: ""
        
        if (cleanTitle.isNotEmpty() && 
            !cleanTitle.equals(stName, ignoreCase = true) && 
            cleanTitle != "Радио" && cleanTitle != "Radyo" && 
            !cleanTitle.equals("unknown", ignoreCase = true)) {
            
            val isNew = cleanTitle != currentSongMetadata
            currentSongMetadata = cleanTitle
            runOnUiThread {
                val fullText = if (stName.isNotEmpty()) "$stName\n$cleanTitle" else cleanTitle
                stationNameText.text = fullText
                if (isNew) {
                    stationNameText.announceForAccessibility("$stName, $cleanTitle")
                }
            }
        }
    }

    private fun copySongMetadata(station: Station) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = if (currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
        clipboard.setPrimaryClip(ClipData.newPlainText("Song Info", textToCopy))
        Toast.makeText(this, getStr("Скопировано: ", "Kopyalandı: ") + textToCopy, Toast.LENGTH_SHORT).show()
    }

    private fun showTimerDialog() {
        val times = arrayOf("15 " + getStr("мин", "dk"), "30 " + getStr("мин", "dk"), "45 " + getStr("мин", "dk"), "60 " + getStr("мин", "dk"))
        val values = arrayOf(15, 30, 45, 60)
        AlertDialog.Builder(this).setTitle(getStr("Остановить через:", "Şu süre sonra durdur:"))
            .setItems(times) { _, w -> startSleepTimer(values[w]) }
            .setNegativeButton(getStr("Отмена", "İptal"), null).show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerHandler = Handler(Looper.getMainLooper())
        sleepRunnable = Runnable { 
            val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_SERVICE" }
            startService(stopIntent)
            if (isRecording) toggleRecording() 
            cancelSleepTimer() 
        }
        sleepTimerHandler?.postDelayed(sleepRunnable!!, minutes * 60 * 1000L)
        Toast.makeText(this, getStr("Таймер установлен на $minutes мин.", "Zamanlayıcı $minutes dakikaya ayarlandı."), Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() { sleepRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }; sleepTimerHandler = null; sleepRunnable = null }

    private fun toggleRecording() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        
        if (isRecording) {
            isRecording = false
            Toast.makeText(this, getStr("Запись остановлена", "Kayıt durduruldu"), Toast.LENGTH_SHORT).show()
            return
        }
        startRecordingLogic()
    }

    private fun startRecordingLogic() {
        isRecording = true
        val station = currentPlaylist[currentStationIndex]
        Toast.makeText(this, getStr("Запись началась...", "Kayıt başladı..."), Toast.LENGTH_SHORT).show()
        
        recordThread = thread {
            var input: InputStream? = null; var out: OutputStream? = null
            try {
                val conn = URL(station.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 10000; input = conn.inputStream
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Record_${station.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_$timestamp${settings.recFormat}"
                
                if (settings.recFolderUri.isNotEmpty()) {
                    val treeUri = Uri.parse(settings.recFolderUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val newFileUri = DocumentsContract.createDocument(contentResolver, docUri, "audio/*", fileName)
                    out = newFileUri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "radio_player_recordings")
                    if (!rootDir.exists()) rootDir.mkdirs()
                    out = FileOutputStream(File(rootDir, fileName))
                }
                
                val buffer = ByteArray(8192); var bytesRead = 0
                while (isRecording && input?.read(buffer).also { bytesRead = it ?: -1 } != -1) { out?.write(buffer, 0, bytesRead) }
            } catch (e: Exception) { runOnUiThread { isRecording = false; Toast.makeText(this@MainActivity, getStr("Ошибка записи.", "Kayıt hatası."), Toast.LENGTH_LONG).show() } }
            finally { try { out?.close() } catch (e: Exception) {}; try { input?.close() } catch (e: Exception) {}; isRecording = false }
        }
    }
    
    
    private fun parseM3uContent(content: String): List<Station> {
        val list = ArrayList<Station>()
        var currentTitle = ""
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                currentTitle = if (line.contains(",")) {
                    line.substringAfterLast(",").trim()
                } else {
                    val tvgMatch = Regex("tvg-name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)
                    tvgMatch?.groupValues?.get(1)?.trim() ?: ""
                }
            } else if (!line.startsWith("#")) {
                if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true) || line.startsWith("mms://", ignoreCase = true) || line.startsWith("rtsp://", ignoreCase = true)) {
                    val name = if (currentTitle.isNotEmpty()) currentTitle else {
                        line.substringBefore("?").substringAfterLast("/").ifEmpty { "Custom Radio" }
                    }
                    list.add(Station(name, line, "M3U"))
                    currentTitle = ""
                }
            }
        }
        return list
    }

    private fun buildM3uString(stationList: List<Station>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        for (st in stationList) {
            val cleanName = st.name.replace("\n", " ").trim()
            sb.append("#EXTINF:-1,")
            sb.append(cleanName)
            sb.append("\n")
            sb.append(st.url.trim())
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun exportStationsToM3u(stationsList: List<Station>, fileName: String) {
        if (stationsList.isEmpty()) {
            Toast.makeText(this, getStr("Список пуст", "Liste boş"), Toast.LENGTH_SHORT).show()
            return
        }
        m3uExportData = buildM3uString(stationsList)
        exportM3uLauncher.launch(fileName)
    }

    private fun exportCountryStationsToM3u(country: String) {
        Toast.makeText(this, getStr("Загрузка станций для экспорта...", "Dışa aktarım için istasyonlar yükleniyor..."), Toast.LENGTH_SHORT).show()
        thread {
            try {
                val res = fetchJsonWithFallback("bycountry/" + URLEncoder.encode(country, "UTF-8"))
                val countryStations = ArrayList<Station>()
                for (i in 0 until res.length()) {
                    val o = res.getJSONObject(i)
                    val url = if (o.optString("url_resolved").isNotEmpty()) o.optString("url_resolved") else o.optString("url")
                    if (url.isNotEmpty()) countryStations.add(Station.fromJson(o))
                }
                if (countryStations.isNotEmpty()) {
                    m3uExportData = buildM3uString(countryStations)
                    val safeFileName = "${country.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_stations.m3u"
                    runOnUiThread {
                        exportM3uLauncher.launch(safeFileName)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, getStr("Станции не найдены", "İstasyonlar bulunamadı"), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getStr("Ошибка загрузки станций", "İstasyon yükleme hatası"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showTrackHistoryDialog() {
        val tracks = settings.loadTrackHistory()
        if (tracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getStr("История треков", "Parça Geçmişi"))
                .setMessage(getStr("История прослушанных треков пуста", "Çalınan parçaların geçmişi boş"))
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val titles = tracks.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getStr("История треков (последние 15)", "Parça Geçmişi (son 15)"))
            .setItems(titles) { _, which ->
                val track = tracks[which]
                showTrackActionDialog(track)
            }
            .setNeutralButton(getStr("Очистить историю", "Geçmişi Temizle")) { _, _ ->
                settings.clearTrackHistory()
                Toast.makeText(this, getStr("История треков очищена", "Parça geçmişi temizlendi"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getStr("Закрыть", "Kapat"), null)
            .show()
    }

    private fun openSpotifySearch(track: String) {
        val query = track.trim()
        if (query.isEmpty()) return
        try {
            val uri = Uri.parse("spotify:search:" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://open.spotify.com/search/" + URLEncoder.encode(query, "UTF-8"))
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, getStr("Не удалось открыть Spotify", "Spotify açılamadı"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDeezerSearch(track: String) {
        val query = track.trim()
        if (query.isEmpty()) return
        try {
            val uri = Uri.parse("deezer-query://www.deezer.com/search/" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://www.deezer.com/search/" + URLEncoder.encode(query, "UTF-8"))
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, getStr("Не удалось открыть Deezer", "Deezer açılamadı"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openYouTubeSearch(track: String) {
        val query = track.trim()
        if (query.isEmpty()) return
        try {
            val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(ytIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getStr("Ошибка открытия YouTube", "YouTube açma hatası"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebSearch(track: String) {
        val query = track.trim()
        if (query.isEmpty()) return
        try {
            val webIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(webIntent)
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(browserIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, getStr("Ошибка открытия поиска", "Arama açma hatası"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyTrackTitle(track: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Track Info", track))
        Toast.makeText(this, getStr("Скопировано: ", "Kopyalandı: ") + track, Toast.LENGTH_SHORT).show()
    }

    private fun showTrackActionDialog(track: String) {
        val options = arrayOf(
            getStr("Искать в Spotify", "Spotify'da Ara"),
            getStr("Искать в Deezer", "Deezer'da Ara"),
            getStr("Искать на YouTube", "YouTube'da Ara"),
            getStr("Искать в браузере", "İnternette Ara"),
            getStr("Скопировать название", "Şarkı adını kopyala")
        )
        AlertDialog.Builder(this)
            .setTitle(track)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSpotifySearch(track)
                    1 -> openDeezerSearch(track)
                    2 -> openYouTubeSearch(track)
                    3 -> openWebSearch(track)
                    4 -> copyTrackTitle(track)
                }
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun clearStationHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle(getStr("Очистить историю станций?", "İstasyon geçmişi temizlensin mi?"))
            .setPositiveButton(getStr("Очистить", "Temizle")) { _, _ ->
                settings.clearStationHistory()
                recentStations.clear()
                updateList(emptyList())
                Toast.makeText(this, getStr("История очищена", "Geçmiş temizlendi"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun showAddCustomStationMenu() {
        val options = arrayOf(
            getStr("Добавить с URL", "URL ile ekle"),
            getStr("Добавить M3U", "M3U ekle")
        )
        AlertDialog.Builder(this)
            .setTitle(getStr("Добавить станцию", "İstasyon Ekle"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddCustomStationDialog()
                    1 -> {
                        m3uImportTarget = "CUSTOM"
                        importM3uLauncher.launch(arrayOf("*/*"))
                    }
                }
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun showAddCustomStationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val nameInput = EditText(this).apply {
            hint = getStr("Название станции", "İstasyon Adı")
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = getStr("URL станции", "İstasyon URL'si")
            setSingleLine(true)
        }
        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(getStr("Добавить станцию", "İstasyon Ekle"))
            .setView(layout)
            .setPositiveButton(getStr("Добавить", "Ekle")) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, getStr("Заполните название и URL", "Ad ve URL giriniz"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (allLoadedCustomStations.any { it.url == url }) {
                    Toast.makeText(this, getStr("Эта станция уже добавлена", "Bu istasyon zaten eklendi"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newStation = Station(name, url, "Custom")
                allLoadedCustomStations.add(newStation)
                settings.saveCustomStations(allLoadedCustomStations)
                if (currentMode == "CUSTOM_STATIONS") {
                    filterCustomStations(countrySearchEdit.text.toString())
                }
                Toast.makeText(this, getStr("Станция успешно добавлена!", "İstasyon başarıyla eklendi!"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun removeCustomStation(station: Station) {
        AlertDialog.Builder(this)
            .setTitle(getStr("Удаление станции", "İstasyonu Sil"))
            .setMessage(getStr("Удалить станцию \"${station.name}\" из списка пользовательских?", "\"${station.name}\" kullanıcı istasyonları listesinden silinsin mi?"))
            .setPositiveButton(getStr("Удалить", "Sil")) { _, _ ->
                customStations.removeAll { it.url == station.url }
                allLoadedCustomStations.removeAll { it.url == station.url }
                settings.saveCustomStations(allLoadedCustomStations)
                if (currentMode == "CUSTOM_STATIONS") {
                    filterCustomStations(countrySearchEdit.text.toString())
                }
                Toast.makeText(this, getStr("Станция удалена", "İstasyon silindi"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .show()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                if (!settings.batteryOptAsked) {
                    settings.batteryOptAsked = true
                    showBatteryOptimizationDialog()
                }
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getStr("Фоновая работа и батарея", "Arka Planda Çalışma ve Pil"))
            .setMessage(getStr(
                "Для непрерывного воспроизведения и записи радио при выключенном экране рекомендуется отключить ограничения батареи для приложения.",
                "Ekran kapalıyken radyonun kesintisiz çalması ve kaydetmesi için uygulama için pil kısıtlamalarını devre dışı bırakmanız önerilir."
            ))
            .setPositiveButton(getStr("Разрешить", "İzin Ver")) { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton(getStr("Позже", "Daha Sonra"), null)
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, getStr("Не удалось открыть настройки батареи", "Pil ayarları açılamadı"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
private fun showSearchDialog() {
        val input = EditText(this).apply { hint = getStr("Название", "Adı") }
        AlertDialog.Builder(this).setTitle(getStr("Поиск", "Arama")).setView(input)
            .setPositiveButton(getStr("Найти", "Bul")) { _, _ -> 
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) { 
                    currentMode = "SEARCH_RESULTS"
                    updateUIForMode()
                    fetchStations(q, isSearch = true) 
                } 
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null).show(); input.requestFocus()
    }

    private fun playStation(index: Int, playlist: List<Station>) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        
        currentPlaylist = ArrayList(playlist)
        currentStationIndex = index
        val station = currentPlaylist[index]
        
        settings.saveLastStation(station)
        if (settings.stationHistoryEnabled) {
            settings.addStationToHistory(station)
        }
        currentAudioCodec = ""
        currentAudioBitrate = 0
        currentAudioSampleRate = 0
        currentAudioChannels = 0
        
        currentPlayingStationName = station.name
        currentSongMetadata = ""
        stationNameText.text = getStr("Загрузка...\n", "Yükleniyor...\n") + station.name 
        btnPlayPause.text = "⏳ " + getStr("Загрузка...", "Yükleniyor...")
        
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = "PLAY_STATION"
            putExtra("url", station.url)
            putExtra("name", station.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun togglePlayPause() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) {
            val lastStation = settings.getLastStation()
            if (lastStation != null) {
                currentPlaylist = arrayListOf(lastStation)
                currentStationIndex = 0
                playStation(0, currentPlaylist)
            }
            return
        }

        val intent = Intent(this, PlaybackService::class.java).apply { action = "TOGGLE" }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun playNext() {
        if (currentPlaylist.isEmpty()) return
        val nextIdx = if (currentStationIndex + 1 >= currentPlaylist.size) 0 else currentStationIndex + 1
        playStation(nextIdx, currentPlaylist)
    }

    private fun playPrev() {
        if (currentPlaylist.isEmpty()) return
        val prevIdx = if (currentStationIndex - 1 < 0) currentPlaylist.size - 1 else currentStationIndex - 1
        playStation(prevIdx, currentPlaylist)
    }

    private fun fetchJsonWithFallback(endpoint: String): JSONArray {
        for (server in API_SERVERS) {
            try {
                val fullUrl = "https://$server/json/$endpoint"
                val c = (URL(fullUrl).openConnection() as HttpURLConnection).apply { 
                    connectTimeout = 5000 
                    readTimeout = 5000 
                    setRequestProperty("User-Agent", "radio_player") 
                }
                if (c.responseCode == 200) {
                    return JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                }
            } catch (e: Exception) {
                continue
            }
        }
        return JSONArray()
    }

    private fun fetchStations(endpoint: String, isSearch: Boolean = false) {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            try {
                val apiPath = if (isSearch) {
                    "stations/search?name=${URLEncoder.encode(endpoint, "UTF-8")}&limit=100000"
                } else {
                    "stations/$endpoint?limit=100000"
                }
                
                val res = fetchJsonWithFallback(apiPath)
                var rawList = ArrayList<JSONObject>()
                for (i in 0 until res.length()) { rawList.add(res.getJSONObject(i)) }
                if (settings.hideDuplicates) { rawList = ArrayList(StationDeduplicator.removeDuplicates(rawList)) }
                
                stations.clear()
                for (o in rawList) {
                    if (o.optInt("bitrate", 0) < settings.minBitrate) continue
                    val url = if (o.optString("url_resolved").isNotEmpty()) o.optString("url_resolved") else o.optString("url")
                    if (url.isNotEmpty()) stations.add(Station.fromJson(o))
                }
                stations.sortBy { it.name.lowercase(Locale.getDefault()) }
                allLoadedStations.clear()
                allLoadedStations.addAll(stations)
                runOnUiThread { 
                    if (currentMode == "STATIONS_OF_COUNTRY") {
                        countrySearchEdit.setText("")
                    }
                    updateList(stations.map { it.name }) 
                    val stCount = stations.size; listView.announceForAccessibility(getStr("Загружено $stCount станций", "$stCount istasyon yüklendi"))
                }
            } catch (e: Exception) {}
        }
    }

    private fun loadCountries() {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            try {
                val res = fetchJsonWithFallback("countries")
                countries.clear()
                for (i in 0 until res.length()) { val o = res.getJSONObject(i); if (o.optInt("stationcount") > 0) countries.add(o.optString("name") + " (" + o.optInt("stationcount") + ")") }
                countries.sort(); allCountries.clear(); allCountries.addAll(countries)
                runOnUiThread { 
                    countrySearchEdit.setText("")
                    updateList(countries) 
                    listView.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi"))
                }
            } catch (e: Exception) {}
        }
    }
    
    private fun loadGenres() {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        
        genres.clear()
        genres.addAll(MAIN_GENRES.map { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } })
        updateList(genres)
        
        thread {
            try {
                val res = fetchJsonWithFallback("tags")
                val tagCounts = HashMap<String, Int>()
                for (i in 0 until res.length()) { 
                    val o = res.getJSONObject(i)
                    val name = o.optString("name").lowercase(Locale.US)
                    tagCounts[name] = o.optInt("stationcount")
                }
                
                val updatedGenres = ArrayList<String>()
                for (genre in MAIN_GENRES) {
                    val count = tagCounts[genre] ?: 0
                    val displayName = genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                    if (count > 0) {
                        updatedGenres.add("$displayName ($count)")
                    } else {
                        updatedGenres.add(displayName)
                    }
                }
                
                runOnUiThread { 
                    genres.clear()
                    genres.addAll(updatedGenres)
                    updateList(genres) 
                    listView.announceForAccessibility(getStr("Загружено ${genres.size} жанров", "${genres.size} tür yüklendi"))
                }
            } catch (e: Exception) {}
        }
    }

    private fun filterCountries(query: String) {
        if (query.isEmpty()) {
            countries.clear()
            countries.addAll(allCountries)
            updateList(countries)
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault())
            val filtered = allCountries.filter { it.lowercase(Locale.getDefault()).contains(lowerQuery) }
            countries.clear()
            countries.addAll(filtered)
            updateList(countries)
        }
    }
    private fun filterStations(query: String) {
        if (query.isEmpty()) {
            stations.clear()
            stations.addAll(allLoadedStations)
            updateList(stations.map { it.name })
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault())
            val filtered = allLoadedStations.filter { it.name.lowercase(Locale.getDefault()).contains(lowerQuery) }
            stations.clear()
            stations.addAll(filtered)
            updateList(stations.map { it.name })
        }
    }

    private fun filterCustomStations(query: String) {
        if (query.isEmpty()) {
            customStations.clear()
            customStations.addAll(allLoadedCustomStations)
            updateList(customStations.map { it.name })
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault())
            val filtered = allLoadedCustomStations.filter { it.name.lowercase(Locale.getDefault()).contains(lowerQuery) }
            customStations.clear()
            customStations.addAll(filtered)
            updateList(customStations.map { it.name })
        }
    }

    private fun loadStationsByCountry(country: String) {
        currentCountryName = country
        currentMode = "STATIONS_OF_COUNTRY"
        runOnUiThread { countrySearchEdit.setText("") }
        updateUIForMode()
        fetchStations("bycountry/" + URLEncoder.encode(country, "UTF-8"))
    }
    
    private fun loadStationsByGenre(genre: String) {
        currentGenreName = genre
        currentMode = "STATIONS_OF_GENRE"
        updateUIForMode()
        fetchStations("bytagexact/" + URLEncoder.encode(genre.lowercase(Locale.US), "UTF-8"))
    }

    private fun updateList(items: List<String>) { listAdapter.clear(); listAdapter.addAll(items); listAdapter.notifyDataSetChanged() }

    private fun toggleFavorite(station: Station) {
        val index = favorites.indexOfFirst { it.url == station.url }
        if (index != -1) { favorites.removeAt(index); Toast.makeText(this, getStr("Удалено", "Çıkarıldı"), Toast.LENGTH_SHORT).show() } 
        else { favorites.add(station); Toast.makeText(this, getStr("Добавлено", "Eklendi"), Toast.LENGTH_SHORT).show() }
        settings.saveFavorites(favorites)
        if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
    }

    private fun moveFavorite(fromPos: Int, toPos: Int) {
        if (fromPos !in favorites.indices) return
        val targetPos = toPos.coerceIn(0, favorites.size - 1)
        if (fromPos == targetPos) return
        val station = favorites.removeAt(fromPos)
        favorites.add(targetPos, station)
        settings.saveFavorites(favorites)
        if (currentMode == "FAVORITES") {
            updateList(favorites.map { it.name })
            listView.post { listView.setSelection(targetPos) }
        }
        if (currentStationIndex in currentPlaylist.indices) {
            val playingUrl = currentPlaylist[currentStationIndex].url
            if (currentMode == "FAVORITES") {
                currentPlaylist = ArrayList(favorites)
                val newPlayingIdx = currentPlaylist.indexOfFirst { it.url == playingUrl }
                if (newPlayingIdx != -1) currentStationIndex = newPlayingIdx
            }
        }
        val msg = getStr(
            "${station.name}: перемещена на позицию ${targetPos + 1} из ${favorites.size}",
            "${station.name}: ${favorites.size} içinde ${targetPos + 1}. sıraya taşındı"
        )
        listView.announceForAccessibility(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showMoveFavoriteDialog(station: Station) {
        val currentIndex = favorites.indexOfFirst { it.url == station.url }
        if (currentIndex == -1) return
        val total = favorites.size
        if (total <= 1) {
            Toast.makeText(this, getStr("В избранном только одна станция", "Favorilerde sadece bir istasyon var"), Toast.LENGTH_SHORT).show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val infoText = TextView(this).apply {
            text = getStr(
                "${station.name}\nТекущая позиция: ${currentIndex + 1} из $total.\nВведите новую позицию (от 1 до $total):",
                "${station.name}\nMevcut sıra: $total içinde ${currentIndex + 1}.\nYeni sırayı girin (1 ile $total arası):"
            )
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }

        val posInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getStr("Позиция (1 - $total)", "Sıra (1 - $total)")
            setText((currentIndex + 1).toString())
            selectAll()
            setSingleLine(true)
        }

        layout.addView(infoText)
        layout.addView(posInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getStr("Переместить станцию", "İstasyonu Taşı"))
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val inputStr = posInput.text.toString().trim()
                val targetNum = inputStr.toIntOrNull()
                if (targetNum == null || targetNum !in 1..total) {
                    Toast.makeText(this@MainActivity, getStr("Введите число от 1 до $total", "1 ile $total arasında bir sayı girin"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val curIdx = favorites.indexOfFirst { it.url == station.url }
                if (curIdx != -1) moveFavorite(curIdx, targetNum - 1)
            }
            .setNegativeButton(getStr("Отмена", "İptal"), null)
            .create()

        dialog.show()
    }

    private fun shareStation(station: Station) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, station.name); putExtra(Intent.EXTRA_TEXT, getStr("Слушай ", "Dinle: ") + station.name + ": " + station.url) }
        startActivity(Intent.createChooser(shareIntent, getStr("Поделиться", "Paylaş")))
    }

    private fun attemptExit(force: Boolean = false) {
        if (isRecording && !force) {
            AlertDialog.Builder(this).setTitle(getStr("Внимание", "Uyarı"))
                .setMessage(getStr("Идет запись радио. Остановить и выйти?", "Radyo kaydı devam ediyor. Durdurup çıkılsın mı?"))
                .setPositiveButton(getStr("Выйти", "Çıkış")) { _, _ ->
                    isRecording = false
                    val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_SERVICE" }
                    startService(stopIntent)
                    finishAffinity()
                }
                .setNegativeButton(getStr("Отмена", "İptal"), null).show()
        } else {
            val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_SERVICE" }
            startService(stopIntent)
            finishAffinity() 
        }
    }

    override fun onResume() {
        super.onResume()
        if (PlaybackService.isPlaying || PlaybackService.currentStation.isNotEmpty()) {
            if (PlaybackService.currentStation.isNotEmpty()) {
                currentPlayingStationName = PlaybackService.currentStation
            }
            if (PlaybackService.currentTitle.isNotEmpty()) {
                currentSongMetadata = PlaybackService.currentTitle
            }
            val stName = if (currentPlayingStationName.isNotEmpty()) currentPlayingStationName else (settings.getLastStation()?.name ?: "")
            val displayText = if (currentSongMetadata.isNotEmpty() && !currentSongMetadata.equals(stName, ignoreCase = true)) {
                "$stName\n$currentSongMetadata"
            } else {
                stName
            }
            if (displayText.isNotEmpty()) {
                stationNameText.text = displayText
            }
            btnPlayPause.text = if (PlaybackService.isPlaying) "⏸ " + getStr("Пауза", "Duraklat") else "▶ " + getStr("Играть", "Oynat")
        }
    }

    override fun onDestroy() {
        unregisterReceiver(playbackReceiver)
        isRecording = false
        cancelSleepTimer()
        super.onDestroy()
    }
}