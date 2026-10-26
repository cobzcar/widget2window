
package com.example.widgetflottant

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowManager

/**
 * Fond translucide des fenêtres flottantes.
 * On désactive le flou système car certaines box le propagent
 * à tout l'arrière-plan au lieu de le limiter au widget.
 */
object GlassStyle {
    private const val FALLBACK_ALPHA_TINT = 0xA0101418.toInt()
    private const val BORDER = 0x33FFFFFF

    fun apply(
        context: Context,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        view: View,
        cornerPx: Float,
        strokePx: Int
    ): Boolean {
        view.background = GradientDrawable().apply {
            setColor(FALLBACK_ALPHA_TINT)
            cornerRadius = cornerPx
            setStroke(strokePx, BORDER)
        }
        return false
    }
}
