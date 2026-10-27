package com.example.widgetflottant

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

/**
 * Affiche une vue en superposition. Deux modes :
 * - normal : la vue est ajoutée directement au WindowManager ;
 * - flou : la vue est placée dans une fenêtre de dialogue en superposition, ce qui permet un flou
 *   d'arrière-plan LIMITÉ à la zone de la fenêtre (le « flou derrière » du WindowManager, lui,
 *   floute tout l'écran).
 * Si le mode flou échoue, on retombe sur le mode normal.
 */
class OverlayHost(
    private val context: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val view: View,
    private val useBlur: Boolean,
    private val blurRadiusPx: Int
) {
    companion object {
        /** Vrai si l'appareil sait flouter entre fenêtres (Android 12+ et GPU compatible). */
        fun blurSupported(wm: WindowManager): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            return try {
                wm.isCrossWindowBlurEnabled
            } catch (e: Throwable) {
                false
            }
        }
    }

    private var dialog: Dialog? = null
    private var viewAdded = false

    /** Vrai si la fenêtre a été créée en mode flou (sinon, mode normal, par choix ou après un échec). */
    var blurActive = false
        private set

    fun add() {
        if (useBlur) {
            try {
                addAsDialog()
                saveStatus("Flou limité à la fenêtre demandé (fenêtre de dialogue créée, rayon ${blurRadiusPx} px)")
                return
            } catch (e: Throwable) {
                dialog?.let { runCatching { it.dismiss() } }
                dialog = null
                // la vue a pu être attachée au dialogue : on la détache avant le mode normal
                (view.parent as? ViewGroup)?.removeView(view)
                saveStatus("Échec du mode flou : ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        } else {
            saveStatus("Flou non demandé (interrupteur désactivé, ou flou entre fenêtres indisponible)")
        }
        wm.addView(view, params)
        viewAdded = true
    }

    /** Mémorise le dernier mode obtenu, affiché dans l'appli pour comprendre ce qui se passe. */
    private fun saveStatus(text: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("blur_status", text).apply()
    }

    private fun addAsDialog() {
        val themed = ContextThemeWrapper(context, R.style.OverlayDialog)
        val d = Dialog(themed)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val w = d.window ?: throw IllegalStateException("pas de fenêtre")
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        w.setGravity(Gravity.TOP or Gravity.START)
        applyGeometry(w)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.setBackgroundBlurRadius(blurRadiusPx)
        }
        d.show()
        dialog = d
        blurActive = true
    }

    private fun applyGeometry(w: Window) {
        val a = w.attributes
        a.x = params.x
        a.y = params.y
        a.width = params.width
        a.height = params.height
        w.attributes = a
    }

    /** À appeler après avoir modifié x, y, width ou height dans [params]. */
    fun update() {
        val d = dialog
        if (d != null) {
            d.window?.let { applyGeometry(it) }
        } else if (viewAdded) {
            wm.updateViewLayout(view, params)
        }
    }

    fun remove() {
        val d = dialog
        if (d != null) {
            runCatching { d.dismiss() }
            dialog = null
        } else if (viewAdded) {
            runCatching { wm.removeView(view) }
            viewAdded = false
        }
    }
}
