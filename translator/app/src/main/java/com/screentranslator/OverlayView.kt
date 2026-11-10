package com.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Прозрачный слой поверх экрана: рисует перевод прямо на месте китайского текста. */
class OverlayView(context: Context) : View(context) {

    private var blocks: List<TranslatedBlock> = emptyList()
    private val density = resources.displayMetrics.density
    private val location = IntArray(2)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 15, 15, 20) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun show(newBlocks: List<TranslatedBlock>) {
        blocks = newBlocks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Окно оверлея может быть сдвинуто (статус-бар, вырез камеры) — компенсируем
        getLocationOnScreen(location)
        val minSize = 9f * density
        val maxSize = 18f * density
        val pad = 2f * density

        for (b in blocks) {
            val left = b.box.left - location[0].toFloat()
            val top = b.box.top - location[1].toFloat()
            val boxW = b.box.width().toFloat()
            val boxH = b.box.height().toFloat()
            // Русский текст длиннее китайского — даём минимум ширины
            val width = max(boxW, 60f * density).toInt()

            // Подбираем размер шрифта, чтобы влезть в исходную рамку
            var size = min(maxSize, max(minSize, boxH * 0.8f))
            var layout: StaticLayout
            while (true) {
                textPaint.textSize = size
                layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= boxH || size <= minSize) break
                size = max(minSize, size * 0.9f)
            }

            val bg = RectF(left - pad, top - pad, left + width + pad, top + max(boxH, layout.height.toFloat()) + pad)
            canvas.drawRoundRect(bg, 4 * density, 4 * density, bgPaint)
            canvas.save()
            canvas.translate(left, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
