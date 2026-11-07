package com.oss.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/**
 * First concrete visual source module. It deliberately supports app resources only:
 * user-selected media permissions and URI lifecycle will be added in a later source module.
 */
class StaticImageOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = "oss.source.image.static",
        displayName = "Static Image",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
    )

    override fun createRenderer(
        context: Context,
        configuration: Map<String, String>
    ): BaseFilterRender {
        val config = StaticImageLayerConfig.from(configuration)
        val drawable = context.getDrawable(config.resourceId)
            ?: throw IllegalArgumentException("Image resource not found: ${config.resourceId}")
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
        val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }

        val renderer = ImageFilterRender()
        renderer.setImage(bitmap)
        val canvasAspect = 720f / 1280f
        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val heightPercent = config.widthPercent * canvasAspect / imageAspect
        renderer.setScale(config.widthPercent, heightPercent.coerceAtMost(100f))
        renderer.setPosition(config.xPercent, config.yPercent)
        renderer.alpha = config.opacity
        return renderer
    }
}
