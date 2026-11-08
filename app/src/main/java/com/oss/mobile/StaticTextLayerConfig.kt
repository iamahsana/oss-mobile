package com.oss.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Validated, serializable configuration for an editable text source. */
data class StaticTextLayerConfig(
    val text: String,
    val color: Int = Color.WHITE,
    val textSize: Float = 64f,
    val bold: Boolean = false,
    val alignment: Alignment = Alignment.LEFT
) {
    enum class Alignment { LEFT, CENTER, RIGHT }

    init {
        require(text.isNotBlank() && text.length <= 500) { "Text must contain 1–500 characters" }
        require(textSize.isFinite() && textSize in 16f..256f) { "Text size must be 16–256" }
    }

    fun toMap(): Map<String, String> = mapOf(
        KEY_TEXT to text,
        KEY_COLOR to String.format("#%08X", color),
        KEY_SIZE to textSize.toString(),
        KEY_BOLD to bold.toString(),
        KEY_ALIGNMENT to alignment.name
    )

    companion object {
        const val MODULE_ID = "oss.source.text.static"
        const val KEY_TEXT = "text"
        const val KEY_COLOR = "color"
        const val KEY_SIZE = "text_size"
        const val KEY_BOLD = "bold"
        const val KEY_ALIGNMENT = "alignment"

        fun from(values: Map<String, String>): StaticTextLayerConfig {
            val text = values[KEY_TEXT] ?: "Teks"
            val color = runCatching { Color.parseColor(values[KEY_COLOR] ?: "#FFFFFFFF") }
                .getOrDefault(Color.WHITE)
            val size = values[KEY_SIZE]?.toFloatOrNull() ?: 64f
            val bold = values[KEY_BOLD]?.toBooleanStrictOrNull() ?: false
            val alignment = runCatching {
                Alignment.valueOf(values[KEY_ALIGNMENT] ?: "LEFT")
            }.getOrDefault(Alignment.LEFT)
            return StaticTextLayerConfig(text, color, size, bold, alignment)
        }
    }
}

/** Shared bounded bitmap generation for the editor and RootEncoder GL image filter. */
object TextLayerBitmapFactory {
    fun create(config: StaticTextLayerConfig): Bitmap {
        val wrapWidth = 1200
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = config.color
            textSize = config.textSize
            typeface = if (config.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val lines = mutableListOf<String>()
        config.text.split('\\n').forEach { paragraph ->
            var line = ""
            paragraph.split(Regex("\\\\s+")).forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > wrapWidth - 48f) {
                    lines += line
                    line = word
                } else line = candidate
            }
            lines += line
        }
        val lineHeight = (config.textSize * 1.28f).toInt().coerceAtLeast(24)
        val contentWidth = lines.maxOfOrNull { paint.measureText(it) } ?: config.textSize
        val width = (contentWidth + 32f).toInt().coerceIn(64, wrapWidth)
        val height = (lines.size * lineHeight + 32).coerceIn(64, 4096)
        val alignedPaint = Paint(paint).apply {
            textAlign = when (config.alignment) {
                StaticTextLayerConfig.Alignment.LEFT -> Paint.Align.LEFT
                StaticTextLayerConfig.Alignment.CENTER -> Paint.Align.CENTER
                StaticTextLayerConfig.Alignment.RIGHT -> Paint.Align.RIGHT
            }
        }
        val x = when (config.alignment) {
            StaticTextLayerConfig.Alignment.LEFT -> 16f
            StaticTextLayerConfig.Alignment.CENTER -> width / 2f
            StaticTextLayerConfig.Alignment.RIGHT -> width - 16f
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            lines.take(120).forEachIndexed { index, line ->
                canvas.drawText(line, x, 16f + config.textSize + index * lineHeight, alignedPaint)
            }
        }
    }
}
