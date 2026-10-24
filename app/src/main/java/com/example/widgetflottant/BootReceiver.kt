package com.example.widgetflottant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Lance le lecteur musique flottant quand l'appareil démarre (ou après une mise à jour de l'appli),
 * si l'option est activée dans l'appli.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val PREFS = "settings"
        const val KEY_AUTOSTART = "music_autostart"

        fun isAutostartEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

        fun setAutostartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTOSTART, enabled).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isAutostartEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, MusicWindowService::class.java))
        } catch (_: Exception) {
            // démarrage refusé par le système : rien à faire, l'utilisateur peut lancer depuis l'appli
        }
    }
}
