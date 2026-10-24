package com.example.widgetflottant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Petite fenêtre flottante avec un clavier numérique intégré, qui reste au-dessus de l'écran
 * « Débogage sans fil » (sans prendre le focus : la fenêtre d'association des Réglages reste ouverte).
 * Étape 1 : associer avec le code ; étape 2 : se connecter et accorder le droit de lier des widgets.
 */
class AdbOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val packageName: String,
    private val onClose: () -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())

    private val labels = listOf("IP", "Code d'association", "Port d'association", "Port de connexion")
    private val maxLen = listOf(15, 6, 5, 5)
    private val values = mutableListOf(AdbHelper.localIp(), "", "", "")
    private var selected = 1

    private val fieldViews = mutableListOf<TextView>()
    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private lateinit var grantButton: Button

    private val params = WindowManager.LayoutParams(
        dp(300),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(48)
    }

    private val root = LinearLayout(context)
    private var attached = false

    init {
        build()
    }

    private fun build() {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(8), dp(10), dp(10))
        root.background = GradientDrawable().apply {
            setColor(0xF0101820.toInt())
            cornerRadius = dp(12).toFloat()
        }

        // Titre (poignée de déplacement) + fermeture
        val title = TextView(context).apply {
            text = "⠿ Assistant ADB (glisser ici)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(6))
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { onClose() }
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        root.addView(titleRow)
        attachDrag(title)

        status = TextView(context).apply {
            setTextColor(0xFFB8E6FF.toInt())
            textSize = 12f
            minLines = 3
            text = "1) Dans Réglages > Débogage sans fil > « Associer avec un code », " +
                "touche un champ ci-dessous puis saisis le code et le port affichés."
        }
        root.addView(status)

        // Champs sélectionnables
        for (i in labels.indices) {
            val tv = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener {
                    selected = i
                    refreshFields()
                }
            }
            fieldViews.add(tv)
            root.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            )
        }
        refreshFields()

        // Clavier numérique
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫")
        )
        for (row in keys) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val b = TextView(context).apply {
                    text = key
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(0xFF2A3644.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { press(key) }
                }
                rowLayout.addView(
                    b,
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                )
            }
            root.addView(
                rowLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
        }

        pairButton = Button(context).apply {
            text = "1. Associer"
            setOnClickListener { doPair() }
        }
        grantButton = Button(context).apply {
            text = "2. Autoriser les widgets"
            setOnClickListener { doGrant() }
        }
        root.addView(pairButton)
        root.addView(grantButton)
    }

    // ---------- Saisie ----------

    private fun refreshFields() {
        for (i in labels.indices) {
            val tv = fieldViews[i]
            val shown = values[i].ifEmpty { "…" }
            tv.text = "${labels[i]} : $shown"
            tv.background = GradientDrawable().apply {
                setColor(if (i == selected) 0xFF1E5A8C.toInt() else 0xFF222B36.toInt())
                cornerRadius = dp(6).toFloat()
            }
        }
    }

    private fun press(key: String) {
        val current = values[selected]
        when (key) {
            "⌫" -> if (current.isNotEmpty()) values[selected] = current.dropLast(1)
            "." -> if (selected == 0 && current.length < maxLen[0]) values[selected] = "$current."
            else -> if (current.length < maxLen[selected]) values[selected] = current + key
        }
        refreshFields()
    }

    private fun say(message: String) {
        main.post { status.text = message }
    }

    private fun setBusy(busy: Boolean) {
        main.post {
            pairButton.isEnabled = !busy
            grantButton.isEnabled = !busy
        }
    }

    // ---------- Actions ----------

    private fun doPair() {
        val host = values[0]
        val code = values[1]
        val port = values[2].toIntOrNull()
        if (host.isEmpty() || code.length != 6 || port == null) {
            say("Il faut l'IP, un code à 6 chiffres et le port d'association.")
            return
        }
        say("Association en cours…")
        setBusy(true)
        thread {
            val error = AdbHelper.pair(host, port, code)
            setBusy(false)
            if (error == null) {
                main.post {
                    selected = 3
                    refreshFields()
                }
                say(
                    "Association réussie ✓\n2) Ferme la fenêtre d'association, lis le « Port de connexion » " +
                        "(en haut de la page Débogage sans fil, après l'IP), saisis-le ici puis touche « 2. Autoriser »."
                )
            } else {
                say(error)
            }
        }
    }

    private fun doGrant() {
        val host = values[0]
        val port = values[3].toIntOrNull()
        if (host.isEmpty() || port == null) {
            say("Il faut l'IP et le port de connexion.")
            return
        }
        say("Connexion et autorisation en cours…")
        setBusy(true)
        thread {
            val error = AdbHelper.grantBind(host, port, packageName)
            setBusy(false)
            if (error == null) {
                say("Autorisation accordée ✓\nRetourne dans l'appli et choisis un widget. Tu peux fermer cette fenêtre (✕).")
            } else {
                say(error)
            }
        }
    }

    // ---------- Fenêtre ----------

    private fun attachDrag(handle: View) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    if (attached) wm.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }
    }

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
    }

    fun hide() {
        if (!attached) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
    }
}
