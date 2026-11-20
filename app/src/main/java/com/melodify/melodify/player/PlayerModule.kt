package com.melodify.melodify.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSessionService
import com.melodify.melodify.MainActivity
import com.melodify.melodify.MelodifyApp
import com.melodify.melodify.R
import com.melodify.melodify.data.model.AudioTrack

/**
 * Foreground service that keeps playback alive when the app is in the
 * background. It exposes a media session so the system media controls, lock
 * screen widget and Bluetooth headsets can drive playback.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: androidx.media3.session.MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val controller = (application as MelodifyApp).playerController
        mediaSession = androidx.media3.session.MediaSession.Builder(this, controller.exoPlayer())
            .setSessionCallback(object : androidx.media3.session.MediaSession.Callback {})
            .build()
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): androidx.media3.session.MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val controller = (application as MelodifyApp).playerController
        if (!controller.state.value.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            runCatching { player.release() }
            runCatching { release() }
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the currently playing track"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /** Build a media-style notification for the current track. */
    fun buildNotification(track: AudioTrack, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_melodify_note)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album.ifBlank { "Melodify" })
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                R.drawable.ic_skip_previous, "Previous",
                servicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                servicePendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(
                R.drawable.ic_skip_next, "Next",
                servicePendingIntent(ACTION_NEXT)
            )
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "melodify_playback"
        const val ACTION_PLAY = "com.melodify.melodify.PLAY"
        const val ACTION_PAUSE = "com.melodify.melodify.PAUSE"
        const val ACTION_NEXT = "com.melify.melodify.NEXT"
        const val ACTION_PREVIOUS = "com.melodify.melodify.PREVIOUS"

        /** Convenience: build a notification without starting the service. */
        fun notify(context: Context, track: AudioTrack, isPlaying: Boolean) {
            val svc = context as? PlaybackService ?: return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, svc.buildNotification(track, isPlaying))
        }

        private const val NOTIF_ID = 1001
    }
}

/** Compatibility shim for legacy [Service] / [MediaSessionCompat] callers. */
@Suppress("unused")
class LegacyMediaSessionCallback(
    private val controller: PlayerController
) : MediaSessionCompat.Callback() {
    override fun onPlay() = controller.play()
    override fun onPause() = controller.pause()
    override fun onSkipToNext() = controller.next()
    override fun onSkipToPrevious() = controller.previous()
}
