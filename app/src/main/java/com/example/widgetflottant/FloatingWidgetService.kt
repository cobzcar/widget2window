package com.example.widgetflottant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast

/**
 * Service au premier plan qui héberge les widgets (AppWidgetHost) et les affiche
 * dans des fenêtres flottantes par-dessus les autres applications.
 */
class FloatingWidgetService : Service() {

    companion object {
        const val HOST_ID = 1024
        const val ACTION_SYNC = "com.example.widgetflottant.SYNC"
        const val ACTION_STOP = "com.example.widgetflottant.STOP"
        private const val CHANNEL_ID = "floating_widgets"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var awm: AppWidgetManager
    private lateinit var host: AppWidgetHost
    private val windows = LinkedHashMap<Int, FloatingWindow>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)
        host.startListening()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        sync()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        windows.values.forEach { it.hide() }
        windows.clear()
        try {
            host.stopListening()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** Aligne les fenêtres affichées sur la liste enregistrée. */
    private fun sync() {
        val entries = WidgetStore.load(this)
        val ids = entries.map { it.id }.toSet()
        for (id in windows.keys.toList()) {
            if (id !in ids) windows.remove(id)?.hide()
        }
        for (entry in entries) {
            if (entry.id !in windows) addWindow(entry)
        }
        if (windows.isEmpty()) stopSelf()
    }

    private fun addWindow(entry: FloatingEntry) {
        val info = awm.getAppWidgetInfo(entry.id)
        if (info == null) {
            // le widget n'existe plus (appli désinstallée, etc.)
            WidgetStore.remove(this, entry.id)
            host.deleteAppWidgetId(entry.id)
            return
        }
        try {
            val view = host.createView(this, entry.id, info)
            val window = FloatingWindow(this, wm, entry, view) { id -> closeWindow(id) }
            windows[entry.id] = window
            window.show()
        } catch (e: Exception) {
            windows.remove(entry.id)
            Toast.makeText(this, "Impossible d'afficher le widget #${entry.id}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun closeWindow(id: Int) {
        windows.remove(id)?.hide()
        WidgetStore.remove(this, id)
        host.deleteAppWidgetId(id)
        if (windows.isEmpty()) stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Widgets flottants", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingWidgetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Widgets flottants actifs")
            .setContentText("Touchez pour gérer vos fenêtres")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tout masquer", stop)
            .setOngoing(true)
            .build()
    }
}
