package com.oss.mobile

import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import kotlin.math.roundToInt

/**
 * RootEncoder 2.8.1 uses percentages for Sprite geometry, with a top-left origin.
 * This adapter is the single conversion point from OSS normalized scene geometry.
 */
data class RootEncoderSpriteTransform(
    val xPercent: Float,
    val yPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float,
    val rotationDegrees: Int,
    val alpha: Float
) {
    init {
        require(xPercent.isFinite() && yPercent.isFinite())
        require(widthPercent.isFinite() && widthPercent > 0f)
        require(heightPercent.isFinite() && heightPercent > 0f)
        require(rotationDegrees in -360..360)
        require(alpha.isFinite() && alpha in 0f..1f)
    }

    /**
     * Apply before attaching the renderer to OverlayCompositor. RootEncoder's Sprite
     * geometry is consumed on its GL thread; do not call this on an attached renderer.
     */
    fun applyTo(renderer: BaseObjectFilterRender) {
        // Sprite.scale() adjusts the current translation to preserve its anchor.
        // Set scale first, then restore the desired top-left position.
        renderer.setScale(widthPercent, heightPercent)
        renderer.setPosition(xPercent, yPercent)
        renderer.rotation = rotationDegrees
        renderer.alpha = alpha
    }

    companion object {
        fun from(transform: SceneTransform): RootEncoderSpriteTransform =
            RootEncoderSpriteTransform(
                xPercent = transform.x * 100f,
                yPercent = transform.y * 100f,
                widthPercent = transform.width * 100f,
                heightPercent = transform.height * 100f,
                rotationDegrees = transform.rotationDegrees.roundToInt(),
                alpha = transform.opacity
            )
    }
}

/** Convert one persisted layer transform into RootEncoder's object-filter geometry. */
fun SceneLayer.rootEncoderTransform(): RootEncoderSpriteTransform =
    RootEncoderSpriteTransform.from(transform)
