package dev.blanky.vinyl.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.IBinder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationManager
import dev.blanky.vinyl.MainActivity
import dev.blanky.vinyl.R

/**
 * Foreground service hostujący Media3 MediaSession + ExoPlayer.
 * Repozytorium (PlayerRepository) jest właścicielem kolejki i logiki;
 * serwis dba tylko o sesję, powiadomienie i cykl życia odtwarzacza.
 */
class PlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var notificationManager: MediaStyleNotificationManager? = null

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

        val notificationMgr = MediaStyleNotificationManager(this, CHANNEL_ID)
        notificationManager = notificationMgr

        val session = MediaSession.Builder(this, exo)
            .setSessionActivity(openAppIntent())
            .setMediaButtonReceiver(MediaButtonReceiver.getDefaultIntentFilter())
            .build()
        mediaSession = session
        session.setActive(true)

        session.getController().addListener(object : MediaController.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
            override fun onPlaybackStateChanged(state: Int) = updateNotification()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNotification()
        })

        // Serwis startuje zanim kolejka zostanie przygotowana —
        // musi być natychmiast startForeground (Android 12+), więc
        // pokazujemy najpierw powiadomienie zastępcze.
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onGetSession(): MediaSession = mediaSession!!

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p != null && p.playWhenReady) return // nie zabijaj odtwarzacza po schowaniu z tasków
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun updateNotification() {
        val session = mediaSession ?: return
        val p = player ?: return
        val notifMgr = notificationManager ?: return

        val builder = Notification.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(session.sessionActivity)

        val notification = if (p.mediaItemCount == 0) {
            builder
                .setContentTitle("Vinyl")
                .setContentText("W gotowości")
                .setOngoing(true)
                .build()
        } else {
            notifMgr.build(session, builder, MediaStyleNotificationManager.DefaultActionListener)
        }
        startForeground(NOTIF_ID, notification)
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
        const val NOTIF_ID = 1
        const val ACTION_START = "dev.blanky.vinyl.action.START"
    }
}
