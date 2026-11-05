package com.oss.mobile

import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.library.view.GlInterface

/**
 * Runtime representation of one visual layer supplied by a core or plugin source.
 *
 * Renderer instances are created by the source module and remain owned by that module.
 * The compositor only controls their presence and ordering in RootEncoder's GL pipeline.
 */
data class CompositorLayer(
    val id: String,
    val zIndex: Int,
    val renderer: BaseFilterRender,
    val enabled: Boolean = true
)

/**
 * Central registry for visual layers sent to the stream encoder.
 *
 * Only filters registered through this compositor are removed or reordered. This avoids
 * clearing unrelated filters that may be owned by the streaming engine or other features.
 */
class OverlayCompositor(
    private val glInterface: GlInterface
) {
    private val attachedLayers = linkedMapOf<String, BaseFilterRender>()

    @Synchronized
    fun setLayers(layers: List<CompositorLayer>) {
        val duplicateIds = layers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Compositor layer IDs must be unique: ${duplicateIds.joinToString()}"
        }

        val orderedLayers = layers
            .filter { it.enabled }
            .sortedWith(compareBy<CompositorLayer> { it.zIndex }.thenBy { it.id })

        // Remove only renderers previously registered by this compositor.
        attachedLayers.values.forEach { renderer ->
            glInterface.removeFilter(renderer)
        }
        attachedLayers.clear()

        // RootEncoder applies filters in insertion order; lower z-index is rendered first.
        orderedLayers.forEach { layer ->
            glInterface.addFilter(layer.renderer)
            attachedLayers[layer.id] = layer.renderer
        }
    }

    @Synchronized
    fun removeLayer(id: String): Boolean {
        val renderer = attachedLayers.remove(id) ?: return false
        glInterface.removeFilter(renderer)
        return true
    }

    @Synchronized
    fun clear() {
        attachedLayers.values.forEach { renderer ->
            glInterface.removeFilter(renderer)
        }
        attachedLayers.clear()
    }

    @Synchronized
    fun layerIds(): List<String> = attachedLayers.keys.toList()
}
