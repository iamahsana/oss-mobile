package com.oss.mobile

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SceneVideoLayerConfig(val assetId: String) {
    init { require(assetId.matches(Regex("[a-f0-9]{32}"))) { "Invalid video asset ID" } }
    fun toMap(): Map<String, String> = mapOf(KEY_ASSET_ID to assetId)
    companion object {
        const val MODULE_ID = "oss.source.video.file"
        const val KEY_ASSET_ID = "asset_id"
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_DURATION_MS = 120_000L
        const val MAX_DIMENSION = 1920
        fun from(configuration: Map<String, String>) = SceneVideoLayerConfig(
            configuration[KEY_ASSET_ID] ?: throw IllegalArgumentException("Video asset is missing")
        )
    }
}

/**
 * MediaPlayer decodes the selected clip into RootEncoder's SurfaceFilterRender texture.
 * Playback setup is main-thread-bound; texture sampling and transform changes remain GL-thread-bound.
 */
class SceneVideoFilterRender private constructor(
    private val appContext: Context,
    private val videoFile: File,
    private val surfaceReady: AtomicReference<((SurfaceTexture) -> Unit)?>
) : SurfaceFilterRender({ texture -> surfaceReady.get()?.invoke(texture) }), SceneTransformTarget {

    constructor(context: Context, file: File) : this(
        context.applicationContext, file, AtomicReference(null)
    )
    private val pendingTransform = AtomicReference<RootEncoderSpriteTransform?>(null)
    private var appliedTransform: RootEncoderSpriteTransform? = null
    private val released = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null

    override fun updateSceneTransform(transform: SceneTransform) {
        pendingTransform.set(RootEncoderSpriteTransform.from(transform))
    }

    override fun drawFilter() {
        val next = pendingTransform.get()
        if (next != null && next != appliedTransform) {
            next.applyTo(this)
            appliedTransform = next
        }
        super.drawFilter()
    }

    init {
        surfaceReady.set(::initVideoPlayback)
    }

    private fun initVideoPlayback(texture: SurfaceTexture) {
        if (released.get() || player != null) return
        val surface = Surface(texture)
        playerSurface = surface
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(videoFile.absolutePath)
            mediaPlayer.setSurface(surface)
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.isLooping = true
            mediaPlayer.setOnPreparedListener { prepared ->
                if (released.get()) {
                    prepared.release()
                } else {
                    runCatching {
                        prepared.trackInfo.forEachIndexed { index, info ->
                            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                                runCatching { prepared.deselectTrack(index) }
                            }
                        }
                        prepared.start()
                    }
                }
            }
            mediaPlayer.setOnErrorListener { _, _, _ -> true }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            runCatching { mediaPlayer.release() }
            player = null
            surface.release()
            playerSurface = null
        }
    }

    override fun release() {
        released.set(true)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { playerSurface?.release() }
        playerSurface = null
        super.release()
    }
}

class SceneVideoOverlayModule : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = SceneVideoLayerConfig.MODULE_ID,
        displayName = "Video Clip",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL, OverlayModuleCapability.READ_SELECTED_MEDIA)
    )

    override fun createRenderer(context: Context, configuration: Map<String, String>): SceneVideoFilterRender {
        val config = SceneVideoLayerConfig.from(configuration)
        val file = SceneMediaAssetStorage.file(context, config.assetId)
            ?: throw IllegalArgumentException("Video asset not found")
        require(file.length() <= SceneVideoLayerConfig.MAX_FILE_BYTES) { "Video file exceeds 50 MB" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(width in 1..SceneVideoLayerConfig.MAX_DIMENSION && height in 1..SceneVideoLayerConfig.MAX_DIMENSION) {
                "Video resolution exceeds 1920 × 1920 or is invalid"
            }
            require(duration in 1..SceneVideoLayerConfig.MAX_DURATION_MS) {
                "Video duration must be 1–120 seconds"
            }
        } finally {
            retriever.release()
        }
        return SceneVideoFilterRender(context.applicationContext, file)
    }
}
