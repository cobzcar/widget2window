package com.example.widgetflottant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/** Service au premier plan qui garde le lecteur musique flottant affiché. */
class MusicWindowService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.widgetflottant.MUSIC_STOP"
        private const val CHANNEL_ID = "music_window"
        private const val NOTIF_ID = 44

        @Volatile
        var running = false
    }

    private var window: MusicWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (window == null) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            window = MusicWindow(this, wm) { stopSelf() }.also { it.show() }
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        window?.hide()
        window = null
        super.onDestroy()
    }

    private fun goForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Lecteur musique flottant", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, MusicWindowService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Lecteur musique flottant actif")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
