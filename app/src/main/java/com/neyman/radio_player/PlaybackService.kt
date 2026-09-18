package com.neyman.radio_player

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
        player = ExoPlayer.Builder(this).build()
        
        // Создаем кнопку "Закрыть" для шторки уведомлений
        val stopCommand = SessionCommand("ACTION_STOP_APP", Bundle.EMPTY)
        val stopButton = CommandButton.Builder()
            .setDisplayName("Закрыть")
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(stopCommand)
            .build()

        // Обрабатываем нажатие этой кнопки
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
                    stopSelf() // Полностью убиваем сервис
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setCustomLayout(listOf(stopButton)) // Добавляем нашу кнопку
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}