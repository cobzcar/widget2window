package com.example.widgetflottant

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Une fenêtre flottante contenant un widget.
 * Les commandes (barre du haut avec ✕, poignée ◢ en bas à droite) se superposent au widget,
 * disparaissent 0,7 s après le dernier toucher et réapparaissent dès qu'on touche la fenêtre.
 * Un appui sur la barre réduit la fenêtre à la seule barre ; un second appui la rouvre.
 */
class FloatingWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val entry: FloatingEntry,
    private val hostView: AppWidgetHostView,
    private val onClose: (Int) -> Unit
) {
    private companion object {
        const val HIDE_DELAY_MS = 700L
        const val FADE_IN_MS = 120L
        const val FADE_OUT_MS = 200L
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val barH = dp(28)
    private val handleSize = dp(36)
    private val minW = dp(120)
    private val minH = dp(90)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var collapsed = entry.collapsed
    private var fullH = entry.h

    private val params = WindowManager.LayoutParams(
        entry.w,
        if (collapsed) barH else fullH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = entry.x
        y = entry.y
    }

    /** Commandes qui se masquent et se réaffichent (barre du haut, poignée de redimensionnement). */
    private val controls = mutableListOf<View>()
    private val hideRunnable = Runnable { hideControls() }

    /** Conteneur principal : observe les touchers (sans les consommer) pour réafficher les commandes. */
    private val root: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> showControls()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleHide()
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    /** Flou d'arrière-plan limité à la fenêtre : option de l'appli, si l'appareil le permet. */
    private val blurOn = GlassStyle.isBlurEnabled(context) && OverlayHost.blurSupported(wm)
    private val host = OverlayHost(context, wm, params, root, blurOn, dp(28))

    private lateinit var grip: TextView
    private lateinit var handle: View
    private var attached = false

    init {
        buildUi()
    }

    private fun screenW() = context.resources.displayMetrics.widthPixels
    private fun screenH() = context.resources.displayMetrics.heightPixels

    private fun buildUi() {
        GlassStyle.apply(root, dp(14).toFloat(), dp(1), blurOn)
        root.clipToOutline = true

        // Le widget occupe toute la fenêtre ; les commandes se superposent.
        root.addView(
            hostView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        grip = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { onClose(entry.id) }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x55000000)
            addView(grip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(close, LinearLayout.LayoutParams(barH, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP))

        val resizeHandle = TextView(context).apply {
            text = "◢"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(6), dp(2))
            setBackgroundColor(0x44000000)
        }
        root.addView(
            resizeHandle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END)
        )
        handle = resizeHandle

        controls.add(bar)
        controls.add(resizeHandle)

        attachDrag(grip)
        attachResize(resizeHandle)
        applyCollapsed()
    }

    // ---------- Réduction à la barre ----------

    /** Met la fenêtre dans l'état réduit (barre seule) ou normal, selon [collapsed]. */
    private fun applyCollapsed() {
        hostView.visibility = if (collapsed) View.GONE else View.VISIBLE
        handle.visibility = if (collapsed) View.GONE else View.VISIBLE
        grip.text = if (collapsed) "≡  agrandir" else "≡  déplacer / réduire"
        params.height = if (collapsed) barH else fullH
        update()
        if (!collapsed) root.post { notifySize() }
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        applyCollapsed()
        persist()
        showControls()
        scheduleHide()
    }

    // ---------- Masquage automatique des commandes ----------

    private fun showControls() {
        root.removeCallbacks(hideRunnable)
        controls.forEach { v ->
            if (collapsed && v === handle) return@forEach
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
    }

    private fun scheduleHide() {
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun hideControls() {
        // Réduite, la barre reste visible : c'est la seule chose à toucher pour rouvrir la fenêtre.
        if (collapsed) return
        controls.forEach { v ->
            v.animate().cancel()
            // INVISIBLE : les commandes masquées ne captent plus les touchers, qui vont au widget
            v.animate().alpha(0f).setDuration(FADE_OUT_MS)
                .withEndAction { v.visibility = View.INVISIBLE }
                .start()
        }
    }

    // ---------- Déplacement, appui (réduire) et redimensionnement ----------

    private fun attachDrag(v: View) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > touchSlop || abs(e.rawY - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (startX + (e.rawX - downX).toInt())
                            .coerceIn(-(params.width - dp(72)), screenW() - dp(72))
                        params.y = (startY + (e.rawY - downY).toInt())
                            .coerceIn(0, screenH() - barH)
                        update()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) persist() else toggleCollapsed()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    persist()
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResize(v: View) {
        var startW = 0
        var startH = 0
        var downX = 0f
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - downX).toInt())
                        .coerceIn(minW, maxOf(minW, screenW()))
                    params.height = (startH + (e.rawY - downY).toInt())
                        .coerceIn(minH, maxOf(minH, screenH()))
                    fullH = params.height
                    update()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    notifySize()
                    persist()
                    true
                }
                else -> false
            }
        }
    }

    /** Prévient le widget de sa nouvelle taille (en dp) pour qu'il adapte sa mise en page. */
    private fun notifySize() {
        if (collapsed) return
        val wDp = (params.width / density).toInt()
        val hDp = (fullH / density).toInt()
        hostView.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
    }

    private fun update() {
        if (attached) host.update()
    }

    private fun persist() {
        entry.x = params.x
        entry.y = params.y
        entry.w = params.width
        entry.h = fullH
        entry.collapsed = collapsed
        WidgetStore.update(context, entry)
    }

    fun show() {
        if (attached) return
        host.add()
        attached = true
        // le fond dépend du mode réellement obtenu (flou système actif ou non)
        GlassStyle.apply(root, dp(14).toFloat(), dp(1), host.blurActive)
        root.post { notifySize() }
        scheduleHide() // les commandes sont visibles à l'ouverture puis se masquent après 0,7 s
    }

    fun hide() {
        if (!attached) return
        root.removeCallbacks(hideRunnable)
        host.remove()
        attached = false
    }
}
