package com.neyman.radio_player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.un4seen.bass.BASS
import java.util.regex.Pattern

class PlaybackService : Service() {

    private var streamHandle = 0
    private lateinit var mediaSession: MediaSessionCompat
    private var currentUrl: String = ""
    private var currentStationName: String = ""
    private var isTr = false

    // Обработчик метаданных, который BASS дергает автоматически при смене песни в потоке
    private val syncProc = BASS.SYNCPROC { _, channel, _, _ ->
        updateMetadataFromBass(channel)
    }

    override fun onCreate() {
        super.onCreate()
        val locale = resources.configuration.locales.get(0).language
        isTr = locale == "tr"

        // Инициализируем BASS как в Python: (device = -1, freq = 44100, flags = 0)
        BASS.BASS_Init(-1, 44100, 0)
        
        // Маскируемся под браузер, чтобы StreamTheWorld не блокировал нас
        BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

        // Пытаемся подгрузить плагин HLS для m3u8 (не страшно, если его нет)
        try {
            val hlsPath = applicationInfo.nativeLibraryDir + "/libbasshls.so"
            BASS.BASS_PluginLoad(hlsPath, 0)
        } catch (e: Exception) { }

        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayback() }
                override fun onPause() { togglePlayback() }
                override fun onStop() { stopServiceAndApp() }
                override fun onSkipToNext() { sendActionToActivity("com.neyman.radio.NEXT") }
                override fun onSkipToPrevious() { sendActionToActivity("com.neyman.radio.PREV") }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_STATION" -> {
                currentUrl = intent.getStringExtra("url") ?: ""
                currentStationName = intent.getStringExtra("name") ?: ""
                startBassPlayback()
            }
            "TOGGLE" -> togglePlayback()
            "STOP_SERVICE" -> stopServiceAndApp()
        }
        return START_NOT_STICKY
    }

    private fun startBassPlayback() {
        // Запуск сети делаем в фоне, чтобы не заморозить интерфейс
        Thread {
            if (streamHandle != 0) {
                BASS.BASS_StreamFree(streamHandle)
            }

            // Настройка буфера
            val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
            val bufferSec = sp.getInt("buffer_seconds", 5)
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, bufferSec * 1000)

            // Создаем поток с автоочисткой
            streamHandle = BASS.BASS_StreamCreateURL(currentUrl, 0, BASS.BASS_STREAM_AUTOFREE or BASS.BASS_STREAM_STATUS, null, null)
            
            if (streamHandle == 0) {
                sendActionToActivity("com.neyman.radio.ERROR")
                return@Thread
            }

            // Вешаем крючок на получение метаданных (Icy Meta)
            BASS.BASS_ChannelSetSync(streamHandle, BASS.BASS_SYNC_META, 0, syncProc, null)
            BASS.BASS_ChannelPlay(streamHandle, false)

            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, "Radyo Yayını")
            sendActionToActivity("com.neyman.radio.READY")
            sendStateToActivity(true)

            // Пробуем сразу достать название
            updateMetadataFromBass(streamHandle)
        }.start()
    }

    private fun togglePlayback() {
        if (streamHandle == 0) return
        val activeStatus = BASS.BASS_ChannelIsActive(streamHandle)
        
        if (activeStatus == BASS.BASS_ACTIVE_PLAYING) {
            BASS.BASS_ChannelPause(streamHandle)
            updateSessionState(PlaybackStateCompat.STATE_PAUSED)
            showNotification(PlaybackStateCompat.STATE_PAUSED, currentStationName)
            sendStateToActivity(false)
        } else {
            BASS.BASS_ChannelPlay(streamHandle, false)
            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(PlaybackStateCompat.STATE_PLAYING, currentStationName)
            sendStateToActivity(true)
        }
    }

    private fun updateMetadataFromBass(channel: Int) {
        var newTitle = ""

        // Читаем стандартные ICY метаданные (JoyTurk, Mydonose и т.д.)
        val meta = BASS.BASS_ChannelGetTags(channel, BASS.BASS_TAG_META) as? String
        if (meta != null) {
            val matcher = Pattern.compile("StreamTitle='([^']*)';").matcher(meta)
            if (matcher.find()) {
                newTitle = matcher.group(1)?.trim() ?: ""
            }
        } else {
            // Резерв: некоторые серверы Icecast отдают данные в формате OGG тегов
            val oggMeta = BASS.BASS_ChannelGetTags(channel, BASS.BASS_TAG_OGG) as? Array<String>
            if (oggMeta != null) {
                for (tag in oggMeta) {
                    if (tag.lowercase().startsWith("title=")) {
                        newTitle = tag.substring(6).trim()
                        break
                    }
                }
            }
        }

        if (newTitle.isNotEmpty()) {
            val intent = Intent("com.neyman.radio.METADATA")
            intent.putExtra("title", newTitle)
            sendBroadcast(intent)
            
            mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, newTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentStationName)
                .build())
                
            showNotification(PlaybackStateCompat.STATE_PLAYING, newTitle)
        }
    }

    private fun updateSessionState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or 
                        PlaybackStateCompat.ACTION_STOP)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun showNotification(state: Int, songTitle: String) {
        val channelId = "radio_playback_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Radio Playback", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseActionName = if (isPlaying) "Pause" else "Play"

        val playPauseIntent = Intent(this, PlaybackService::class.java).apply { action = "TOGGLE" }
        val playPausePending = PendingIntent.getService(this, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val prevIntent = Intent(this, PlaybackService::class.java).apply { action = "PREV" } // Это перехватится через сессию
        val prevPending = PendingIntent.getService(this, 2, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(this, PlaybackService::class.java).apply { action = "NEXT" } // Это перехватится через сессию
        val nextPending = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val closeIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_SERVICE" }
        val closePending = PendingIntent.getService(this, 4, closeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentStationName)
            .setContentText(songTitle)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevPending)
            .addAction(playPauseIcon, playPauseActionName, playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePending)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()

        startForeground(1001, notification)
    }

    private fun sendActionToActivity(actionStr: String) {
        sendBroadcast(Intent(actionStr))
    }

    private fun sendStateToActivity(isPlaying: Boolean) {
        val intent = Intent("com.neyman.radio.STATE")
        intent.putExtra("isPlaying", isPlaying)
        sendBroadcast(intent)
    }

    private fun stopServiceAndApp() {
        sendActionToActivity("com.neyman.radio.STOP_APP")
        stopSelf()
    }

    override fun onDestroy() {
        if (streamHandle != 0) {
            BASS.BASS_StreamFree(streamHandle)
        }
        BASS.BASS_Free()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}