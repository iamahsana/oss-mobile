package com.oss.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

data class StickerLayerConfig(val emoji: String = "⭐") {
    init {
        require(emoji in ALLOWED_STICKERS) { "Sticker is not in the built-in pack" }
    }

    fun toMap(): Map<String, String> = mapOf(KEY_EMOJI to emoji)

    companion object {
        const val MODULE_ID = "oss.source.sticker.emoji"
        const val KEY_EMOJI = "emoji"
        val ALLOWED_STICKERS = listOf(
            "⭐", "✨", "❤️", "💜", "💙", "🔥", "👑", "🎮",
            "🎯", "🌸", "🦊", "🐱", "🐼", "🍀", "🌙", "💎"
        )

        fun from(values: Map<String, String>) =
            StickerLayerConfig(values[KEY_EMOJI]?.takeIf { it in ALLOWED_STICKERS } ?: "⭐")
    }
}

/** One shared emoji-to-bitmap path keeps editor artwork and LIVE artwork consistent. */
object StickerLayerBitmapFactory {
    fun create(config: StickerLayerConfig): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = 390f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(config.emoji, size / 2f, baseline, paint)
        return bitmap
    }
}

class EmojiStickerOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = StickerLayerConfig.MODULE_ID,
        displayName = "Emoji Stickers",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
    )

    override fun createRenderer(context: Context, configuration: Map<String, String>): BaseFilterRender {
        val bitmap = StickerLayerBitmapFactory.create(StickerLayerConfig.from(configuration))
        return SceneImageFilterRender().apply { setImage(bitmap) }
    }
}
