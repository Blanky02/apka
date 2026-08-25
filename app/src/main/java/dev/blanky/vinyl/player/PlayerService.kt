package dev.blanky.vinyl.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.blanky.vinyl.MainActivity
import dev.blanky.vinyl.R

/**
 * Foreground service hostujący Media3 MediaSession + ExoPlayer.
 * Repozytorium (PlayerRepository) jest właścicielem kolejki i logiki;
 * serwis dba tylko o sesję, powiadomienie i cykl życia odtwarzacza.
 *
 * Powiadomienie, przyciski w słuchawkach i przejście w foreground obsługuje
 * Media3 (MediaSessionService) — serwis dokłada jedynie kanał powiadomień
 * i małą ikonę.
 */
class PlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val exo = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        player = exo

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openAppIntent())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        // Nie zabijaj odtwarzacza po schowaniu aplikacji z listy ostatnich zadań.
        if (p != null && p.playWhenReady && p.mediaItemCount > 0) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Odtwarzanie",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Kontrolki odtwarzacza Vinyl"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "vinyl_playback"
    }
}
