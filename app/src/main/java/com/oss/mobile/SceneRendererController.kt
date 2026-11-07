package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/**
 * Turns one renderer-independent SceneDocument into RootEncoder compositor layers.
 *
 * A renderer is reused while its layer ID, source module, and module configuration remain
 * unchanged. Geometry is published separately through SceneTransformTarget, so dragging an
 * object does not recreate its bitmap/renderer or rebuild the GL filter chain.
 */
class SceneRendererController(
    context: Context,
    modules: Collection<OverlaySourceModule>,
    private val compositor: OverlayCompositor
) {
    private data class ActiveSource(
        val moduleId: String,
        val configuration: Map<String, String>,
        val renderer: BaseFilterRender
    )

    private val appContext = context.applicationContext
    private val modulesById: Map<String, OverlaySourceModule>
    private var activeSources = linkedMapOf<String, ActiveSource>()

    init {
        val duplicateIds = modules.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Duplicate visual source module IDs: ${duplicateIds.joinToString()}"
        }
        modulesById = modules.associateBy { it.descriptor.id }
    }

    /**
     * Applies the document as one logical scene update. Hidden layers are not attached.
     * A missing module or unsupported transform target fails before compositor mutation.
     */
    @Synchronized
    fun apply(document: SceneDocument) {
        val visibleLayers = document.renderOrder()

        // Validate every source module before creating any renderer.
        visibleLayers.forEach { layer ->
            require(modulesById.containsKey(layer.moduleId)) {
                "No visual source module registered for: ${layer.moduleId}"
            }
        }

        val nextSources = linkedMapOf<String, ActiveSource>()
        val compositorLayers = visibleLayers.map { layer ->
            val previous = activeSources[layer.id]
            val canReuse = previous != null &&
                previous.moduleId == layer.moduleId &&
                previous.configuration == layer.configuration

            val renderer = if (canReuse) {
                previous!!.renderer
            } else {
                modulesById.getValue(layer.moduleId).createRenderer(appContext, layer.configuration)
            }

            val transformTarget = renderer as? SceneTransformTarget
                ?: throw IllegalStateException(
                    "Renderer for layer ${layer.id} does not support scene transforms"
                )
            layer.applyTransformTo(transformTarget)

            nextSources[layer.id] = ActiveSource(
                moduleId = layer.moduleId,
                configuration = layer.configuration.toMap(),
                renderer = renderer
            )
            CompositorLayer(
                id = layer.id,
                zIndex = layer.zIndex,
                renderer = renderer,
                enabled = true
            )
        }

        compositor.setLayers(compositorLayers)
        activeSources = nextSources
    }

    @Synchronized
    fun clear() {
        compositor.setLayers(emptyList())
        activeSources.clear()
    }

    @Synchronized
    fun activeLayerIds(): List<String> = activeSources.keys.toList()
}
