package com.ugwap.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Overlay grid 3x3 (rule of thirds) di atas preview kamera.
 */
class GridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var gridEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!gridEnabled || width == 0 || height == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
    }
}
