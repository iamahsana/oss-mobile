package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/** Built-in text source; uses the same image filter path as static images. */
class TextOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = TextLayerConfig.MODULE_ID,
        displayName = "Text",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
    )

    override fun createRenderer(
        context: Context,
        configuration: Map<String, String>
    ): BaseFilterRender {
        val config = TextLayerConfig.from(configuration)
        val bitmap = TextLayerBitmapFactory.create(config)
        return SceneImageFilterRender().apply {
            setImage(bitmap)
        }
    }
}
