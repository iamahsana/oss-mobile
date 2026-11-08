package com.oss.mobile

/** Stable renderer identity inputs; transform and z-index are deliberately excluded. */
internal data class SceneRendererSignature(
    val layerId: String,
    val moduleId: String,
    val configuration: Map<String, String>
)

/**
 * Existing renderers may be retained only when they form an unchanged prefix of the
 * desired ordered scene. This permits append-only additions without releasing survivors.
 */
internal fun canReuseSceneRendererPrefix(
    current: List<SceneRendererSignature>,
    desired: List<SceneLayer>
): Boolean {
    if (current.size > desired.size) return false
    return current.indices.all { index ->
        val old = current[index]
        val next = desired[index]
        old.layerId == next.id &&
            old.moduleId == next.moduleId &&
            old.configuration == next.configuration
    }
}
