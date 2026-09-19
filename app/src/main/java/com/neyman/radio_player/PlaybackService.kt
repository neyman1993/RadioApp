package com.neyman.radio_player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        val sp = getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val bufferSec = sp.getInt("buffer_seconds", 5)
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferSec * 1000, 
                bufferSec * 1000 * 2, 
                bufferSec * 1000, 
                bufferSec * 1000
            ).build()

        // Включаем запрос метаданных в аудиопотоке (нативно, без заиканий)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // Перехватываем только команды, НЕ создавая дубликаты кнопок
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
            override fun seekToNext() { sendBroadcast(Intent("com.neyman.radio.NEXT")) }
            override fun seekToPrevious() { sendBroadcast(Intent("com.neyman.radio.PREV")) }
            override fun seekToNextMediaItem() { sendBroadcast(Intent("com.neyman.radio.NEXT")) }
            override fun seekToPreviousMediaItem() { sendBroadcast(Intent("com.neyman.radio.PREV")) }
        }
        
        // Создаем ТОЛЬКО ОДНУ кастомную кнопку - "Закрыть"
        val stopButton = CommandButton.Builder()
            .setDisplayName("Закрыть")
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(SessionCommand("ACTION_STOP_APP", Bundle.EMPTY))
            .build()

        val callback = object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == "ACTION_STOP_APP") {
                    player.stop()
                    player.clearMediaItems()
                    sendBroadcast(Intent("com.neyman.radio.STOP_APP"))
                    stopSelf()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand("ACTION_STOP_APP", Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(availableSessionCommands, connectionResult.availablePlayerCommands)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(callback)
            .setCustomLayout(listOf(stopButton))
            .build()
    }

    // Если смахнуть приложение из недавних, радио полностью выключится
    override fun onTaskRemoved(rootIntent: Intent?) {
        player.stop()
        player.clearMediaItems()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}