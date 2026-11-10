package com.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Один переведённый фрагмент: где он был на экране, русский текст,
 * цвет фона под ним и подходящий цвет букв — чтобы перевод выглядел как родная надпись.
 */
data class TranslatedBlock(
    val box: Rect,
    val text: String,
    val bg: Int,
    val fg: Int,
    val lineHeight: Int,
)

/** Распознавание китайского текста + перевод на русский. Всё работает на телефоне, офлайн. */
object Engine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
    }

    // Кэш переводов: в играх одни и те же надписи повторяются постоянно
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1000
    }

    /** Скачивает языковые модели (~30–60 МБ), нужно один раз. */
    suspend fun downloadModels() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    /** [skip] — области, где перевод уже показан: текст в них пропускаем. */
    suspend fun process(bitmap: Bitmap, skip: List<Rect> = emptyList()): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<TranslatedBlock>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            if (skip.any { Rect.intersects(it, box) }) continue
            // Строки блока склеиваем без пробелов — в китайском их нет
            val source = block.lines.joinToString("") { it.text.trim() }
            if (!hasChinese(source)) continue
            val heights = block.lines.mapNotNull { it.boundingBox?.height() }
            val lineHeight = if (heights.isEmpty()) box.height() else heights.sum() / heights.size
            val bg = sampleBackground(bitmap, box)
            out += TranslatedBlock(box, translate(source), bg, contrastTo(bg), lineHeight)
        }
        return out
    }

    private suspend fun translate(source: String): String {
        synchronized(cache) { cache[source] }?.let { return it }
        val translated = translator.translate(source).await()
        synchronized(cache) { cache[source] = translated }
        return translated
    }

    private fun hasChinese(s: String) =
        s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** Средний цвет по рамке вокруг надписи — это и есть её фон. */
    private fun sampleBackground(bmp: Bitmap, box: Rect): Int {
        val l = (box.left - 3).coerceIn(0, bmp.width - 1)
        val r = (box.right + 3).coerceIn(0, bmp.width - 1)
        val t = (box.top - 3).coerceIn(0, bmp.height - 1)
        val b = (box.bottom + 3).coerceIn(0, bmp.height - 1)
        var rs = 0; var gs = 0; var bs = 0; var n = 0
        fun add(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
        }
        val steps = 12
        for (i in 0..steps) {
            val x = l + (r - l) * i / steps
            add(x, t); add(x, b)
            val y = t + (b - t) * i / steps
            add(l, y); add(r, y)
        }
        return Color.rgb(rs / n, gs / n, bs / n)
    }

    /** Мягкий светлый или тёмный цвет букв — что лучше читается на этом фоне. */
    private fun contrastTo(bg: Int): Int {
        val lum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
        return if (lum > 150) Color.rgb(28, 28, 32) else Color.rgb(240, 240, 240)
    }
}
