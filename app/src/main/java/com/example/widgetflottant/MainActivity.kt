package com.example.widgetflottant

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_BIND = 100
        const val REQ_CONFIGURE = 101
        const val REQ_NOTIF = 102
    }

    private lateinit var awm: AppWidgetManager
    private lateinit var host: AppWidgetHost
    private lateinit var statusView: TextView
    private lateinit var overlayButton: Button
    private lateinit var listBox: LinearLayout

    /** Identifiant de widget en cours d'ajout (entre le choix et la fin de la configuration). */
    private var pendingId = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Widgets flottants"
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, FloatingWidgetService.HOST_ID)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply { textSize = 15f }
        overlayButton = Button(this).apply {
            text = "Autoriser l'affichage par-dessus les autres applis"
            setOnClickListener { requestOverlay() }
        }
        val addButton = Button(this).apply {
            text = "＋ Choisir un widget"
            setOnClickListener { onAddClicked() }
        }
        val showButton = Button(this).apply {
            text = "Afficher les fenêtres"
            setOnClickListener { startFloating() }
        }
        val hideButton = Button(this).apply {
            text = "Tout masquer"
            setOnClickListener { hideAll() }
        }
        val musicButton = Button(this).apply {
            text = "♪ Lecteur musique flottant"
            setOnClickListener { startMusic() }
        }
        val musicStopButton = Button(this).apply {
            text = "Fermer le lecteur musique"
            setOnClickListener { stopService(Intent(this@MainActivity, MusicWindowService::class.java)) }
        }
        val autostartSwitch = android.widget.Switch(this).apply {
            text = "Lancer le lecteur musique au démarrage"
            isChecked = BootReceiver.isAutostartEnabled(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                BootReceiver.setAutostartEnabled(this@MainActivity, checked)
            }
        }
        val listTitle = TextView(this).apply {
            text = "Widgets enregistrés"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(8))
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(statusView)
        root.addView(overlayButton)
        root.addView(addButton)
        root.addView(showButton)
        root.addView(hideButton)
        root.addView(musicButton)
        root.addView(musicStopButton)
        root.addView(autostartSwitch)
        root.addView(listTitle)
        root.addView(listBox)
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- Interface ----------

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        statusView.text = if (granted) {
            "Affichage par-dessus les autres applis : autorisé ✓"
        } else {
            "Affichage par-dessus les autres applis : autorisation requise ✗"
        }
        overlayButton.visibility = if (granted) View.GONE else View.VISIBLE

        listBox.removeAllViews()
        val entries = WidgetStore.load(this)
        if (entries.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Aucun widget pour l'instant." })
            return
        }
        entries.forEach { entry ->
            val info = awm.getAppWidgetInfo(entry.id)
            val label = if (info != null) widgetLabel(info) else "Widget #${entry.id} (introuvable)"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Button(this).apply {
                text = "Retirer"
                setOnClickListener { removeEntry(entry.id) }
            })
            listBox.addView(row)
        }
    }

    private fun widgetLabel(info: AppWidgetProviderInfo): String {
        val pm = packageManager
        val widgetName = try {
            info.loadLabel(pm)
        } catch (e: Exception) {
            info.provider.shortClassName
        }
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        } catch (e: Exception) {
            info.provider.packageName
        }
        return "$appName — $widgetName"
    }

    // ---------- Permissions et service ----------

    private fun requestOverlay() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        startForegroundService(
            Intent(this, FloatingWidgetService::class.java).setAction(FloatingWidgetService.ACTION_SYNC)
        )
    }

    // ---------- Lecteur musique flottant ----------

    /** Android ne laisse lire les lecteurs de musique en cours qu'aux applis ayant l'« accès aux notifications ». */
    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun startMusic() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        if (hasListenerAccess()) {
            launchMusic()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Autorisation nécessaire")
            .setMessage(
                "Pour afficher la musique en cours (titre, pochette, boutons), l'appli doit avoir " +
                    "« l'accès aux notifications ».\n\n" +
                    "Touche « Ouvrir les réglages » et active « Widgets Flottants ».\n\n" +
                    "Si cet écran n'existe pas sur cet appareil, l'autorisation peut aussi être donnée par ADB :\n" +
                    "cmd notification allow_listener " +
                    "com.example.widgetflottant/com.example.widgetflottant.MediaListenerService"
            )
            .setPositiveButton("Ouvrir les réglages") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Écran introuvable sur cet appareil : utilise la commande ADB", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Lancer quand même") { _, _ -> launchMusic() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchMusic() {
        startForegroundService(Intent(this, MusicWindowService::class.java))
    }

    private fun hideAll() {
        stopService(Intent(this, FloatingWidgetService::class.java))
    }

    private fun removeEntry(id: Int) {
        WidgetStore.remove(this, id)
        host.deleteAppWidgetId(id)
        if (FloatingWidgetService.running) startFloating()
        refresh()
    }

    // ---------- Ajout d'un widget ----------

    private fun onAddClicked() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        val providers = awm.installedProviders
        if (providers.isEmpty()) {
            Toast.makeText(this, "Aucun widget trouvé sur cet appareil", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = providers.associateWith { widgetLabel(it) }
        val sorted = providers.sortedBy { labels[it]?.lowercase() ?: "" }

        val adapter = object : ArrayAdapter<AppWidgetProviderInfo>(this, 0, sorted) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val info = getItem(position)!!
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val icon = ImageView(context)
                try {
                    icon.setImageDrawable(
                        info.loadIcon(context, context.resources.displayMetrics.densityDpi)
                    )
                } catch (e: Exception) {
                    // pas d'icône disponible
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(TextView(context).apply {
                    text = labels[info]
                    textSize = 15f
                    setPadding(dp(12), 0, 0, 0)
                })
                return row
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Choisir un widget")
            .setAdapter(adapter) { _, which -> bindProvider(sorted[which]) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun bindProvider(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
            afterBind(id)
        } else {
            // Android demande à l'utilisateur d'autoriser cette appli à utiliser ce widget
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_BIND)
            } catch (e: Exception) {
                cancelPending()
                Toast.makeText(this, "Impossible de lier ce widget", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun afterBind(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        if (info?.configure != null) {
            // certains widgets ont un écran de configuration à valider d'abord
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                // configuration impossible : on ajoute quand même le widget tel quel
            }
        }
        finishAdd(id)
    }

    private fun finishAdd(id: Int) {
        val existing = WidgetStore.load(this).size
        val entry = FloatingEntry(
            id = id,
            x = dp(32) + existing * dp(24),
            y = dp(120) + existing * dp(24),
            w = dp(240),
            h = dp(180)
        )
        WidgetStore.add(this, entry)
        pendingId = -1
        startFloating()
        refresh()
    }

    private fun cancelPending() {
        if (pendingId != -1) {
            host.deleteAppWidgetId(pendingId)
            pendingId = -1
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            ?.takeIf { it != -1 } ?: pendingId
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK && id != -1) afterBind(id) else cancelPending()
            REQ_CONFIGURE -> if (resultCode == RESULT_OK && id != -1) finishAdd(id) else cancelPending()
        }
    }
}
