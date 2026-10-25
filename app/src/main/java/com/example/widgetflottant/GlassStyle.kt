package com.example.widgetflottant

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * Fond « verre dépoli » des fenêtres flottantes : translucide, avec le contenu derrière flouté.
 * Le flou demande Android 12 ou plus et un appareil qui sait flouter entre fenêtres.
 * Sinon, on retombe sur un fond translucide un peu plus sombre (pour que le texte reste lisible).
 */
object GlassStyle {
    private const val BLUR_TINT = 0x40101418
    private const val FALLBACK_ALPHA_TINT = 0xA0101418L
    private const val BORDER = 0x33FFFFFF
    private const val BLUR_RADIUS_DP = 28

    /** Applique le fond à [view] et active le flou dans [params]. Retourne true si le flou est actif. */
    fun apply(
        context: Context,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        view: View,
        cornerPx: Float,
        strokePx: Int
    ): Boolean {
        var blur = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blur = try {
                wm.isCrossWindowBlurEnabled
            } catch (e: Throwable) {
                false
            }
            if (blur) {
                params.blurBehindRadius = (BLUR_RADIUS_DP * context.resources.displayMetrics.density).toInt()
                @Suppress("DEPRECATION")
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
        }
        view.background = GradientDrawable().apply {
            setColor(if (blur) BLUR_TINT else FALLBACK_ALPHA_TINT.toInt())
            cornerRadius = cornerPx
            setStroke(strokePx, BORDER)
        }
        return blur
    }
}
