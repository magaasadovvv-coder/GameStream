package com.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Один переведённый фрагмент: где он был на экране и что там написано по-русски. */
data class TranslatedBlock(val box: Rect, val text: String)

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

    suspend fun process(bitmap: Bitmap): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<TranslatedBlock>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            // Строки блока склеиваем без пробелов — в китайском их нет
            val source = block.lines.joinToString("") { it.text.trim() }
            if (!hasChinese(source)) continue
            out += TranslatedBlock(box, translate(source))
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
}
