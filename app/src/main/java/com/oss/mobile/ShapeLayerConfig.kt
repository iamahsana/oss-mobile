package com.oss.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/** Validated, serializable settings for native vector-like scene shapes. */
data class ShapeLayerConfig(
    val shape: Shape = Shape.RECTANGLE,
    val fillEnabled: Boolean = true,
    val fillColor: Int = Color.rgb(65, 190, 225),
    val strokeColor: Int = Color.WHITE,
    val strokeWidth: Float = 8f,
    val cornerRadius: Float = 28f
) {
    enum class Shape { RECTANGLE, CIRCLE, LINE }

    init {
        require(strokeWidth.isFinite() && strokeWidth in 0f..48f) { "Stroke width must be 0–48" }
        require(cornerRadius.isFinite() && cornerRadius in 0f..100f) { "Corner radius must be 0–100" }
    }

    fun toMap(): Map<String, String> = mapOf(
        KEY_SHAPE to shape.name,
        KEY_FILL_ENABLED to fillEnabled.toString(),
        KEY_FILL_COLOR to String.format("#%08X", fillColor),
        KEY_STROKE_COLOR to String.format("#%08X", strokeColor),
        KEY_STROKE_WIDTH to strokeWidth.toString(),
        KEY_CORNER_RADIUS to cornerRadius.toString()
    )

    companion object {
        const val MODULE_ID = "oss.source.shape.static"
        const val KEY_SHAPE = "shape"
        const val KEY_FILL_ENABLED = "fill_enabled"
        const val KEY_FILL_COLOR = "fill_color"
        const val KEY_STROKE_COLOR = "stroke_color"
        const val KEY_STROKE_WIDTH = "stroke_width"
        const val KEY_CORNER_RADIUS = "corner_radius"

        fun from(values: Map<String, String>): ShapeLayerConfig {
            val shape = runCatching { Shape.valueOf(values[KEY_SHAPE] ?: "RECTANGLE") }
                .getOrDefault(Shape.RECTANGLE)
            val fillColor = runCatching { Color.parseColor(values[KEY_FILL_COLOR] ?: "#FF41BEE1") }
                .getOrDefault(Color.rgb(65, 190, 225))
            val strokeColor = runCatching { Color.parseColor(values[KEY_STROKE_COLOR] ?: "#FFFFFFFF") }
                .getOrDefault(Color.WHITE)
            return ShapeLayerConfig(
                shape = shape,
                fillEnabled = values[KEY_FILL_ENABLED]?.toBooleanStrictOrNull() ?: true,
                fillColor = fillColor,
                strokeColor = strokeColor,
                strokeWidth = values[KEY_STROKE_WIDTH]?.toFloatOrNull()?.coerceIn(0f, 48f) ?: 8f,
                cornerRadius = values[KEY_CORNER_RADIUS]?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 28f
            )
        }
    }
}

/** Shared shape bitmap used by the editor and LIVE renderer. */
object ShapeLayerBitmapFactory {
    fun create(config: ShapeLayerConfig): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = (config.strokeWidth / 2f + 2f).coerceAtMost(48f)
        val bounds = RectF(inset, inset, size - inset, size - inset)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.fillColor
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.strokeColor
            style = Paint.Style.STROKE
            strokeWidth = config.strokeWidth.coerceAtLeast(1f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        when (config.shape) {
            ShapeLayerConfig.Shape.RECTANGLE -> {
                val radius = (config.cornerRadius / 100f * 180f).coerceIn(0f, 180f)
                if (config.fillEnabled) canvas.drawRoundRect(bounds, radius, radius, fill)
                if (config.strokeWidth > 0f) canvas.drawRoundRect(bounds, radius, radius, stroke)
            }
            ShapeLayerConfig.Shape.CIRCLE -> {
                if (config.fillEnabled) canvas.drawOval(bounds, fill)
                if (config.strokeWidth > 0f) canvas.drawOval(bounds, stroke)
            }
            ShapeLayerConfig.Shape.LINE -> {
                canvas.drawLine(24f, size / 2f, size - 24f, size / 2f, stroke)
            }
        }
        return bitmap
    }
}

class ShapeOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = ShapeLayerConfig.MODULE_ID,
        displayName = "Shapes",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
    )

    override fun createRenderer(context: Context, configuration: Map<String, String>): BaseFilterRender {
        val bitmap = ShapeLayerBitmapFactory.create(ShapeLayerConfig.from(configuration))
        return SceneImageFilterRender().apply { setImage(bitmap) }
    }
}
