package com.example.widgetflottant

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Une fenêtre flottante contenant un widget.
 * Les commandes (barre de déplacement + ✕ en haut, poignée ◢ en bas à droite) se superposent au widget,
 * disparaissent 0,7 s après le dernier toucher et réapparaissent dès qu'on touche la fenêtre.
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

    private val params = WindowManager.LayoutParams(
        entry.w,
        entry.h,
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
    private var attached = false

    init {
        buildUi()
    }

    private fun screenW() = context.resources.displayMetrics.widthPixels
    private fun screenH() = context.resources.displayMetrics.heightPixels

    private fun buildUi() {
        root.background = GradientDrawable().apply {
            setColor(0xAA000000.toInt())
            cornerRadius = dp(14).toFloat()
        }
        root.clipToOutline = true

        // Le widget occupe toute la fenêtre ; les commandes se superposent.
        root.addView(
            hostView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val grip = TextView(context).apply {
            text = "≡  déplacer"
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
            setBackgroundColor(0xCC202020.toInt())
            addView(grip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(close, LinearLayout.LayoutParams(barH, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP)
        )

        val resizeHandle = TextView(context).apply {
            text = "◢"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(6), dp(2))
            setBackgroundColor(0x66000000)
        }
        root.addView(
            resizeHandle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END)
        )

        controls.add(bar)
        controls.add(resizeHandle)

        attachDrag(grip)
        attachResize(resizeHandle)
    }

    // ---------- Masquage automatique des commandes ----------

    private fun showControls() {
        root.removeCallbacks(hideRunnable)
        controls.forEach { v ->
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
        controls.forEach { v ->
            v.animate().cancel()
            // INVISIBLE : les commandes masquées ne captent plus les touchers, qui vont au widget
            v.animate().alpha(0f).setDuration(FADE_OUT_MS)
                .withEndAction { v.visibility = View.INVISIBLE }
                .start()
        }
    }

    // ---------- Déplacement et redimensionnement ----------

    private fun attachDrag(v: View) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX).toInt())
                        .coerceIn(-(params.width - dp(72)), screenW() - dp(72))
                    params.y = (startY + (e.rawY - downY).toInt())
                        .coerceIn(0, screenH() - barH)
                    update()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        val wDp = (params.width / density).toInt()
        val hDp = (params.height / density).toInt()
        hostView.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
    }

    private fun update() {
        if (attached) wm.updateViewLayout(root, params)
    }

    private fun persist() {
        entry.x = params.x
        entry.y = params.y
        entry.w = params.width
        entry.h = params.height
        WidgetStore.update(context, entry)
    }

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
        root.post { notifySize() }
        scheduleHide() // les commandes sont visibles à l'ouverture puis se masquent après 0,7 s
    }

    fun hide() {
        if (!attached) return
        root.removeCallbacks(hideRunnable)
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
    }
}
