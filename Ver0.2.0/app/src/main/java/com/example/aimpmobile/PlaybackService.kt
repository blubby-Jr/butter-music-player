package com.example.aimpmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Foreground-сервіс: тримає процес живим, показує сповіщення з кнопками (⏮ ⏯ ⏭),
 * підтримує MediaSession (кнопки гарнітури, екран блокування).
 */
class PlaybackService : Service() {
    private var session: MediaSession? = null
    private val observer: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW))
        val s = MediaSession(this, "AimpMobile")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { Controller.resume() }
            override fun onPause() { Controller.pause() }
            override fun onSkipToNext() { Controller.next(false) }
            override fun onSkipToPrevious() { Controller.prev() }
            override fun onStop() { Controller.stopAll() }
        })
        s.isActive = true
        session = s
        Controller.observers.add(observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Controller.toggle()
            ACTION_NEXT -> Controller.next(false)
            ACTION_PREV -> Controller.prev()
            ACTION_STOP -> { Controller.stopAll(); stopSelf(); return START_NOT_STICKY }
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun action(code: Int, act: String, icon: Int, label: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun refresh() {
        val t = Controller.current
        val playing = Controller.isPlaying
        val sess = session
        if (sess != null && t != null) {
            sess.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                    .build()
            )
            sess.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                    )
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, Core.engine.positionMs, 1f)
                    .build()
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, "playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "AIMP Mobile")
            .setContentText(t?.artist ?: "")
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(action(1, ACTION_PREV, android.R.drawable.ic_media_previous, S.t("prev")))
            .addAction(
                action(
                    2, ACTION_TOGGLE,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    S.t(if (playing) "pause" else "play")
                )
            )
            .addAction(action(3, ACTION_NEXT, android.R.drawable.ic_media_next, S.t("next")))
        if (sess != null) {
            b.setStyle(Notification.MediaStyle().setMediaSession(sess.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        ServiceCompat.startForeground(this, 1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onDestroy() {
        Controller.observers.remove(observer)
        session?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.aimpmobile.TOGGLE"
        const val ACTION_NEXT = "com.example.aimpmobile.NEXT"
        const val ACTION_PREV = "com.example.aimpmobile.PREV"
        const val ACTION_STOP = "com.example.aimpmobile.STOP"
    }
}
