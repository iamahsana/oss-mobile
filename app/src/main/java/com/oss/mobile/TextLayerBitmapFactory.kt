package com.oss.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Shared bounded text rasterizer used by both the editor and LIVE renderer. */
object TextLayerBitmapFactory {
    private const val MAX_WIDTH = 2048
    private const val MAX_HEIGHT = 2048
    private const val PADDING = 32

    fun create(config: TextLayerConfig): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = config.color
            textSize = config.textSize
        }
        val contentWidth = MAX_WIDTH - PADDING * 2
        val alignment = when (config.alignment) {
            TextLayerConfig.Alignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextLayerConfig.Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextLayerConfig.Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(
            config.text, 0, config.text.length, paint, contentWidth
        ).setAlignment(alignment)
            .setIncludePad(true)
            .setLineSpacing(0f, 1.08f)
            .build()
        val width = MAX_WIDTH
        val height = (layout.height + PADDING * 2).coerceIn(1, MAX_HEIGHT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            save()
            translate(PADDING.toFloat(), PADDING.toFloat())
            layout.draw(this)
            restore()
        }
        return bitmap
    }
}
