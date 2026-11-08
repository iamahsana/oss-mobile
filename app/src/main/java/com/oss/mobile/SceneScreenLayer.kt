package com.oss.mobile

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.graphics.SurfaceTexture
import android.util.DisplayMetrics
import android.view.WindowManager
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaProjection-backed scene source. Capture is rendered as a normal GL layer,
 * so it shares the same normalized transform, crop bounds and z-order as overlays.
 *
 * The MediaProjection permission is obtained once by ScreenStreamService. This
 * module owns exactly one VirtualDisplay for the lifetime of its filter renderer.
 */
data class SceneScreenLayerConfig(
    val captureDisplay: String = "default"
) {
    fun toMap(): Map<String, String> = mapOf(KEY_CAPTURE_DISPLAY to captureDisplay)

    companion object {
        const val MODULE_ID = "oss.source.screen.capture"
        const val KEY_CAPTURE_DISPLAY = "capture_display"

        fun from(configuration: Map<String, String>) =
            SceneScreenLayerConfig(configuration[KEY_CAPTURE_DISPLAY] ?: "default")
    }
}

class SceneScreenCaptureFilterRender private constructor(
    private val appContext: Context,
    private val projection: MediaProjection,
    private val surfaceReady: AtomicReference<((SurfaceTexture) -> Unit)?>
) : SurfaceFilterRender({ texture -> surfaceReady.get()?.invoke(texture) }), SceneTransformTarget {

    constructor(context: Context, projection: MediaProjection) : this(
        context.applicationContext, projection, AtomicReference(null)
    )

    private val surfaceRef = AtomicReference<Surface?>(null)
    private val virtualDisplayRef = AtomicReference<VirtualDisplay?>(null)
    private val released = AtomicBoolean(false)
    private val pendingTransform = AtomicReference<RootEncoderSpriteTransform?>(null)
    private var appliedTransform: RootEncoderSpriteTransform? = null

    init {
        surfaceReady.set(::attachCaptureSurface)
    }

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

    /**
     * Called by SceneScreenSourceModule once the renderer has been attached to GL.
     * Uses the display's real dimensions so landscape capture remains landscape.
     */
    fun attachCaptureSurface(texture: SurfaceTexture) {
        if (released.get() || virtualDisplayRef.get() != null) return
        val metrics = DisplayMetrics()
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(2)
        val height = metrics.heightPixels.coerceAtLeast(2)
        val density = metrics.densityDpi.coerceAtLeast(1)
        val surface = Surface(texture)
        surfaceRef.set(surface)
        try {
            val display = projection.createVirtualDisplay(
                "OSS Scene Share Screen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                Handler(Looper.getMainLooper())
            )
            if (released.get()) {
                display?.release()
                surface.release()
            } else if (display == null) {
                surface.release()
                surfaceRef.set(null)
                throw IllegalStateException("Android tidak dapat membuat sumber Share Screen")
            } else {
                virtualDisplayRef.set(display)
            }
        } catch (error: Exception) {
            surfaceRef.getAndSet(null)?.release()
            throw error
        }
    }

    override fun release() {
        released.set(true)
        virtualDisplayRef.getAndSet(null)?.release()
        surfaceRef.getAndSet(null)?.release()
        super.release()
    }
}

/**
 * Source module bound to the active stream's already-authorized MediaProjection.
 */
class SceneScreenSourceModule(
    private val projection: MediaProjection
) : OverlaySourceModule {
    override val descriptor = OverlayModuleDescriptor(
        id = SceneScreenLayerConfig.MODULE_ID,
        displayName = "Share Screen",
        version = "1.0.0",
        minimumCoreApi = OverlayModuleApi.CURRENT_VERSION,
        capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
    )

    override fun createRenderer(
        context: Context,
        configuration: Map<String, String>
    ): SceneScreenCaptureFilterRender {
        SceneScreenLayerConfig.from(configuration)
        return SceneScreenCaptureFilterRender(context.applicationContext, projection)
    }
}
