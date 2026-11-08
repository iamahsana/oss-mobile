package com.oss.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/** Static image source supporting packaged resources and private imported media. */
class StaticImageOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = "oss.source.image.static",
        displayName = "Static Image",
        version = "1.1.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(
            OverlayModuleCapability.RENDER_VISUAL,
            OverlayModuleCapability.READ_SELECTED_MEDIA
        )
    )

    override fun createRenderer(
        context: Context,
        configuration: Map<String, String>
    ): BaseFilterRender {
        val config = StaticImageLayerConfig.from(configuration)
        val bitmap = if (config.imageFileId != null) {
            SceneImageStorage.decode(context, config.imageFileId, 2048)
                ?: throw IllegalArgumentException("Gambar yang diimpor tidak ditemukan atau rusak")
        } else {
            val drawable = context.getDrawable(config.resourceId)
                ?: throw IllegalArgumentException("Image resource not found: ${config.resourceId}")
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
            val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
            require(intrinsicWidth.toLong() * intrinsicHeight <= 4_194_304L) {
                "Image resource exceeds the renderer pixel limit"
            }
            Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }

        val renderBitmap = if (config.flipHorizontal || config.flipVertical) {
            val matrix = Matrix().apply {
                setScale(
                    if (config.flipHorizontal) -1f else 1f,
                    if (config.flipVertical) -1f else 1f
                )
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap

        val renderer = SceneImageFilterRender()
        renderer.setImage(renderBitmap)
        val canvasAspect = 720f / 1280f
        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val heightPercent = (config.widthPercent * canvasAspect / imageAspect).coerceAtMost(100f)
        renderer.updateSceneTransform(
            SceneTransform(
                x = config.xPercent / 100f,
                y = config.yPercent / 100f,
                width = config.widthPercent / 100f,
                height = heightPercent / 100f,
                opacity = config.opacity
            )
        )
        return renderer
    }
}
