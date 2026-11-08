package com.oss.mobile

import android.graphics.Color

/** Persistable settings for an editable text source. */
data class TextLayerConfig(
    val text: String,
    val color: Int = Color.WHITE,
    val textSize: Float = 64f,
    val alignment: Alignment = Alignment.CENTER
) {
    enum class Alignment { LEFT, CENTER, RIGHT }

    init {
        require(text.isNotBlank()) { "Text cannot be blank" }
        require(text.length <= 2000) { "Text is too long" }
        require(textSize.isFinite() && textSize in 16f..180f) { "Text size must be 16–180" }
    }

    fun toMap(): Map<String, String> = mapOf(
        KEY_TEXT to text,
        KEY_COLOR to String.format("#%08X", color),
        KEY_SIZE to textSize.toString(),
        KEY_ALIGNMENT to alignment.name
    )

    companion object {
        const val MODULE_ID = "oss.source.text.basic"
        const val KEY_TEXT = "text"
        const val KEY_COLOR = "color"
        const val KEY_SIZE = "text_size"
        const val KEY_ALIGNMENT = "alignment"

        fun from(values: Map<String, String>): TextLayerConfig {
            val text = values[KEY_TEXT] ?: throw IllegalArgumentException("Text is missing")
            val color = runCatching { Color.parseColor(values[KEY_COLOR] ?: "#FFFFFFFF") }
                .getOrElse { throw IllegalArgumentException("Invalid text color") }
            val size = values[KEY_SIZE]?.toFloatOrNull() ?: 64f
            val alignment = runCatching {
                Alignment.valueOf(values[KEY_ALIGNMENT] ?: Alignment.CENTER.name)
            }.getOrElse { throw IllegalArgumentException("Invalid text alignment") }
            return TextLayerConfig(text, color, size, alignment)
        }
    }
}
