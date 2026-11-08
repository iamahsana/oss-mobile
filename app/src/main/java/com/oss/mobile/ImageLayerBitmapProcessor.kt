package com.oss.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/** Applies crop, mask, and border identically in editor and LIVE. */
object ImageLayerBitmapProcessor {
    fun process(
        source: Bitmap,
        cropZoom: Float,
        maskShape: String,
        cropOffsetX: Float = 0f,
        cropOffsetY: Float = 0f,
        borderWidth: Float = 0f,
        borderColor: String = "#FFFFFF"
    ): Bitmap {
        val zoom = cropZoom.coerceIn(1f, 3f)
        val offsetX = cropOffsetX.coerceIn(-1f, 1f)
        val offsetY = cropOffsetY.coerceIn(-1f, 1f)
        val strokeFraction = borderWidth.coerceIn(0f, 0.08f)
        if (zoom == 1f && offsetX == 0f && offsetY == 0f &&
            maskShape == StaticImageLayerConfig.MASK_RECTANGLE && strokeFraction == 0f) return source
        val width = source.width
        val height = source.height
        val cropWidth = (width / zoom).toInt().coerceAtLeast(1)
        val cropHeight = (height / zoom).toInt().coerceAtLeast(1)
        val travelX = (width - cropWidth) / 2f
        val travelY = (height - cropHeight) / 2f
        val left = (travelX + travelX * offsetX).toInt().coerceIn(0, width - cropWidth)
        val top = (travelY + travelY * offsetY).toInt().coerceIn(0, height - cropHeight)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val path = Path()
        when (maskShape) {
            StaticImageLayerConfig.MASK_CIRCLE -> path.addOval(bounds, Path.Direction.CW)
            StaticImageLayerConfig.MASK_ROUNDED ->
                path.addRoundRect(bounds, min(width, height) * 0.12f, min(width, height) * 0.12f, Path.Direction.CW)
            else -> path.addRect(bounds, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight), bounds, paint)
        canvas.restore()
        if (strokeFraction > 0f) {
            val stroke = min(width, height) * strokeFraction
            val borderBounds = RectF(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f)
            val borderPath = Path()
            when (maskShape) {
                StaticImageLayerConfig.MASK_CIRCLE -> borderPath.addOval(borderBounds, Path.Direction.CW)
                StaticImageLayerConfig.MASK_ROUNDED ->
                    borderPath.addRoundRect(borderBounds, min(width, height) * 0.12f, min(width, height) * 0.12f, Path.Direction.CW)
                else -> borderPath.addRect(borderBounds, Path.Direction.CW)
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = runCatching { android.graphics.Color.parseColor(borderColor) }.getOrDefault(android.graphics.Color.WHITE)
            }
            canvas.drawPath(borderPath, borderPaint)
        }
        return output
    }
}
