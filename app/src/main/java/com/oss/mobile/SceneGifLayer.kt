package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.`object`.GifFilterRender
import com.pedro.encoder.utils.gl.gif.GifHeaderParser
import java.io.ByteArrayInputStream
import java.io.File

data class SceneGifLayerConfig(val assetId: String) {
    init { require(assetId.matches(Regex("[a-f0-9]{32}"))) { "Invalid GIF asset ID" } }

    fun toMap(): Map<String, String> = mapOf(KEY_ASSET_ID to assetId)

    companion object {
        const val MODULE_ID = "oss.source.image.gif"
        const val KEY_ASSET_ID = "asset_id"
        const val MAX_GIF_BYTES = 10 * 1024 * 1024
        const val MAX_FRAMES = 60
        const val MAX_DIMENSION = 512
        const val MAX_DECODED_PIXELS = 16_777_216L

        fun from(configuration: Map<String, String>) =
            SceneGifLayerConfig(configuration[KEY_ASSET_ID]
                ?: throw IllegalArgumentException("GIF asset is missing"))
    }
}

/** GL-thread transform adapter around RootEncoder's native GIF filter. */
class SceneGifFilterRender : GifFilterRender(), SceneTransformTarget {
    @Volatile private var pendingTransform: RootEncoderSpriteTransform? = null
    private var appliedTransform: RootEncoderSpriteTransform? = null

    override fun updateSceneTransform(transform: SceneTransform) {
        pendingTransform = RootEncoderSpriteTransform.from(transform)
    }

    override fun drawFilter() {
        val next = pendingTransform
        if (next != null && next != appliedTransform) {
            next.applyTo(this)
            appliedTransform = next
        }
        super.drawFilter()
    }

    fun loadGif(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= SceneGifLayerConfig.MAX_GIF_BYTES)
        val parser = GifHeaderParser().setData(bytes)
        val header = parser.parseHeader()
        parser.clear()
        require(header.status == 0) { "GIF file is invalid" }
        require(header.numFrames in 2..SceneGifLayerConfig.MAX_FRAMES) {
            "GIF must contain 2–60 frames"
        }
        require(header.width in 1..SceneGifLayerConfig.MAX_DIMENSION &&
            header.height in 1..SceneGifLayerConfig.MAX_DIMENSION) {
            "GIF canvas is too large (maximum 512 × 512)"
        }
        require(header.width.toLong() * header.height * header.numFrames <=
            SceneGifLayerConfig.MAX_DECODED_PIXELS) {
            "GIF frame memory budget exceeded"
        }
        setGif(ByteArrayInputStream(bytes))
    }
}

class SceneGifOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = SceneGifLayerConfig.MODULE_ID,
        displayName = "Animated GIF",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL, OverlayModuleCapability.READ_SELECTED_MEDIA)
    )

    override fun createRenderer(context: Context, configuration: Map<String, String>): SceneGifFilterRender {
        val config = SceneGifLayerConfig.from(configuration)
        val file = SceneMediaAssetStorage.file(context, config.assetId)
            ?: throw IllegalArgumentException("GIF asset not found")
        require(file.length() <= SceneGifLayerConfig.MAX_GIF_BYTES) { "GIF file exceeds 10 MB" }
        val bytes = file.readBytes()
        return SceneGifFilterRender().apply { loadGif(bytes) }
    }
}
