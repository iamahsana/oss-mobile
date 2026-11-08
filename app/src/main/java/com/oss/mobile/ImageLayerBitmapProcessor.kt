package com.oss.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/** Produces a bounded bitmap with the same crop and mask in editor and LIVE. */
object ImageLayerBitmapProcessor {
    fun process(source: Bitmap, cropZoom: Float, maskShape: String): Bitmap {
        val zoom = cropZoom.coerceIn(1f, 3f)
        if (zoom == 1f && maskShape == StaticImageLayerConfig.MASK_RECTANGLE) return source
        val width = source.width
        val height = source.height
        val cropWidth = (width / zoom).toInt().coerceAtLeast(1)
        val cropHeight = (height / zoom).toInt().coerceAtLeast(1)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
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
        canvas.clipPath(path)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight), bounds, paint)
        return output
    }
}
