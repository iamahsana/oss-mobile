package com.oss.mobile

import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import java.util.concurrent.atomic.AtomicReference

/**
 * ImageFilterRender whose scene geometry is applied from drawFilter(), i.e. on
 * RootEncoder's GL render thread. UI/service threads only publish immutable snapshots.
 */
class SceneImageFilterRender : ImageFilterRender() {
    private val pendingTransform = AtomicReference<RootEncoderSpriteTransform?>(null)

    // Accessed only by RootEncoder's GL thread.
    private var appliedTransform: RootEncoderSpriteTransform? = null

    /**
     * Safe to call from the editor or service thread. The newest transform wins;
     * intermediate pointer-move updates are intentionally coalesced.
     */
    fun updateSceneTransform(transform: SceneTransform) {
        pendingTransform.set(RootEncoderSpriteTransform.from(transform))
    }

    fun updateSceneTransform(layer: SceneLayer) {
        updateSceneTransform(layer.transform)
    }

    override fun drawFilter() {
        val next = pendingTransform.get()
        if (next != null && next != appliedTransform) {
            // This runs on RootEncoder's GL thread, not the caller thread.
            next.applyTo(this)
            appliedTransform = next
        }
        super.drawFilter()
    }
}
