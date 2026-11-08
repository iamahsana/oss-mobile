package com.oss.mobile

/**
 * Implemented by visual renderers that can consume the geometry stored in a SceneLayer.
 * The implementation must publish changes safely; it must not mutate GL state from
 * the caller thread.
 */
interface SceneTransformTarget {
    fun updateSceneTransform(transform: SceneTransform)
}

/** Apply the authoritative persisted layer geometry to a compatible renderer. */
fun SceneLayer.applyTransformTo(target: SceneTransformTarget) {
    target.updateSceneTransform(transform)
}
