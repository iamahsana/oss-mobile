package com.oss.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface

/** Validated, serializable configuration for an editable text source. */
data class StaticTextLayerConfig(
    val text: String,
    val color: Int = Color.WHITE,
    val textSize: Float = 64f,
    val bold: Boolean = false,
    val alignment: Alignment = Alignment.LEFT,
    val outlineWidth: Float = 0f,
    val shadow: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val backgroundColor: Int = Color.argb(210, 15, 25, 35),
    val backgroundGradient: Boolean = false,
    val backgroundGradientEndColor: Int = Color.rgb(40, 170, 210),
    val backgroundRadius: Float = 18f,
    val italic: Boolean = false,
    val fontFamily: FontFamily = FontFamily.SANS,
    val backgroundOpacity: Int = 86
) {
    enum class Alignment { LEFT, CENTER, RIGHT }
    enum class FontFamily { SANS, SERIF, MONOSPACE }

    init {
        require(text.isNotBlank() && text.length <= 500) { "Text must contain 1–500 characters" }
        require(textSize.isFinite() && textSize in 16f..256f) { "Text size must be 16–256" }
        require(outlineWidth.isFinite() && outlineWidth in 0f..12f) { "Outline width must be 0–12" }
        require(backgroundRadius.isFinite() && backgroundRadius in 0f..48f) { "Background radius must be 0–48" }
        require(backgroundOpacity in 0..100) { "Background opacity must be 0–100" }
    }

    fun toMap(): Map<String, String> = mapOf(
        KEY_TEXT to text,
        KEY_COLOR to String.format("#%08X", color),
        KEY_SIZE to textSize.toString(),
        KEY_BOLD to bold.toString(),
        KEY_ALIGNMENT to alignment.name,
        KEY_OUTLINE_WIDTH to outlineWidth.toString(),
        KEY_SHADOW to shadow.toString(),
        KEY_BACKGROUND_ENABLED to backgroundEnabled.toString(),
        KEY_BACKGROUND_COLOR to String.format("#%08X", backgroundColor),
        KEY_BACKGROUND_GRADIENT to backgroundGradient.toString(),
        KEY_BACKGROUND_GRADIENT_END to String.format("#%08X", backgroundGradientEndColor),
        KEY_BACKGROUND_RADIUS to backgroundRadius.toString(),
        KEY_ITALIC to italic.toString(),
        KEY_FONT_FAMILY to fontFamily.name,
        KEY_BACKGROUND_OPACITY to backgroundOpacity.toString()
    )

    companion object {
        const val MODULE_ID = "oss.source.text.static"
        const val KEY_TEXT = "text"
        const val KEY_COLOR = "color"
        const val KEY_SIZE = "text_size"
        const val KEY_BOLD = "bold"
        const val KEY_ALIGNMENT = "alignment"
        const val KEY_OUTLINE_WIDTH = "outline_width"
        const val KEY_SHADOW = "shadow"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_BACKGROUND_GRADIENT = "background_gradient"
        const val KEY_BACKGROUND_GRADIENT_END = "background_gradient_end"
        const val KEY_BACKGROUND_RADIUS = "background_radius"
        const val KEY_ITALIC = "italic"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"

        fun from(values: Map<String, String>): StaticTextLayerConfig {
            val text = values[KEY_TEXT] ?: "Teks"
            val color = runCatching { Color.parseColor(values[KEY_COLOR] ?: "#FFFFFFFF") }
                .getOrDefault(Color.WHITE)
            val size = values[KEY_SIZE]?.toFloatOrNull() ?: 64f
            val bold = values[KEY_BOLD]?.toBooleanStrictOrNull() ?: false
            val alignment = runCatching {
                Alignment.valueOf(values[KEY_ALIGNMENT] ?: "LEFT")
            }.getOrDefault(Alignment.LEFT)
            val outlineWidth = values[KEY_OUTLINE_WIDTH]?.toFloatOrNull()?.coerceIn(0f, 12f) ?: 0f
            val shadow = values[KEY_SHADOW]?.toBooleanStrictOrNull() ?: false
            val backgroundEnabled = values[KEY_BACKGROUND_ENABLED]?.toBooleanStrictOrNull() ?: false
            val backgroundColor = runCatching { Color.parseColor(values[KEY_BACKGROUND_COLOR] ?: "#D20F1923") }.getOrDefault(Color.argb(210, 15, 25, 35))
            val backgroundGradient = values[KEY_BACKGROUND_GRADIENT]?.toBooleanStrictOrNull() ?: false
            val backgroundGradientEnd = runCatching { Color.parseColor(values[KEY_BACKGROUND_GRADIENT_END] ?: "#FF28AAD2") }.getOrDefault(Color.rgb(40, 170, 210))
            val backgroundRadius = values[KEY_BACKGROUND_RADIUS]?.toFloatOrNull()?.coerceIn(0f, 48f) ?: 18f
            val italic = values[KEY_ITALIC]?.toBooleanStrictOrNull() ?: false
            val fontFamily = runCatching { FontFamily.valueOf(values[KEY_FONT_FAMILY] ?: "SANS") }.getOrDefault(FontFamily.SANS)
            val backgroundOpacity = values[KEY_BACKGROUND_OPACITY]?.toIntOrNull()?.coerceIn(0, 100) ?: 86
            return StaticTextLayerConfig(text, color, size, bold, alignment, outlineWidth, shadow, backgroundEnabled, backgroundColor, backgroundGradient, backgroundGradientEnd, backgroundRadius, italic, fontFamily, backgroundOpacity)
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
            val style = (if (config.bold) Typeface.BOLD else Typeface.NORMAL) or
                (if (config.italic) Typeface.ITALIC else Typeface.NORMAL)
            val family = when (config.fontFamily) {
                StaticTextLayerConfig.FontFamily.SANS -> Typeface.SANS_SERIF
                StaticTextLayerConfig.FontFamily.SERIF -> Typeface.SERIF
                StaticTextLayerConfig.FontFamily.MONOSPACE -> Typeface.MONOSPACE
            }
            typeface = Typeface.create(family, style)
            textAlign = Paint.Align.LEFT
        }
        val lines = mutableListOf<String>()
        config.text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(Regex("\\s+")).forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > wrapWidth - 48f) {
                    lines += line
                    line = word
                } else line = candidate
            }
            lines += line
        }
        val effectPadding = (config.outlineWidth * 2f + if (config.shadow) 12f else 0f).toInt()
        val padding = 18 + effectPadding
        val lineHeight = (config.textSize * 1.28f + effectPadding).toInt().coerceAtLeast(24)
        val contentWidth = lines.maxOfOrNull { paint.measureText(it) } ?: config.textSize
        val width = (contentWidth + padding * 2f).toInt().coerceIn(64, wrapWidth)
        val height = (lines.size * lineHeight + padding * 2).coerceIn(64, 4096)
        val alignedPaint = Paint(paint).apply {
            textAlign = when (config.alignment) {
                StaticTextLayerConfig.Alignment.LEFT -> Paint.Align.LEFT
                StaticTextLayerConfig.Alignment.CENTER -> Paint.Align.CENTER
                StaticTextLayerConfig.Alignment.RIGHT -> Paint.Align.RIGHT
            }
        }
        val x = when (config.alignment) {
            StaticTextLayerConfig.Alignment.LEFT -> padding.toFloat()
            StaticTextLayerConfig.Alignment.CENTER -> width / 2f
            StaticTextLayerConfig.Alignment.RIGHT -> width - padding.toFloat()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            if (config.backgroundEnabled) {
                val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((255 * config.backgroundOpacity / 100f).toInt(), Color.red(config.backgroundColor), Color.green(config.backgroundColor), Color.blue(config.backgroundColor))
                    style = Paint.Style.FILL
                    if (config.backgroundGradient) {
                        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                            config.backgroundColor.let { Color.argb((255 * config.backgroundOpacity / 100f).toInt(), Color.red(it), Color.green(it), Color.blue(it)) }, Color.argb((255 * config.backgroundOpacity / 100f).toInt(), Color.red(config.backgroundGradientEndColor), Color.green(config.backgroundGradientEndColor), Color.blue(config.backgroundGradientEndColor)), Shader.TileMode.CLAMP)
                    }
                }
                canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, config.backgroundRadius, config.backgroundRadius, backgroundPaint)
            }
            lines.take(120).forEachIndexed { index, line ->
                val baseline = padding + config.textSize + index * lineHeight
                if (config.shadow) {
                    val shadowPaint = Paint(alignedPaint).apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                        setShadowLayer(6f, 3f, 3f, Color.BLACK)
                    }
                    canvas.drawText(line, x, baseline, shadowPaint)
                    shadowPaint.clearShadowLayer()
                }
                if (config.outlineWidth > 0f) {
                    val outlinePaint = Paint(alignedPaint).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = config.outlineWidth * 2f
                    }
                    canvas.drawText(line, x, baseline, outlinePaint)
                }
                canvas.drawText(line, x, baseline, alignedPaint)
            }
        }
    }
}
