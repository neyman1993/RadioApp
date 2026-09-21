package com.neyman.radio_player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.un4seen.bass.BASS
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern

class PlaybackService : Service() {

    private var streamHandle = 0
    private lateinit var mediaSession: MediaSessionCompat
    private var currentUrl: String = ""
    private var currentStationName: String = ""
    private var currentSongTitle: String = ""
    private var isTr = false
    private var metadataTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        val locale = resources.configuration.locales.get(0).language
        isTr = locale == "tr"

        BASS.BASS_Init(-1, 44100, 0)
        BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        
        try {
            val hlsPath = applicationInfo.nativeLibraryDir + "/libbasshls.so"
            BASS.BASS_PluginLoad(hlsPath, 0)
        } catch (e: Exception) { }

        setupMediaSession()
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
        when (intent?.action) {
            "PLAY_STATION" -> {
                currentUrl = intent.getStringExtra("url") ?: ""
                currentStationName = intent.getStringExtra("name") ?: ""
                currentSongTitle = ""
                
                updateSessionState(PlaybackStateCompat.STATE_BUFFERING)
                showNotification(PlaybackStateCompat.STATE_BUFFERING, getStr("Загрузка...", "Yükleniyor..."))
                
                startBassPlayback()
            }
            "TOGGLE" -> togglePlayback()
            "STOP_SERVICE" -> stopServiceAndApp()
        }
        return START_NOT_STICKY
    }

    private fun startBassPlayback() {
        metadataTimer?.cancel()

        Thread {
            if (streamHandle != 0) {
                BASS.BASS_StreamFree(streamHandle)
            }

            val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
            val bufferSec = sp.getInt("buffer_seconds", 5)
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, bufferSec * 1000)

            streamHandle = BASS.BASS_StreamCreateURL(currentUrl, 0, BASS.BASS_STREAM_AUTOFREE or BASS.BASS_STREAM_STATUS, null, null)
            
            if (streamHandle == 0) {
                sendActionToActivity("com.neyman.radio.ERROR")
                return@Thread
            }

            BASS.BASS_ChannelPlay(streamHandle, false)

            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, currentStationName)
            
            val readyIntent = Intent("com.neyman.radio.READY")
            readyIntent.putExtra("name", currentStationName)
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
            BASS.BASS_ChannelPause(streamHandle)
            updateSessionState(PlaybackStateCompat.STATE_PAUSED)
            showNotification(PlaybackStateCompat.STATE_PAUSED, if (currentSongTitle.isNotEmpty()) currentSongTitle else currentStationName)
            
            val stateIntent = Intent("com.neyman.radio.STATE")
            stateIntent.putExtra("isPlaying", false)
            sendLocalBroadcast(stateIntent)
        } else {
            BASS.BASS_ChannelPlay(streamHandle, false)
            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, if (currentSongTitle.isNotEmpty()) currentSongTitle else currentStationName)
            
            val stateIntent = Intent("com.neyman.radio.STATE")
            stateIntent.putExtra("isPlaying", true)
            sendLocalBroadcast(stateIntent)
        }
    }

    private fun startMetadataTimer() {
        metadataTimer = Timer()
        metadataTimer?.schedule(object : TimerTask() {
            override fun run() { pollMetadata() }
        }, 1000, 10000)
    }

    private fun pollMetadata() {
        if (streamHandle == 0 || BASS.BASS_ChannelIsActive(streamHandle) != BASS.BASS_ACTIVE_PLAYING) return
        
        var newTitle = ""
        
        val meta = BASS.BASS_ChannelGetTags(streamHandle, BASS.BASS_TAG_META) as? String
        if (meta != null) {
            val matcher = Pattern.compile("StreamTitle='([^']*)';").matcher(meta)
            if (matcher.find()) newTitle = matcher.group(1)?.trim() ?: ""
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
            getStr("Закрыть", "Kapat"), // Правильный перевод для TalkBack
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

    private fun showNotification(state: Int, textToDisplay: String) {
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

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentStationName)
            .setContentText(textToDisplay)
            // Строгий порядок и перевод кнопок
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
        sendActionToActivity("com.neyman.radio.STOP_APP")
        stopSelf()
    }

    override fun onDestroy() {
        metadataTimer?.cancel()
        if (streamHandle != 0) {
            BASS.BASS_StreamFree(streamHandle)
        }
        BASS.BASS_Free()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}