package com.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Прозрачный слой поверх экрана: рисует перевод прямо на месте китайского текста.
 * Плашка берёт цвет фона исходной надписи, поэтому перевод не выбивается из интерфейса.
 */
class OverlayView(context: Context) : View(context) {

    private class Item(
        val block: TranslatedBlock,
        val rect: RectF,          // плашка, в координатах экрана
        val layout: StaticLayout,
        val textLeft: Float,
        val textTop: Float,
        val bgPaint: Paint,
    )

    private var items: List<Item> = emptyList()
    private val density = resources.displayMetrics.density
    private val location = IntArray(2)

    val isEmpty get() = items.isEmpty()

    /** Заменить весь перевод. Если на экране то же самое — ничего не трогаем, чтобы не дёргалось. */
    fun show(blocks: List<TranslatedBlock>) {
        if (sameAsShown(blocks)) return
        val wasEmpty = items.isEmpty()
        items = blocks.map(::build)
        if (wasEmpty && items.isNotEmpty()) {
            // Плавное появление вместо резкого
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
        }
        invalidate()
    }

    /** Добавить перевод для нового текста, не трогая уже показанный. */
    fun add(blocks: List<TranslatedBlock>) {
        if (blocks.isEmpty()) return
        items = items + blocks.map(::build)
        invalidate()
    }

    /** Области экрана, закрытые переводом. */
    fun coveredRects(): List<Rect> = items.map {
        Rect(it.rect.left.toInt() - 2, it.rect.top.toInt() - 2, it.rect.right.toInt() + 3, it.rect.bottom.toInt() + 3)
    }

    private fun sameAsShown(blocks: List<TranslatedBlock>): Boolean {
        if (blocks.size != items.size) return false
        for (i in blocks.indices) {
            val a = blocks[i]
            val b = items[i].block
            if (a.text != b.text || abs(a.box.left - b.box.left) > 14 || abs(a.box.top - b.box.top) > 14) return false
        }
        return true
    }

    private fun build(b: TranslatedBlock): Item {
        val minSize = 10f * density
        val maxSize = 17f * density
        val padX = 4f * density
        val padY = 2f * density
        val left = b.box.left.toFloat()
        val top = b.box.top.toFloat()
        val boxH = b.box.height().toFloat()
        val screenW = max(resources.displayMetrics.widthPixels, b.box.right).toFloat()
        val room = max(40f * density, screenW - left - padX)

        // Русский текст длиннее китайского — даём минимум ширины
        var width = min(room, max(b.box.width().toFloat(), 64f * density))
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = b.fg }
        var size = min(maxSize, max(minSize, b.lineHeight * 0.78f))

        fun layoutFor(): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(b.text, 0, b.text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
        }

        // Сначала уменьшаем шрифт, чтобы влезть в исходную рамку…
        var layout = layoutFor()
        while (layout.height > boxH && size > minSize) {
            size = max(minSize, size * 0.92f)
            layout = layoutFor()
        }
        // …а если всё равно не влезло — расширяем плашку вправо, а не вниз
        if (layout.height > boxH && width < room) {
            width = min(room, width * 1.6f)
            layout = layoutFor()
        }

        val rect = RectF(left - padX, top - padY, left + width + padX, top + max(boxH, layout.height.toFloat()) + padY)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(238, Color.red(b.bg), Color.green(b.bg), Color.blue(b.bg))
        }
        return Item(b, rect, layout, left, top, bgPaint)
    }

    override fun onDraw(canvas: Canvas) {
        // Окно оверлея может быть сдвинуто (статус-бар, вырез камеры) — компенсируем
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        val radius = 5f * density
        for (item in items) {
            canvas.drawRoundRect(item.rect, radius, radius, item.bgPaint)
            canvas.save()
            canvas.translate(item.textLeft, item.textTop)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
