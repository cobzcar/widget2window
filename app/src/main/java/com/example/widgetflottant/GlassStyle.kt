package com.example.widgetflottant

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Fond translucide des fenêtres flottantes.
 *
 * Par défaut : simple fond translucide sombre. Le flou système « derrière la fenêtre » est désactivé,
 * car certaines box le propagent à tout l'écran au lieu de le limiter au widget.
 * Option « flou d'arrière-plan » (réglage de l'appli, désactivé par défaut) : le flou est limité à la zone
 * de la fenêtre (voir [OverlayHost]) ; le fond est alors plus léger, puisque le flou aide la lisibilité.
 */
object GlassStyle {
    private const val PREFS = "settings"
    private const val KEY_BLUR = "glass_blur"

    private const val BLUR_TINT = 0x40101418
    private val PLAIN_TINT = 0xA0101418L.toInt()
    private const val BORDER = 0x33FFFFFF

    fun isBlurEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLUR, false)

    fun setBlurEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLUR, enabled).apply()
    }

    /** Applique le fond à [view]. [blur] : le flou d'arrière-plan est actif pour cette fenêtre. */
    fun apply(view: View, cornerPx: Float, strokePx: Int, blur: Boolean) {
        view.background = GradientDrawable().apply {
            setColor(if (blur) BLUR_TINT else PLAIN_TINT)
            cornerRadius = cornerPx
            setStroke(strokePx, BORDER)
        }
    }
}
