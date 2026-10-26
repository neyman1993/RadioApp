package com.neyman.radio_player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSHLS
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PlaybackService : Service() {

    companion object {
        var isPlaying: Boolean = false
            internal set
        var currentStation: String = ""
            internal set
        var currentTitle: String = ""
            internal set
    }

    private var streamHandle = 0
    private val eqFxHandles = IntArray(5)
    private val eqFrequencies = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    private lateinit var mediaSession: MediaSessionCompat
    private var currentUrl: String = ""
    private var currentStationName: String = ""
    private var currentSongTitle: String = ""
    private var isTr = false
    private var metadataTimer: Timer? = null

    // Audio Focus & Headphone unplug
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isDucked = false
    private var pausedByFocus = false
    private var isNoisyReceiverRegistered = false

    // Auto-reconnect & Network tracking
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var isReconnecting = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var consecutiveStallSeconds = 0
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Audio format info
    private var currentCodec = ""
    private var currentBitrate = 0
    private var currentSampleRate = 0
    private var currentChannels = 0

    // Локальный прокси для обхода BASS_ERROR_SSL (Код 10) на всех https:// и HLS потоках
    private var proxyServerSocket: ServerSocket? = null
    private var proxyPort: Int = 0
    private val proxyExecutor = Executors.newCachedThreadPool()
    private var trustAllSslFactory: SSLSocketFactory? = null

    override fun onCreate() {
        super.onCreate()
        val locale = resources.configuration.locales.get(0).language
        isTr = locale == "tr"

        initSslTrust()
        startLocalHttpsProxy()

        BASS.BASS_Init(-1, 44100, 0)
        BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_AGENT, "VLC/3.0.18 LibVLC/3.0.18 (compatible; RadioPlayer/1.0)")
        BASS.BASS_SetConfig(11, 10000) // BASS_CONFIG_NET_TIMEOUT
        BASS.BASS_SetConfig(14, 10000) // BASS_CONFIG_NET_READTIMEOUT
        BASS.BASS_SetConfig(18, 1)     // BASS_CONFIG_NET_PREBUF
        BASS.BASS_SetConfig(21, 1)     // BASS_CONFIG_NET_PLAYLIST

        val nativeDir = applicationInfo.nativeLibraryDir
        if (BASS.BASS_PluginLoad("libbasshls.so", 0) == 0) {
            BASS.BASS_PluginLoad("$nativeDir/libbasshls.so", 0)
        }
        if (BASS.BASS_PluginLoad("libbass_aac.so", 0) == 0) {
            BASS.BASS_PluginLoad("$nativeDir/libbass_aac.so", 0)
        }

        setupMediaSession()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerNetworkCallback()
    }

    // Аналог verify=False из Python, чтобы открывались станции с любыми SSL-сертификатами
    private fun initSslTrust() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            trustAllSslFactory = sslContext.socketFactory
        } catch (e: Exception) { }
    }

    private fun startLocalHttpsProxy() {
        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            proxyServerSocket = server
            proxyPort = server.localPort
            proxyExecutor.execute {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        proxyExecutor.execute { handleProxyClient(client) }
                    } catch (e: Exception) {
                        if (server.isClosed) break
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun buildProxyUrl(targetUrl: String): String {
        if (proxyPort == 0) return targetUrl
        val cleanPath = targetUrl.substringBefore("?").substringAfterLast("/").ifEmpty { "stream" }
        val safeName = cleanPath.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        return "http://127.0.0.1:$proxyPort/proxy/$safeName?url=$encoded"
    }

    private fun resolveRelativeUrl(baseUrl: String, refUrl: String): String {
        val trimmed = refUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val parsedBase = URL(baseUrl)
            val protocol = parsedBase.protocol
            val authority = parsedBase.authority
            when {
                trimmed.startsWith("//") -> "$protocol:$trimmed"
                trimmed.startsWith("/") -> "$protocol://$authority$trimmed"
                else -> {
                    val basePath = parsedBase.path.ifEmpty { "/" }
                    val baseDir = basePath.substringBeforeLast("/", "") + "/"
                    val cleanRef = if (trimmed.startsWith("./")) trimmed.removePrefix("./") else trimmed
                    "$protocol://$authority$baseDir$cleanRef"
                }
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    private fun openUpstreamConnection(startUrl: String, wantIcyMeta: Boolean, rangeHeader: String?): Pair<HttpURLConnection, String> {
        var currentTarget = startUrl
        var userAgent = "VLC/3.0.18 LibVLC/3.0.18 (compatible; RadioPlayer/1.0)"
        for (redirectCount in 0..5) {
            val urlObj = URL(currentTarget)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
                if (wantIcyMeta) {
                    setRequestProperty("Icy-MetaData", "1")
                }
                if (rangeHeader != null) {
                    setRequestProperty("Range", rangeHeader)
                }
                if (this is HttpsURLConnection) {
                    trustAllSslFactory?.let { this.sslSocketFactory = it }
                    this.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (!loc.isNullOrEmpty()) {
                    // Shoutcast web-redirect trap detection (redirecting browser to index.html)
                    if (loc.contains(".html", ignoreCase = true) || loc.contains("index.", ignoreCase = true)) {
                        val cleanBase = currentTarget.substringBefore("?")
                        currentTarget = if (cleanBase.endsWith("/")) "$cleanBase;" else "$cleanBase/;"
                        continue
                    }
                    currentTarget = resolveRelativeUrl(currentTarget, loc)
                    continue
                }
            }

            val cType = conn.contentType ?: ""
            if (cType.startsWith("text/html", ignoreCase = true) && !currentTarget.endsWith(";") && !currentTarget.endsWith("/;")) {
                conn.disconnect()
                val cleanBase = currentTarget.substringBefore("?")
                currentTarget = if (cleanBase.endsWith("/")) "$cleanBase;" else "$cleanBase/;"
                continue
            }

            if (code == 403 && userAgent.startsWith("VLC")) {
                conn.disconnect()
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                continue
            }

            return Pair(conn, currentTarget)
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun handleProxyClient(clientSocket: Socket) {
        var conn: HttpURLConnection? = null
        try {
            clientSocket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val requestUri = parts[1]

            var wantIcy = false
            var rangeHeader: String? = null
            while (true) {
                val hLine = reader.readLine() ?: break
                if (hLine.isEmpty()) break
                if (hLine.startsWith("Icy-MetaData:", ignoreCase = true) && hLine.contains("1")) {
                    wantIcy = true
                } else if (hLine.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = hLine.substringAfter(":").trim()
                }
            }

            val encodedUrl = requestUri.substringAfter("url=", "").substringBefore("&")
            if (encodedUrl.isEmpty()) return
            val targetUrl = URLDecoder.decode(encodedUrl, "UTF-8")

            val isLikelyHls = targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
            val (upstreamConn, finalUrl) = openUpstreamConnection(targetUrl, wantIcy && !isLikelyHls, rangeHeader)
            conn = upstreamConn

            val responseCode = conn.responseCode
            val contentType = conn.contentType ?: "application/octet-stream"
            val rawIn = BufferedInputStream(conn.inputStream, 8192)

            // Проверяем первые 7 байт на сигнатуру #EXTM3U (для HLS и m3u плейлистов)
            rawIn.mark(16)
            val peekBuf = ByteArray(7)
            var peekRead = 0
            while (peekRead < 7) {
                val r = rawIn.read(peekBuf, peekRead, 7 - peekRead)
                if (r <= 0) break
                peekRead += r
            }
            rawIn.reset()

            val startsWithExtM3u = peekRead == 7 && String(peekBuf, Charsets.UTF_8) == "#EXTM3U"
            val isM3u8Content = startsWithExtM3u ||
                    finalUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
                    contentType.contains("mpegurl", ignoreCase = true)

            val outStream = clientSocket.getOutputStream()

            if (isM3u8Content) {
                val playlistText = rawIn.bufferedReader(Charsets.UTF_8).readText()
                val uriAttrRegex = Regex("URI=\"([^\"]+)\"")
                val rewrittenLines = ArrayList<String>()

                for (rawLine in playlistText.lines()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) {
                        rewrittenLines.add("")
                    } else if (line.startsWith("#")) {
                        val rewrittenTag = uriAttrRegex.replace(line) { matchResult ->
                            val rawUri = matchResult.groupValues[1]
                            val resolved = resolveRelativeUrl(finalUrl, rawUri)
                            "URI=\"${buildProxyUrl(resolved)}\""
                        }
                        rewrittenLines.add(rewrittenTag)
                    } else {
                        val resolved = resolveRelativeUrl(finalUrl, line)
                        rewrittenLines.add(buildProxyUrl(resolved))
                    }
                }

                val bodyBytes = rewrittenLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                val headers = StringBuilder()
                headers.append("HTTP/1.0 200 OK\r\n")
                headers.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                headers.append("Content-Length: ${bodyBytes.size}\r\n")
                headers.append("Connection: close\r\n\r\n")
                outStream.write(headers.toString().toByteArray(Charsets.US_ASCII))
                outStream.write(bodyBytes)
                outStream.flush()
            } else {
                val statusLine = if (responseCode == 206) "HTTP/1.0 206 Partial Content\r\n" else "HTTP/1.0 200 OK\r\n"
                val headers = StringBuilder(statusLine)
                headers.append("Content-Type: $contentType\r\n")

                val contentLen = conn.getHeaderField("Content-Length")
                if (!contentLen.isNullOrEmpty()) {
                    headers.append("Content-Length: $contentLen\r\n")
                }
                val contentRange = conn.getHeaderField("Content-Range")
                if (!contentRange.isNullOrEmpty()) {
                    headers.append("Content-Range: $contentRange\r\n")
                }

                // Пробрасываем ICY-заголовки для работы метаданных в BASS
                for ((k, vList) in conn.headerFields) {
                    if (k != null && k.startsWith("icy-", ignoreCase = true) && !vList.isNullOrEmpty()) {
                        headers.append("$k: ${vList[0]}\r\n")
                    }
                }
                headers.append("Connection: close\r\n\r\n")
                outStream.write(headers.toString().toByteArray(Charsets.US_ASCII))
                outStream.flush()

                val buf = ByteArray(8192)
                while (true) {
                    val read = rawIn.read(buf)
                    if (read == -1) break
                    outStream.write(buf, 0, read)
                }
                outStream.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isDucked) {
                    isDucked = false
                    if (streamHandle != 0) {
                        BASS.BASS_ChannelSetAttribute(streamHandle, BASS.BASS_ATTRIB_VOL, 1.0f)
                    }
                } else if (pausedByFocus) {
                    pausedByFocus = false
                    resumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                isDucked = false
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (streamHandle != 0 && BASS.BASS_ChannelIsActive(streamHandle) == BASS.BASS_ACTIVE_PLAYING) {
                    pausedByFocus = true
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                val focusEnabled = sp.getBoolean("audio_focus_enabled", false)
                if (focusEnabled) {
                    isDucked = true
                    if (streamHandle != 0) {
                        BASS.BASS_ChannelSetAttribute(streamHandle, BASS.BASS_ATTRIB_VOL, 0.2f)
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
        isDucked = false
        pausedByFocus = false
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                val pauseOnUnplug = sp.getBoolean("pause_on_unplug", false)
                if (pauseOnUnplug) {
                    if (streamHandle != 0 && BASS.BASS_ChannelIsActive(streamHandle) == BASS.BASS_ACTIVE_PLAYING) {
                        pausePlayback()
                        sendLocalBroadcast(Intent("com.neyman.radio.UNPLUGGED"))
                    }
                }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                isNoisyReceiverRegistered = true
            } catch (e: Exception) {}
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (e: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isReconnecting || consecutiveStallSeconds > 0) {
                        reconnectHandler.removeCallbacksAndMessages(null)
                        isReconnecting = false
                        reconnectAttempts = 0
                        consecutiveStallSeconds = 0
                        startBassPlayback()
                    }
                }
            }
            networkCallback?.let {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(req, it)
            }
        } catch (e: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
    }

    private fun triggerReconnect(reason: String) {
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val autoReconnect = sp.getBoolean("auto_reconnect", false)
        if (!autoReconnect || isReconnecting || currentUrl.isEmpty()) return

        if (reconnectAttempts >= maxReconnectAttempts) {
            isReconnecting = false
            reconnectAttempts = 0
            val errIntent = Intent("com.neyman.radio.ERROR").apply {
                putExtra("error_code", -99)
            }
            sendLocalBroadcast(errIntent)
            showNotification(PlaybackStateCompat.STATE_PAUSED, getStr("Ошибка сети: не удалось переподключиться", "Ağ hatası: yeniden bağlanılamadı"), currentStationName)
            return
        }

        isReconnecting = true
        reconnectAttempts++
        val delayMs = reconnectAttempts * 2000L

        updateSessionState(PlaybackStateCompat.STATE_BUFFERING)
        val statusText = getStr("Переподключение ($reconnectAttempts/$maxReconnectAttempts)...", "Yeniden bağlanıyor ($reconnectAttempts/$maxReconnectAttempts)...")
        showNotification(PlaybackStateCompat.STATE_BUFFERING, statusText, currentStationName)

        val reconnectIntent = Intent("com.neyman.radio.RECONNECTING").apply {
            putExtra("attempt", reconnectAttempts)
            putExtra("max", maxReconnectAttempts)
        }
        sendLocalBroadcast(reconnectIntent)

        reconnectHandler.postDelayed({
            isReconnecting = false
            startBassPlayback()
        }, delayMs)
    }

    private fun extractAndBroadcastAudioInfo() {
        if (streamHandle == 0) return
        val info = BASS.BASS_CHANNELINFO()
        BASS.BASS_ChannelGetInfo(streamHandle, info)

        var detectedBitrate = 0
        val icyTags = BASS.BASS_ChannelGetTags(streamHandle, BASS.BASS_TAG_ICY) as? Array<*>
        if (icyTags != null) {
            for (tag in icyTags) {
                val line = tag?.toString() ?: ""
                if (line.startsWith("icy-br:", ignoreCase = true) || line.startsWith("ice-bitrate:", ignoreCase = true)) {
                    val brStr = line.substringAfter(":").trim().takeWhile { it.isDigit() }
                    detectedBitrate = brStr.toIntOrNull() ?: 0
                    if (detectedBitrate > 0) break
                }
            }
        }

        var detectedCodec = when {
            info.ctype == BASS.BASS_CTYPE_STREAM_MP3 -> "MP3"
            info.ctype == BASS.BASS_CTYPE_STREAM_MP2 -> "MP2"
            info.ctype == BASS.BASS_CTYPE_STREAM_MP1 -> "MP1"
            info.ctype == BASS.BASS_CTYPE_STREAM_OGG -> "OGG Vorbis"
            info.ctype == BASS.BASS_CTYPE_STREAM_WAV_PCM || info.ctype == BASS.BASS_CTYPE_STREAM_WAV -> "WAV"
            info.ctype == 0x10b00 || info.ctype == 0x10b01 || info.ctype == 0x10b02 -> "AAC"
            info.ctype == 0x10d00 -> "HLS"
            else -> {
                if (currentUrl.contains(".aac", true) || currentUrl.contains("aac", true)) "AAC"
                else if (currentUrl.contains(".m3u8", true)) "HLS"
                else if (currentUrl.contains(".opus", true)) "Opus"
                else if (currentUrl.contains(".flac", true)) "FLAC"
                else if (currentUrl.contains(".ogg", true)) "OGG"
                else "MP3"
            }
        }

        val httpTags = BASS.BASS_ChannelGetTags(streamHandle, BASS.BASS_TAG_HTTP) as? Array<*>
        if (httpTags != null) {
            for (tag in httpTags) {
                val line = tag?.toString() ?: ""
                if (detectedBitrate == 0 && (line.startsWith("icy-br:", ignoreCase = true) || line.startsWith("x-audiocast-bitrate:", ignoreCase = true) || line.startsWith("ice-bitrate:", ignoreCase = true))) {
                    val brStr = line.substringAfter(":").trim().takeWhile { it.isDigit() }
                    detectedBitrate = brStr.toIntOrNull() ?: 0
                }
                if (line.startsWith("content-type:", ignoreCase = true)) {
                    val cType = line.lowercase()
                    if (cType.contains("aac") || cType.contains("audio/aacp")) detectedCodec = "AAC"
                    else if (cType.contains("ogg")) detectedCodec = "OGG"
                    else if (cType.contains("opus")) detectedCodec = "Opus"
                    else if (cType.contains("flac")) detectedCodec = "FLAC"
                    else if (cType.contains("mpeg") && detectedCodec == "HLS") detectedCodec = "HLS (MP3)"
                }
            }
        }

        currentCodec = detectedCodec
        currentBitrate = detectedBitrate
        currentSampleRate = info.freq
        currentChannels = info.chans

        val audioIntent = Intent("com.neyman.radio.AUDIO_INFO").apply {
            putExtra("codec", currentCodec)
            putExtra("bitrate", currentBitrate)
            putExtra("sample_rate", currentSampleRate)
            putExtra("channels", currentChannels)
        }
        sendLocalBroadcast(audioIntent)
    }

    private fun pausePlayback() {
        if (streamHandle != 0 && BASS.BASS_ChannelIsActive(streamHandle) == BASS.BASS_ACTIVE_PLAYING) {
            BASS.BASS_ChannelPause(streamHandle)
            isPlaying = false
            updateSessionState(PlaybackStateCompat.STATE_PAUSED)
            showNotification(PlaybackStateCompat.STATE_PAUSED, if (currentSongTitle.isNotEmpty()) currentSongTitle else currentStationName)
            val stateIntent = Intent("com.neyman.radio.STATE").apply { putExtra("isPlaying", false) }
            sendLocalBroadcast(stateIntent)
            unregisterNoisyReceiver()
        }
    }

    private fun resumePlayback() {
        if (streamHandle != 0 && BASS.BASS_ChannelIsActive(streamHandle) != BASS.BASS_ACTIVE_PLAYING) {
            requestAudioFocus()
            registerNoisyReceiver()
            applyEqualizerSettings()
            BASS.BASS_ChannelPlay(streamHandle, false)
            isPlaying = true
            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, if (currentSongTitle.isNotEmpty()) currentSongTitle else currentStationName)
            val stateIntent = Intent("com.neyman.radio.STATE").apply { putExtra("isPlaying", true) }
            sendLocalBroadcast(stateIntent)
        }
    }

    private fun getStr(ru: String, tr: String): String = if (isTr) tr else ru

    private fun sendLocalBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayback() }
                override fun onPause() { togglePlayback() }
                override fun onStop() { stopServiceAndApp() }
                override fun onSkipToNext() { sendActionToActivity("com.neyman.radio.NEXT") }
                override fun onSkipToPrevious() { sendActionToActivity("com.neyman.radio.PREV") }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == "ACTION_STOP_SERVICE") stopServiceAndApp()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fallbackName = currentStationName.ifEmpty { "Radio Player" }
        showNotification(
            if (streamHandle == 0) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_BUFFERING,
            if (streamHandle == 0) getStr("Остановлено", "Durduruldu") else getStr("Загрузка...", "Yükleniyor..."),
            fallbackName
        )

        when (intent?.action) {
            "PLAY_STATION" -> {
                currentUrl = intent.getStringExtra("url") ?: ""
                currentStationName = intent.getStringExtra("name") ?: ""
                currentSongTitle = ""
                currentStation = currentStationName
                currentTitle = ""
                
                updateSessionState(PlaybackStateCompat.STATE_BUFFERING)
                showNotification(PlaybackStateCompat.STATE_BUFFERING, getStr("Загрузка...", "Yükleniyor..."))
                
                startBassPlayback()
            }
            "TOGGLE" -> togglePlayback()
            "UPDATE_EQ" -> applyEqualizerSettings()
            "STOP_SERVICE" -> stopServiceAndApp()
        }
        return START_NOT_STICKY
    }

    private fun resolveStreamUrl(url: String): String {
        try {
            val lowerUrl = url.lowercase()
            // If URL points to a .pls or .m3u playlist, extract the actual stream URL
            if (lowerUrl.endsWith(".pls") || lowerUrl.contains(".pls?")) {
                val (conn, _) = openUpstreamConnection(url, false, null)
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val match = Regex("File\\d+=(.+)", RegexOption.IGNORE_CASE).find(text)
                if (match != null) return match.groupValues[1].trim()
            } else if ((lowerUrl.endsWith(".m3u") || lowerUrl.contains(".m3u?")) && !lowerUrl.endsWith(".m3u8")) {
                val (conn, _) = openUpstreamConnection(url, false, null)
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) return trimmed
                }
            }
        } catch (e: Exception) { }
        return url
    }
    private fun applyEqualizerSettings() {
        if (streamHandle == 0) return
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val enabled = sp.getBoolean("eq_enabled", false)
        
        if (!enabled) {
            for (i in eqFxHandles.indices) {
                if (eqFxHandles[i] != 0) {
                    BASS.BASS_ChannelRemoveFX(streamHandle, eqFxHandles[i])
                    eqFxHandles[i] = 0
                }
            }
            return
        }

        for (i in 0 until 5) {
            if (eqFxHandles[i] == 0) {
                eqFxHandles[i] = BASS.BASS_ChannelSetFX(streamHandle, BASS.BASS_FX_DX8_PARAMEQ, i)
            }
            if (eqFxHandles[i] != 0) {
                val gain = sp.getFloat("eq_band_$i", 0f)
                val p = BASS.BASS_DX8_PARAMEQ()
                p.fCenter = eqFrequencies[i]
                p.fBandwidth = 18f
                p.fGain = gain.coerceIn(-15f, 15f)
                BASS.BASS_FXSetParameters(eqFxHandles[i], p)
            }
        }
    }

    private fun startBassPlayback() {
        metadataTimer?.cancel()

        Thread {
            if (streamHandle != 0) {
                BASS.BASS_StreamFree(streamHandle)
                streamHandle = 0
                eqFxHandles.fill(0)
            }

            val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
            val bufferSec = sp.getInt("buffer_seconds", 5)
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, bufferSec * 1000)

            // Resolve .pls/.m3u playlists to get the actual stream URL
            val resolvedUrl = resolveStreamUrl(currentUrl)
            val playUrl = buildProxyUrl(resolvedUrl)
            val flags = BASS.BASS_STREAM_AUTOFREE or BASS.BASS_STREAM_STATUS

            fun tryCreateStream(urlToTry: String): Int {
                var handle = 0
                if (urlToTry.contains(".m3u8", ignoreCase = true)) {
                    try {
                        handle = BASSHLS.BASS_HLS_StreamCreateURL(urlToTry, flags, null, null)
                    } catch (e: Exception) { }
                    if (handle == 0) {
                        handle = BASS.BASS_StreamCreateURL(urlToTry, 0, flags, null, null)
                    }
                } else {
                    handle = BASS.BASS_StreamCreateURL(urlToTry, 0, flags, null, null)
                    if (handle == 0) {
                        try {
                            handle = BASSHLS.BASS_HLS_StreamCreateURL(urlToTry, flags, null, null)
                        } catch (e: Exception) { }
                    }
                }
                return handle
            }

            // 1. Try through local proxy
            streamHandle = tryCreateStream(playUrl)

            // 2. Fallback: try direct URL without proxy if proxy failed
            if (streamHandle == 0 && playUrl != resolvedUrl) {
                streamHandle = tryCreateStream(resolvedUrl)
            }

            // 3. Fallback: try shoutcast semicolon URL format if not already ending in ;
            if (streamHandle == 0 && !resolvedUrl.endsWith(";") && !resolvedUrl.endsWith("/;")) {
                val scUrl = if (resolvedUrl.endsWith("/")) "$resolvedUrl;" else "$resolvedUrl/;"
                val scProxyUrl = buildProxyUrl(scUrl)
                streamHandle = tryCreateStream(scProxyUrl)
                if (streamHandle == 0) {
                    streamHandle = tryCreateStream(scUrl)
                }
            }

            if (streamHandle == 0) {
                val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                val autoReconnect = sp.getBoolean("auto_reconnect", true)
                if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                    triggerReconnect("stream_create_failed")
                    return@Thread
                }
                reconnectAttempts = 0
                isPlaying = false
                val errCode = BASS.BASS_ErrorGetCode()
                val errIntent = Intent("com.neyman.radio.ERROR")
                errIntent.putExtra("error_code", errCode)
                sendLocalBroadcast(errIntent)
                showNotification(PlaybackStateCompat.STATE_PAUSED, getStr("Ошибка: $errCode", "Hata: $errCode"), currentStationName)
                return@Thread
            }

            requestAudioFocus()
            registerNoisyReceiver()
            applyEqualizerSettings()
            BASS.BASS_ChannelPlay(streamHandle, false)
            isPlaying = true
            currentStation = currentStationName
            currentTitle = currentSongTitle
            reconnectAttempts = 0
            isReconnecting = false
            consecutiveStallSeconds = 0

            extractAndBroadcastAudioInfo()

            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, currentStationName)
            
            val readyIntent = Intent("com.neyman.radio.READY").apply {
                putExtra("name", currentStationName)
                if (currentSongTitle.isNotEmpty()) {
                    putExtra("title", currentSongTitle)
                }
            }
            sendLocalBroadcast(readyIntent)
            
            val stateIntent = Intent("com.neyman.radio.STATE")
            stateIntent.putExtra("isPlaying", true)
            sendLocalBroadcast(stateIntent)

            startMetadataTimer()
        }.start()
    }

    private fun togglePlayback() {
        if (streamHandle == 0) return
        val activeStatus = BASS.BASS_ChannelIsActive(streamHandle)
        if (activeStatus == BASS.BASS_ACTIVE_PLAYING) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun startMetadataTimer() {
        metadataTimer = Timer()
        metadataTimer?.schedule(object : TimerTask() {
            override fun run() { pollMetadata() }
        }, 1000, 2000)
    }

    private fun pollMetadata() {
        if (streamHandle != 0) {
            val active = BASS.BASS_ChannelIsActive(streamHandle)
            if (active == BASS.BASS_ACTIVE_STALLED) {
                consecutiveStallSeconds += 2
                if (consecutiveStallSeconds >= 4) {
                    consecutiveStallSeconds = 0
                    triggerReconnect("stalled")
                    return
                }
            } else if (active == BASS.BASS_ACTIVE_PLAYING) {
                consecutiveStallSeconds = 0
            }
        }
        if (streamHandle == 0 || BASS.BASS_ChannelIsActive(streamHandle) != BASS.BASS_ACTIVE_PLAYING) return
        
        var newTitle = ""
        
        val metaObj = BASS.BASS_ChannelGetTags(streamHandle, BASS.BASS_TAG_META)
        val meta = when (metaObj) {
            is String -> metaObj
            is java.nio.ByteBuffer -> {
                val bytes = ByteArray(metaObj.remaining())
                metaObj.get(bytes)
                try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { String(bytes) }
            }
            is ByteArray -> {
                try { String(metaObj, Charsets.UTF_8) } catch (e: Exception) { String(metaObj) }
            }
            else -> null
        }
        if (meta != null) {
            val matcher = Pattern.compile("StreamTitle='([^']*)';").matcher(meta)
            if (matcher.find()) newTitle = matcher.group(1)?.trim() ?: ""
        }

        if (newTitle.isEmpty()) {
            val oggTags = BASS.BASS_ChannelGetTags(streamHandle, BASS.BASS_TAG_OGG)
            if (oggTags is Array<*>) {
                var artist = ""
                var title = ""
                for (tag in oggTags) {
                    val s = tag?.toString() ?: continue
                    if (s.startsWith("TITLE=", ignoreCase = true)) title = s.substring(6).trim()
                    if (s.startsWith("ARTIST=", ignoreCase = true)) artist = s.substring(7).trim()
                }
                if (title.isNotEmpty()) {
                    newTitle = if (artist.isNotEmpty()) "$artist - $title" else title
                }
            }
        }

        if (newTitle.isEmpty()) {
            val hlsExtInf = BASS.BASS_ChannelGetTags(streamHandle, BASSHLS.BASS_TAG_HLS_EXTINF) as? String
            if (hlsExtInf != null && hlsExtInf.contains(",")) {
                val candidate = hlsExtInf.substringAfter(",").trim()
                if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                    newTitle = candidate
                }
            }
        }

        if (newTitle.isEmpty() && currentUrl.contains("streamtheworld.com")) {
            try {
                val path = URL(currentUrl).path
                var mountName = path.substringAfterLast("/").substringBefore(".")
                if (mountName.endsWith("_SC")) mountName = mountName.removeSuffix("_SC")
                
                val apiUrl = "https://np.tritondigital.com/public/nowplaying?mountName=$mountName&numberToFetch=1"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val xml = conn.inputStream.bufferedReader().readText()
                
                val titleMatcher = Pattern.compile("<property name=\"cue_title\">([^<]*)</property>").matcher(xml)
                if (titleMatcher.find()) {
                    val parsedTitle = titleMatcher.group(1)?.trim() ?: ""
                    if (parsedTitle.isNotEmpty() && !parsedTitle.contains("STW_AD") && !parsedTitle.contains("ADVERTISEMENT")) {
                        newTitle = parsedTitle
                    }
                }
            } catch(e: Exception) {}
        }

        if (newTitle.isEmpty()) {
            try {
                val parsedUrl = URL(currentUrl)
                val host = parsedUrl.host
                val port = if (parsedUrl.port == -1) (if (parsedUrl.protocol == "https") 443 else 80) else parsedUrl.port
                val conn = URL("${parsedUrl.protocol}://$host:$port/stats?json=1").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                newTitle = json.optString("songtitle", "")
            } catch(e: Exception) {}
        }

        if (newTitle.isNotEmpty() && !newTitle.equals(currentStationName, ignoreCase = true) && newTitle != currentSongTitle) {
            currentSongTitle = newTitle
            currentTitle = newTitle
            try { SettingsManager(this).addTrackToHistory(newTitle) } catch (e: Exception) {}
            
            val intent = Intent("com.neyman.radio.METADATA")
            intent.putExtra("title", newTitle)
            sendLocalBroadcast(intent)
            
            mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, newTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentStationName)
                .build())
                
            showNotification(PlaybackStateCompat.STATE_PLAYING, newTitle)
        }
    }

    private fun updateSessionState(state: Int) {
        val closeCustomAction = PlaybackStateCompat.CustomAction.Builder(
            "ACTION_STOP_SERVICE",
            getStr("Закрыть", "Kapat"), 
            android.R.drawable.ic_menu_close_clear_cancel
        ).build()

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or 
                        PlaybackStateCompat.ACTION_STOP)
            .addCustomAction(closeCustomAction)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun showNotification(state: Int, textToDisplay: String, explicitTitle: String? = null) {
        val channelId = "radio_playback_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Radio Playback", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
        val isBuffering = state == PlaybackStateCompat.STATE_BUFFERING
        
        val playPauseIcon = if (isPlaying || isBuffering) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseActionName = if (isPlaying || isBuffering) getStr("Пауза", "Duraklat") else getStr("Воспроизвести", "Oynat")

        val playPauseIntent = Intent(this, PlaybackService::class.java).apply { action = "TOGGLE" }
        val playPausePending = PendingIntent.getService(this, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val prevIntent = Intent(this, PlaybackService::class.java).apply { action = "PREV" } 
        val prevPending = PendingIntent.getService(this, 2, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(this, PlaybackService::class.java).apply { action = "NEXT" } 
        val nextPending = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val closeIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_SERVICE" }
        val closePending = PendingIntent.getService(this, 4, closeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val finalTitle = explicitTitle ?: currentStationName

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(finalTitle)
            .setContentText(textToDisplay)
            .addAction(android.R.drawable.ic_media_previous, getStr("Предыдущая", "Önceki"), prevPending)
            .addAction(playPauseIcon, playPauseActionName, playPausePending)
            .addAction(android.R.drawable.ic_media_next, getStr("Следующая", "Sonraki"), nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getStr("Закрыть", "Kapat"), closePending) 
            .setDeleteIntent(closePending)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true) 
                .setCancelButtonIntent(closePending))
            .setOngoing(isPlaying || isBuffering)
            .build()

        startForeground(1001, notification)
    }

    private fun sendActionToActivity(actionStr: String) {
        val intent = Intent(actionStr)
        sendLocalBroadcast(intent)
    }

    private fun stopServiceAndApp() {
        isPlaying = false
        currentStation = ""
        currentTitle = ""
        sendActionToActivity("com.neyman.radio.STOP_APP")
        stopSelf()
    }

    override fun onDestroy() {
        isPlaying = false
        currentStation = ""
        currentTitle = ""
        metadataTimer?.cancel()
        reconnectHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        try { proxyServerSocket?.close() } catch (e: Exception) {}
        proxyExecutor.shutdownNow()
        if (streamHandle != 0) {
            BASS.BASS_StreamFree(streamHandle)
        }
        BASS.BASS_Free()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}