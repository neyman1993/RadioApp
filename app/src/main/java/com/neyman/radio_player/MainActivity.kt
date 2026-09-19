package com.neyman.radio_player

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val TAG = "RadioAppLog"

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var player: MediaController? = null
    private lateinit var settings: SettingsManager

    private val stations = ArrayList<Station>()
    private val countries = ArrayList<String>()
    private var favorites = ArrayList<Station>()
    private var currentPlaylist = ArrayList<Station>()
    private var currentStationIndex = -1

    private lateinit var navLayout: LinearLayout
    private lateinit var listView: ListView
    private lateinit var settingsScroll: ScrollView
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var stationNameText: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var miniPlayerLayout: LinearLayout
    private lateinit var folderDisplay: TextView

    private var currentMode = "MAIN_MENU"
    private var preSettingsMode = "MAIN_MENU"
    private var currentCountryName = ""
    private var isTr = false
    private var currentSongMetadata = ""

    private var sleepTimerHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var isRecording = false
    private var recordThread: Thread? = null
    
    private var metadataTimer: Timer? = null
    private var currentStreamUrlForMetadata = ""

    // Прием команд от кнопок ГАРНИТУРЫ и шторки
    private val playerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
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

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settings = SettingsManager(this)
        settings.applySettings()
        isTr = Locale.getDefault().language == "tr"
        favorites = settings.loadFavorites()
        
        // Регистрация ресивера для ГАРНИТУРЫ
        val filter = IntentFilter().apply {
            addAction("com.neyman.radio.NEXT")
            addAction("com.neyman.radio.PREV")
            addAction("com.neyman.radio.STOP_APP")
        }
        ContextCompat.registerReceiver(this, playerActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
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
        
        val btnSearchTab = Button(this).apply { text = getStr("Поиск", "Arama"); layoutParams = btnParams }
        val btnCountriesTab = Button(this).apply { text = getStr("Страны", "Ülkeler"); layoutParams = btnParams }
        val btnFavTab = Button(this).apply { text = getStr("Избранное", "Favoriler"); layoutParams = btnParams }
        
        navLayout.addView(btnSearchTab); navLayout.addView(btnCountriesTab); navLayout.addView(btnFavTab)

        listView = ListView(this)
        
        // TALKBACK Жесты: Свайп вверх/вниз для меню станции
        listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        val list = if (currentMode == "FAVORITES") favorites else stations
                        if (position >= list.size) return
                        val station = list[position]
                        val isFav = favorites.any { it.url == station.url }
                        
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(101, if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle")))
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(102, getStr("Скопировать название песни", "Şarkı adını kopyala")))
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(103, getStr("Информация", "Bilgi")))
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(104, getStr("Поделиться", "Paylaş")))
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                        val list = if (currentMode == "FAVORITES") favorites else stations
                        if (position >= list.size) return false
                        val station = list[position]
                        when (action) {
                            101 -> { toggleFavorite(station); return true }
                            102 -> { copySongMetadata(station); return true }
                            103 -> { showStationInfo(station); return true }
                            104 -> { shareStation(station); return true }
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                })
                return view
            }
        }
        listView.adapter = listAdapter

        settingsScroll = ScrollView(this).apply { visibility = View.GONE }
        settingsScroll.addView(createSettingsLayout())

        miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
        }

        stationNameText = TextView(this).apply {
            text = getStr("Радио не выбрано", "Radyo seçilmedi")
            textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
            isFocusable = true; setOnClickListener { showMiniPlayerMenu() }
        }
        
        val controlsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnPrevBtn = Button(this).apply { text = getStr("Предыдущий", "Önceki"); setOnClickListener { playPrev() } }
        btnPlayPause = Button(this).apply { text = getStr("Плей", "Oynat"); setOnClickListener { togglePlayPause() } }
        val btnNextBtn = Button(this).apply { text = getStr("Следующий", "Sonraki"); setOnClickListener { playNext() } }
        
        controlsLayout.addView(btnPrevBtn); controlsLayout.addView(btnPlayPause); controlsLayout.addView(btnNextBtn)
        miniPlayerLayout.addView(stationNameText); miniPlayerLayout.addView(controlsLayout)

        mainLayout.addView(navLayout)
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout)
        
        setContentView(mainLayout)
        updateUIForMode()

        btnSearchTab.setOnClickListener { showSearchDialog() }
        btnCountriesTab.setOnClickListener { 
            currentMode = "COUNTRIES"; updateUIForMode()
            if (countries.isEmpty()) loadCountries() else { updateList(countries); stationNameText.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi")) }
        }
        btnFavTab.setOnClickListener { 
            currentMode = "FAVORITES"; updateUIForMode()
            updateList(favorites.map { it.name }); stationNameText.announceForAccessibility(getStr("В избранном ${favorites.size} станций", "Favorilerde ${favorites.size} istasyon var"))
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") { loadStationsByCountry(countries[position].split(" (")[0]) } 
            else { playStation(position, if (currentMode == "FAVORITES") favorites else stations) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (currentMode == "COUNTRIES") return@setOnItemLongClickListener false
            val list = if (currentMode == "FAVORITES") favorites else stations
            if (position in list.indices) { showStationListMenu(list[position]); true } else false
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ player = controllerFuture.get(); setupPlayerListener() }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUIForMode() {
        when (currentMode) {
            "MAIN_MENU" -> { 
                navLayout.visibility = View.VISIBLE; listView.visibility = View.GONE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
                supportActionBar?.title = getStr("Радио Плеер", "Radyo Çalar") 
            }
            "SEARCH_RESULTS" -> { 
                navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Результаты поиска", "Arama Sonuçları") 
            }
            "COUNTRIES" -> { 
                navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Страны", "Ülkeler") 
            }
            "STATIONS_OF_COUNTRY" -> { 
                navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = currentCountryName 
            }
            "FAVORITES" -> { 
                navLayout.visibility = View.GONE; listView.visibility = View.VISIBLE; settingsScroll.visibility = View.GONE
                miniPlayerLayout.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Избранное", "Favoriler") 
            }
            "SETTINGS" -> { 
                navLayout.visibility = View.GONE; listView.visibility = View.GONE; settingsScroll.visibility = View.VISIBLE
                miniPlayerLayout.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Настройки", "Ayarlar") 
            }
        }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getStr("Настройки", "Ayarlar"))
        menu.add(0, 2, 0, getStr("Выход", "Çıkış"))
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showMenu = currentMode != "SETTINGS"
        for (i in 0 until menu.size()) { menu.getItem(i).isVisible = showMenu }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            1 -> { preSettingsMode = currentMode; currentMode = "SETTINGS"; updateUIForMode() }
            2 -> attemptExit(force = true)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when (currentMode) {
            "STATIONS_OF_COUNTRY" -> { currentMode = "COUNTRIES"; updateUIForMode(); updateList(countries) }
            "COUNTRIES", "FAVORITES", "SEARCH_RESULTS" -> { currentMode = "MAIN_MENU"; updateUIForMode() }
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
        val btn = Button(this).apply {
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

    private fun createSettingsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun addHeader(text: String) { layout.addView(TextView(this).apply { this.text = text; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) }) }

        addSettingItem(layout, getStr("Тема оформления", "Tema"), arrayOf(getStr("Системная", "Sistem"), getStr("Светлая", "Açık"), getStr("Темная", "Koyu")), arrayOf("system", "light", "dark"), { settings.getTheme() }) { newTheme ->
            settings.saveTheme(newTheme); settings.applySettings()
        }

        addSettingItem(layout, getStr("Язык приложения", "Uygulama Dili"), arrayOf(getStr("Системный", "Sistem"), "Русский", "Türkçe"), arrayOf("system", "ru", "tr"), { settings.getLanguage() }) { newLang ->
            settings.saveLanguage(newLang); AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
        }

        addHeader(getStr("Скрывать дубликаты", "Kopyaları Gizle"))
        val dupSwitch = Switch(this).apply { isChecked = settings.hideDuplicates; setOnCheckedChangeListener { _, isCheckedVal -> settings.hideDuplicates = isCheckedVal } }
        layout.addView(dupSwitch)

        addSettingItem(layout, getStr("Качество звука (Битрейт)", "Ses Kalitesi (Bitrate)"), arrayOf(getStr("По умолчанию (то что дает сервер)", "Varsayılan (sunucunun sağladığı)"), "64 kbps", "128 kbps", "192 kbps", "320 kbps"), arrayOf("0", "64", "128", "192", "320"), { settings.minBitrate.toString() }) { newBit ->
            settings.minBitrate = newBit.toInt()
        }

        addSettingItem(layout, getStr("Буферизация (Защита от заиканий)", "Önbelleğe Alma (Saniye)"), arrayOf("3 " + getStr("сек", "sn"), "5 " + getStr("сек", "sn") + getStr(" (По умолчанию)", " (Varsayılan)"), "10 " + getStr("сек", "sn"), "20 " + getStr("сек", "sn")), arrayOf("3", "5", "10", "20"), { settings.bufferSeconds.toString() }) { newBuf ->
            settings.bufferSeconds = newBuf.toInt()
        }

        addSettingItem(layout, getStr("Формат записи", "Kayıt Formatı"), arrayOf("MP3", "AAC"), arrayOf(".mp3", ".aac"), { settings.recFormat }) { newFmt ->
            settings.recFormat = newFmt
        }

        addHeader(getStr("Папка для записей", "Kayıt Klasörü"))
        val currentFolderText = if (settings.recFolderUri.isNotEmpty()) getStr("Выбрана своя папка", "Özel klasör seçildi") else getStr("Все записи автоматически сохраняются в папку Music на вашем телефоне.", "Tüm kayıtlar telefonunuzdaki Music klasörüne otomatik olarak kaydedilir.")
        folderDisplay = TextView(this).apply { text = currentFolderText; textSize = 14f; setPadding(0,0,0,16) }
        layout.addView(folderDisplay)
        layout.addView(Button(this).apply { text = getStr("Выбрать другую папку вручную", "Başka bir klasörü manuel seç"); setOnClickListener { folderPickerLauncher.launch(null) } })
        
        addHeader(getStr("Резервная копия избранного", "Favori Yedekleme"))
        val backupLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backupLayout.addView(Button(this).apply { text = getStr("Экспорт", "Dışa Aktar"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { exportFavoritesLauncher.launch("radio_favorites.json") } })
        backupLayout.addView(Button(this).apply { text = getStr("Импорт", "İçe Aktar"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { importFavoritesLauncher.launch(arrayOf("*/*")) } })
        layout.addView(backupLayout)
        
        addHeader(getStr("Отладка", "Hata Ayıklama"))
        layout.addView(Button(this).apply { text = getStr("Сохранить лог в файл", "Günlüğü dosyaya kaydet"); setOnClickListener { logExportLauncher.launch("RadioApp_Log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt") } })
        
        return layout
    }

    private fun showMiniPlayerMenu() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        val station = currentPlaylist[currentStationIndex]
        val isFav = favorites.any { it.url == station.url }
        
        // НАСТРОЕК ЗДЕСЬ БОЛЬШЕ НЕТ
        val options = arrayOf(
            getStr("Скопировать название песни", "Şarkı adını kopyala"),
            if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle"),
            if (isRecording) getStr("Остановить запись", "Kaydı Durdur") else getStr("Начать запись потока", "Akışı Kaydet"),
            if (sleepTimerHandler != null) getStr("Отменить таймер выключения", "Zamanlayıcıyı İptal Et") else getStr("Таймер выключения", "Kapanış Zamanlayıcısı")
        )
        AlertDialog.Builder(this).setTitle(station.name).setItems(options) { _, w ->
            when (w) { 
                0 -> copySongMetadata(station)
                1 -> toggleFavorite(station)
                2 -> toggleRecording()
                3 -> if (sleepTimerHandler != null) cancelSleepTimer() else showTimerDialog() 
            }
        }.show()
    }

    private fun showStationListMenu(station: Station) {
        val isFav = favorites.any { it.url == station.url }
        val options = arrayOf(
            if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle"),
            getStr("Поделиться ссылкой", "Bağlantıyı paylaş"), 
            getStr("Информация о станции", "İstasyon bilgisi"),
            getStr("Скопировать название песни", "Şarkı adını kopyala")
        )
        AlertDialog.Builder(this).setTitle(station.name).setItems(options) { _, which ->
            when (which) { 0 -> toggleFavorite(station); 1 -> shareStation(station); 2 -> showStationInfo(station); 3 -> copySongMetadata(station) }
        }.show()
    }

    private fun showStationInfo(station: Station) {
        val info = getStr("Название: ", "Adı: ") + station.name + "\n" + getStr("Страна: ", "Ülke: ") + station.country + "\nURL: " + station.url
        AlertDialog.Builder(this).setTitle(getStr("Информация", "Bilgi")).setMessage(info).setPositiveButton("OK", null).show()
    }

    // Обновление текста песни с потокобезопасностью
    private fun updateSongInfo(title: String) {
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty() && cleanTitle != currentSongMetadata && cleanTitle != "Радио" && cleanTitle != "Radyo" && cleanTitle.lowercase() != "unknown") {
            currentSongMetadata = cleanTitle
            val stName = if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name else ""
            runOnUiThread {
                stationNameText.text = "$stName\n$cleanTitle"
                stationNameText.announceForAccessibility(cleanTitle)
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
        sleepRunnable = Runnable { player?.stop(); if (isRecording) toggleRecording(); cancelSleepTimer() }
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

    // БЕЗОПАСНЫЙ ИДЕАЛЬНЫЙ ПАРСЕР ИЗ ТВОЕГО PYTHON КОДА (Опрашивает только API серверов, НЕ прерывает аудио)
    private fun startMetadataFetcher(urlStr: String, stationName: String) {
        metadataTimer?.cancel()
        currentStreamUrlForMetadata = urlStr
        
        metadataTimer = Timer()
        metadataTimer?.schedule(object : TimerTask() {
            override fun run() {
                try {
                    val parsedUrl = URL(urlStr)
                    val host = parsedUrl.host
                    val port = if (parsedUrl.port == -1) parsedUrl.defaultPort else parsedUrl.port
                    val protocol = parsedUrl.protocol
                    
                    var title = ""

                    try {
                        val statusUrl = URL("$protocol://$host:$port/status-json.xsl")
                        val conn = statusUrl.openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000; conn.readTimeout = 2000
                        val res = conn.inputStream.bufferedReader().readText()
                        val icestats = JSONObject(res).optJSONObject("icestats")
                        val source = icestats?.opt("source")
                        if (source is JSONArray && source.length() > 0) {
                            title = source.getJSONObject(0).optString("title", "")
                        } else if (source is JSONObject) {
                            title = source.optString("title", "")
                        }
                    } catch (e: Exception){}

                    if (title.isEmpty()) {
                        try {
                            val statsUrl = URL("$protocol://$host:$port/stats?json=1")
                            val conn = statsUrl.openConnection() as HttpURLConnection
                            conn.connectTimeout = 2000; conn.readTimeout = 2000
                            val res = conn.inputStream.bufferedReader().readText()
                            title = JSONObject(res).optString("songtitle", "")
                        } catch(e: Exception){}
                    }

                    if (title.isEmpty()) {
                        try {
                            val adminUrl = URL("$protocol://$host:$port/admin.cgi?mode=viewxml")
                            val conn = adminUrl.openConnection() as HttpURLConnection
                            conn.connectTimeout = 2000; conn.readTimeout = 2000
                            val res = conn.inputStream.bufferedReader().readText()
                            val matcher = Pattern.compile("<SONGTITLE>(.*?)</SONGTITLE>").matcher(res)
                            if (matcher.find()) title = matcher.group(1)?.trim() ?: ""
                        } catch(e: Exception){}
                    }

                    if (title.isNotEmpty()) {
                        updateSongInfo(title)
                    }
                } catch (e: Exception) {}
            }
        }, 3000, 15000)
    }

    private fun setupPlayerListener() {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { btnPlayPause.text = if (isPlaying) getStr("Пауза", "Duraklat") else getStr("Плей", "Oynat") }
            
            // Нативный захват метаданных IcyInfo и ID3 прямо в ExoPlayer
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is androidx.media3.extractor.metadata.icy.IcyInfo) {
                        entry.title?.let { updateSongInfo(it) }
                    } else if (entry is androidx.media3.extractor.metadata.id3.TextInformationFrame) {
                        if (entry.id == "TIT2" || entry.id == "TT2") updateSongInfo(entry.value)
                    }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val displayTitle = mediaMetadata.displayTitle?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val info = listOf(title, displayTitle, artist).firstOrNull { it.isNotEmpty() && it != "Радио" && it != "Radyo" && it.lowercase() != "unknown" } ?: ""
                if (info.isNotEmpty()) updateSongInfo(info)
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val stName = if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name else ""
                stationNameText.text = getStr("Ошибка: ", "Hata: ") + stName
                stationNameText.announceForAccessibility(getStr("Ошибка воспроизведения", "Çalma hatası"))
            }
        })
    }

    private fun playStation(index: Int, playlist: List<Station>) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        
        player?.stop() 
        player?.clearMediaItems()
        metadataTimer?.cancel()
        currentSongMetadata = ""
        
        currentPlaylist = ArrayList(playlist)
        currentStationIndex = index
        
        val station = currentPlaylist[index]
        stationNameText.text = getStr("Загрузка...\n", "Yükleniyor...\n") + station.name 
        stationNameText.announceForAccessibility((if(isTr) "Oynatılıyor: " else "Включаю: ") + station.name)

        val meta = MediaMetadata.Builder().setTitle(station.name).setArtist(if(isTr) "Radyo Yayını" else "Радио эфир").build()
        val mediaItem = MediaItem.Builder().setMediaId(station.url).setUri(station.url).setMediaMetadata(meta).build()
        
        player?.setMediaItem(mediaItem)
        player?.prepare() 
        player?.play()
        
        startMetadataFetcher(station.url, station.name)
    }

    private fun togglePlayPause() {
        if (player?.isPlaying == true) player?.pause() else player?.play()
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

    private fun fetchStations(endpoint: String, isSearch: Boolean = false) {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            try {
                val urlStr = if (isSearch) {
                    "https://all.api.radio-browser.info/json/stations/search?name=${URLEncoder.encode(endpoint, "UTF-8")}&limit=100000"
                } else {
                    "https://all.api.radio-browser.info/json/stations/$endpoint?limit=100000"
                }
                
                val res = fetchJson(urlStr)
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
                runOnUiThread { 
                    updateList(stations.map { it.name }) 
                    stationNameText.announceForAccessibility(getStr("Загружено ${stations.size} станций", "${stations.size} istasyon yüklendi"))
                }
            } catch (e: Exception) {}
        }
    }

    private fun loadCountries() {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            try {
                val res = fetchJson("https://all.api.radio-browser.info/json/countries")
                countries.clear()
                for (i in 0 until res.length()) { val o = res.getJSONObject(i); if (o.optInt("stationcount") > 0) countries.add(o.optString("name") + " (" + o.optInt("stationcount") + ")") }
                countries.sort(); 
                runOnUiThread { 
                    updateList(countries) 
                    stationNameText.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi"))
                }
            } catch (e: Exception) {}
        }
    }

    private fun loadStationsByCountry(country: String) {
        currentCountryName = country
        currentMode = "STATIONS_OF_COUNTRY"
        updateUIForMode()
        fetchStations("bycountry/" + URLEncoder.encode(country, "UTF-8"))
    }

    private fun fetchJson(url: String): JSONArray {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=15000; readTimeout=15000; setRequestProperty("User-Agent", "radio_player") }
            JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) { JSONArray() }
    }

    private fun updateList(items: List<String>) { listAdapter.clear(); listAdapter.addAll(items); listAdapter.notifyDataSetChanged() }

    private fun toggleFavorite(station: Station) {
        val index = favorites.indexOfFirst { it.url == station.url }
        if (index != -1) { favorites.removeAt(index); Toast.makeText(this, getStr("Удалено", "Çıkarıldı"), Toast.LENGTH_SHORT).show() } 
        else { favorites.add(station); Toast.makeText(this, getStr("Добавлено", "Eklendi"), Toast.LENGTH_SHORT).show() }
        settings.saveFavorites(favorites)
        if (currentMode == "FAVORITES") updateList(favorites.map { it.name })
    }

    private fun shareStation(station: Station) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, station.name); putExtra(Intent.EXTRA_TEXT, getStr("Слушай ", "Dinle: ") + station.name + ": " + station.url) }
        startActivity(Intent.createChooser(shareIntent, getStr("Поделиться", "Paylaş")))
    }

    private fun attemptExit(force: Boolean = false) {
        if (isRecording && !force) {
            AlertDialog.Builder(this).setTitle(getStr("Внимание", "Uyarı"))
                .setMessage(getStr("Идет запись радио. Остановить и выйти?", "Radyo kaydı devam ediyor. Durdurup çıkılsın mı?"))
                .setPositiveButton(getStr("Выйти", "Çıkış")) { _, _ -> isRecording = false; player?.stop(); finishAffinity() }
                .setNegativeButton(getStr("Отмена", "İptal"), null).show()
        } else { player?.stop(); finishAffinity() }
    }

    override fun onDestroy() {
        unregisterReceiver(playerActionReceiver)
        metadataTimer?.cancel()
        isRecording = false; cancelSleepTimer(); MediaController.releaseFuture(controllerFuture); super.onDestroy()
    }
}