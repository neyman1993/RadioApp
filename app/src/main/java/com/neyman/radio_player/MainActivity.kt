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
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
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
    private lateinit var songInfoText: TextView
    private lateinit var btnPlayPause: Button

    private var currentMode = "MAIN_MENU"
    private var currentCountryName = ""
    private var isTr = false
    private var currentSongMetadata = ""

    private var sleepTimerHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var isRecording = false
    private var recordThread: Thread? = null

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.recFolderUri = uri.toString()
            Toast.makeText(this, getStr("Папка выбрана", "Klasör seçildi"), Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) { startRecordingLogic() } 
        else { Toast.makeText(this, getStr("Нет прав на сохранение", "Kayıt izni verilmedi"), Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settings = SettingsManager(this)
        settings.applySettings()
        isTr = Locale.getDefault().language == "tr"
        favorites = settings.loadFavorites()
        
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        navLayout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        
        val btnSearchTab = Button(this).apply { text = getStr("Поиск", "Arama"); layoutParams = btnParams }
        val btnCountriesTab = Button(this).apply { text = getStr("Страны", "Ülkeler"); layoutParams = btnParams }
        val btnFavTab = Button(this).apply { text = getStr("Избранное", "Favoriler"); layoutParams = btnParams }
        
        navLayout.addView(btnSearchTab)
        navLayout.addView(btnCountriesTab)
        navLayout.addView(btnFavTab)

        listView = ListView(this)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter

        settingsScroll = ScrollView(this).apply { visibility = View.GONE }
        settingsScroll.addView(createSettingsLayout())

        val miniPlayerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
        }

        stationNameText = TextView(this).apply {
            text = getStr("Радио не выбрано", "Radyo seçilmedi")
            textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            isFocusable = true; setOnClickListener { showMiniPlayerMenu() }
        }
        songInfoText = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 16)
        }
        
        val controlsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnPrevBtn = Button(this).apply { text = getStr("Пред", "Önceki"); setOnClickListener { playPrev() } }
        btnPlayPause = Button(this).apply { text = getStr("Плей", "Oynat"); setOnClickListener { togglePlayPause() } }
        val btnNextBtn = Button(this).apply { text = getStr("След", "Sonraki"); setOnClickListener { playNext() } }
        
        controlsLayout.addView(btnPrevBtn); controlsLayout.addView(btnPlayPause); controlsLayout.addView(btnNextBtn)
        miniPlayerLayout.addView(stationNameText); miniPlayerLayout.addView(songInfoText); miniPlayerLayout.addView(controlsLayout)

        mainLayout.addView(navLayout)
        mainLayout.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(miniPlayerLayout)
        
        setContentView(mainLayout)
        updateUIForMode()

        btnSearchTab.setOnClickListener { showSearchDialog() }
        btnCountriesTab.setOnClickListener { 
            currentMode = "COUNTRIES"
            updateUIForMode()
            if (countries.isEmpty()) loadCountries() else { updateList(countries); stationNameText.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi")) }
        }
        btnFavTab.setOnClickListener { 
            currentMode = "FAVORITES"
            updateUIForMode()
            updateList(favorites.map { it.name }) 
            stationNameText.announceForAccessibility(getStr("В избранном ${favorites.size} станций", "Favorilerde ${favorites.size} istasyon var"))
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
                navLayout.visibility = View.VISIBLE
                listView.visibility = View.GONE
                settingsScroll.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
                supportActionBar?.title = getStr("Радио Плеер", "Radio Player")
            }
            "SEARCH_RESULTS" -> {
                navLayout.visibility = View.GONE
                listView.visibility = View.VISIBLE
                settingsScroll.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Результаты поиска", "Arama Sonuçları")
            }
            "COUNTRIES" -> {
                navLayout.visibility = View.GONE
                listView.visibility = View.VISIBLE
                settingsScroll.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Страны", "Ülkeler")
            }
            "STATIONS_OF_COUNTRY" -> {
                navLayout.visibility = View.GONE
                listView.visibility = View.VISIBLE
                settingsScroll.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = currentCountryName
            }
            "FAVORITES" -> {
                navLayout.visibility = View.GONE
                listView.visibility = View.VISIBLE
                settingsScroll.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.title = getStr("Избранное", "Favoriler")
            }
            "SETTINGS" -> {
                navLayout.visibility = View.GONE
                listView.visibility = View.GONE
                settingsScroll.visibility = View.VISIBLE
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
        val showMenu = currentMode == "MAIN_MENU"
        for (i in 0 until menu.size()) { menu.getItem(i).isVisible = showMenu }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            1 -> { currentMode = "SETTINGS"; updateUIForMode() }
            2 -> attemptExit()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when (currentMode) {
            "STATIONS_OF_COUNTRY" -> { currentMode = "COUNTRIES"; updateUIForMode(); updateList(countries) }
            "COUNTRIES", "FAVORITES", "SEARCH_RESULTS", "SETTINGS" -> { currentMode = "MAIN_MENU"; updateUIForMode() }
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

    private fun attemptExit() {
        if (isRecording) {
            AlertDialog.Builder(this).setTitle(getStr("Внимание", "Uyarı"))
                .setMessage(getStr("Идет запись радио. Остановить и выйти?", "Radyo kaydı devam ediyor. Durdurup çıkılsın mı?"))
                .setPositiveButton(getStr("Выйти", "Çıkış")) { _, _ -> isRecording = false; player?.stop(); finishAffinity() }
                .setNegativeButton(getStr("Отмена", "İptal"), null).show()
        } else { player?.stop(); finishAffinity() }
    }

    private fun getStr(ru: String, tr: String): String = if (isTr) tr else ru

    private fun createSettingsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun addHeader(text: String) { layout.addView(TextView(this).apply { this.text = text; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) }) }

        addHeader(getStr("Тема оформления", "Tema"))
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf(getStr("Системная", "Sistem"), getStr("Светлая", "Açık"), getStr("Темная", "Koyu")))
            val t = settings.getTheme()
            setSelection(if (t == "light") 1 else if (t == "dark") 2 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val newTheme = arrayOf("system", "light", "dark")[pos]
                    if (newTheme != t) { settings.saveTheme(newTheme); settings.applySettings() }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(themeSpinner)

        addHeader(getStr("Язык приложения", "Uygulama Dili"))
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf(getStr("Системный", "Sistem"), "Русский", "Türkçe"))
            val l = settings.getLanguage()
            setSelection(if (l == "ru") 1 else if (l == "tr") 2 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val newLang = arrayOf("system", "ru", "tr")[pos]
                    if (newLang != l) { settings.saveLanguage(newLang); AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang)) }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(langSpinner)

        addHeader(getStr("Скрывать дубликаты", "Kopyaları Gizle"))
        val dupSwitch = Switch(this).apply {
            isChecked = settings.hideDuplicates
            setOnCheckedChangeListener { _, isChecked -> settings.hideDuplicates = isChecked }
        }
        layout.addView(dupSwitch)

        addHeader(getStr("Качество звука (Битрейт)", "Ses Kalitesi (Bitrate)"))
        val bitrateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf(getStr("Любое качество (По умолчанию)", "Herhangi bir kalite (Varsayılan)"), "64 kbps", "128 kbps", "192 kbps", "320 kbps"))
            val currentBitrate = settings.minBitrate
            setSelection(when(currentBitrate) { 64 -> 1; 128 -> 2; 192 -> 3; 320 -> 4; else -> 0 })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    settings.minBitrate = arrayOf(0, 64, 128, 192, 320)[pos]
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(bitrateSpinner)

        addHeader(getStr("Формат записи", "Kayıt Formatı"))
        val formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("MP3", "AAC"))
            setSelection(if (settings.recFormat == ".aac") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) { settings.recFormat = if (pos == 1) ".aac" else ".mp3" }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        layout.addView(formatSpinner)

        addHeader(getStr("Папка для записей", "Kayıt Klasörü"))
        layout.addView(TextView(this).apply { text = getStr("По умолчанию: Музыка / radio_player_recordings", "Varsayılan: Müzik / radio_player_recordings"); textSize = 12f; setPadding(0,0,0,16) })
        layout.addView(Button(this).apply { text = getStr("Выбрать папку вручную", "Klasörü Manuel Seç"); setOnClickListener { folderPickerLauncher.launch(null) } })
        return layout
    }

    private fun showMiniPlayerMenu() {
        if (currentStationIndex == -1 || currentPlaylist.isEmpty()) return
        val station = currentPlaylist[currentStationIndex]
        val isFav = favorites.any { it.url == station.url }
        
        val options = arrayOf(
            getStr("Скопировать название песни", "Şarkı adını kopyala"),
            if (isFav) getStr("Удалить из избранного", "Favorilerden çıkar") else getStr("Добавить в избранное", "Favorilere ekle"),
            if (isRecording) getStr("Остановить запись", "Kaydı Durdur") else getStr("Начать запись потока", "Akışı Kaydet"),
            if (sleepTimerHandler != null) getStr("Отменить таймер выключения", "Zamanlayıcıyı İptal Et") else getStr("Таймер выключения", "Kapanış Zamanlayıcısı")
        )
        AlertDialog.Builder(this).setTitle(station.name).setItems(options) { _, w ->
            when (w) { 0 -> copySongMetadata(station); 1 -> toggleFavorite(station); 2 -> toggleRecording(); 3 -> if (sleepTimerHandler != null) cancelSleepTimer() else showTimerDialog() }
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

    private fun copySongMetadata(station: Station) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val isCurrentPlaying = (currentStationIndex != -1 && currentPlaylist.isNotEmpty() && currentPlaylist[currentStationIndex].url == station.url)
        val textToCopy = if (isCurrentPlaying && currentSongMetadata.isNotEmpty()) currentSongMetadata else station.name
        
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

        if (settings.recFolderUri.isEmpty() && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
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
                while (isRecording && input.read(buffer).also { bytesRead = it } != -1) { out?.write(buffer, 0, bytesRead) }
            } catch (e: Exception) { runOnUiThread { isRecording = false; Toast.makeText(this@MainActivity, getStr("Ошибка. Выберите папку вручную.", "Hata. Klasörü manuel seçin."), Toast.LENGTH_LONG).show() } }
            finally { try { out?.close() } catch (e: Exception) {}; try { input?.close() } catch (e: Exception) {}; isRecording = false }
        }
    }
    
    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = getStr("Название", "Adı") }
        AlertDialog.Builder(this).setTitle(getStr("Поиск", "Arama")).setView(input)
            .setPositiveButton(getStr("Найти", "Bul")) { _, _ -> val q = input.text.toString().trim(); if (q.isNotEmpty()) { currentMode = "SEARCH_RESULTS"; updateUIForMode(); fetchStations("byname/" + URLEncoder.encode(q, "UTF-8")) } }
            .setNegativeButton(getStr("Отмена", "İptal"), null).show(); input.requestFocus()
    }

    private fun setupPlayerListener() {
        player?.repeatMode = Player.REPEAT_MODE_ALL
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { btnPlayPause.text = if (isPlaying) getStr("Пауза", "Duraklat") else getStr("Плей", "Oynat") }
            
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val info = if (artist.isNotEmpty() && title.isNotEmpty()) "$artist - $title" else title
                if (info.isNotEmpty() && artist != "Радио" && artist != "Radyo") { 
                    currentSongMetadata = info; songInfoText.text = info; songInfoText.announceForAccessibility(info) 
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player?.currentMediaItemIndex ?: -1
                if (idx != -1 && currentPlaylist.isNotEmpty() && idx < currentPlaylist.size) {
                    if (currentStationIndex != idx) {
                        if (isRecording) {
                            isRecording = false
                            Toast.makeText(this@MainActivity, getStr("Запись остановлена (смена станции)", "Kayıt durduruldu (istasyon değişti)"), Toast.LENGTH_SHORT).show()
                        }
                        currentStationIndex = idx
                        val st = currentPlaylist[idx]
                        stationNameText.text = st.name
                        currentSongMetadata = ""
                        songInfoText.text = getStr("Загрузка...", "Yükleniyor...")
                        stationNameText.announceForAccessibility((if(isTr) "Oynatılıyor: " else "Включаю: ") + st.name)
                    }
                }
                
                // МОЩНАЯ ЗАЩИТА ОТ ОШИБОК: Если станция была сломана, "Следующий" принудительно восстановит плеер
                if (player?.playbackState == Player.STATE_IDLE || player?.playerError != null) {
                    player?.prepare()
                    player?.play()
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val stName = if (currentStationIndex != -1 && currentPlaylist.isNotEmpty()) currentPlaylist[currentStationIndex].name else ""
                stationNameText.text = getStr("Ошибка: ", "Hata: ") + stName
                songInfoText.text = ""
                stationNameText.announceForAccessibility(getStr("Ошибка воспроизведения", "Çalma hatası"))
            }
        })
    }

    private fun playStation(index: Int, playlist: List<Station>) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        
        val isSamePlaylist = (currentPlaylist.size == playlist.size && currentPlaylist.isNotEmpty() && currentPlaylist[0].url == playlist[0].url)
        currentPlaylist = ArrayList(playlist)
        currentStationIndex = index
        
        val station = currentPlaylist[index]
        currentSongMetadata = ""
        stationNameText.text = station.name
        songInfoText.text = getStr("Загрузка...", "Yükleniyor...") 
        stationNameText.announceForAccessibility((if(isTr) "Oynatılıyor: " else "Включаю: ") + station.name)

        if (!isSamePlaylist || player?.mediaItemCount != playlist.size) {
            val mediaItems = playlist.map { st ->
                val meta = MediaMetadata.Builder().setTitle(st.name).setArtist(if(isTr) "Radyo" else "Радио").build()
                MediaItem.Builder().setMediaId(st.url).setUri(st.url).setMediaMetadata(meta).build()
            }
            player?.setMediaItems(mediaItems)
        }
        
        player?.seekToDefaultPosition(index) // ВЕРНУЛ ПРАВИЛЬНУЮ ФУНКЦИЮ
        player?.prepare() 
        player?.play()
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

    private fun fetchStations(endpoint: String) {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            val res = fetchJson("https://all.api.radio-browser.info/json/stations/$endpoint?limit=200")
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
        }
    }

    private fun loadCountries() {
        stationNameText.announceForAccessibility(getStr("Загрузка...", "Yükleniyor..."))
        thread {
            val res = fetchJson("https://all.api.radio-browser.info/json/countries")
            countries.clear()
            for (i in 0 until res.length()) { val o = res.getJSONObject(i); if (o.optInt("stationcount") > 0) countries.add(o.optString("name") + " (" + o.optInt("stationcount") + ")") }
            countries.sort(); 
            runOnUiThread { 
                updateList(countries) 
                stationNameText.announceForAccessibility(getStr("Загружено ${countries.size} стран", "${countries.size} ülke yüklendi"))
            }
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
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=10000; readTimeout=10000; setRequestProperty("User-Agent", "radio_player") }
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

    private fun showStationInfo(station: Station) {
        val info = getStr("Название: ", "Adı: ") + station.name + "\n" + getStr("Страна: ", "Ülke: ") + station.country + "\nURL: " + station.url
        AlertDialog.Builder(this).setTitle(getStr("Информация", "Bilgi")).setMessage(info).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() {
        isRecording = false; cancelSleepTimer(); MediaController.releaseFuture(controllerFuture); super.onDestroy()
    }
}