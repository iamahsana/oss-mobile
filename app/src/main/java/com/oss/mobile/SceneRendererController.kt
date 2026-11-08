package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/**
 * Turns one renderer-independent SceneDocument into RootEncoder compositor layers.
 *
 * Renderers are reused for geometry-only edits. Structural changes create fresh renderers
 * because RootEncoder releases a filter when it is removed from its GL chain.
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
        // Keep the MediaProjection-backed source attached for the whole stream session,
        // even while hidden. Android 14+ permits only one VirtualDisplay per projection.
        val visibleLayers = (document.renderOrder() +
            document.layers.filter {
                !it.visible && it.moduleId == SceneScreenLayerConfig.MODULE_ID
            }).distinctBy { it.id }.sortedWith(
                compareBy<SceneLayer> { if (it.moduleId == SceneScreenLayerConfig.MODULE_ID) 0 else 1 }
                    .thenBy { it.zIndex }.thenBy { it.id }
            )

        // Validate every source module before creating any renderer.
        visibleLayers.forEach { layer ->
            require(modulesById.containsKey(layer.moduleId)) {
                "No visual source module registered for: ${layer.moduleId}"
            }
        }

        val currentSignatures = activeSources.map { (id, source) ->
            SceneRendererSignature(id, source.moduleId, source.configuration)
        }
        val canReuseExistingPrefix = canReuseSceneRendererPrefix(currentSignatures, visibleLayers)

        val nextSources = linkedMapOf<String, ActiveSource>()
        val compositorLayers = visibleLayers.map { layer ->
            val index = nextSources.size
            val renderer = if (canReuseExistingPrefix && index < activeSources.size) {
                activeSources.values.elementAt(index).renderer
            } else {
                // Reuse is safe only for an unchanged ordered prefix. Every other structural
                // change gets fresh renderers because RootEncoder releases removed filters.
                modulesById.getValue(layer.moduleId).createRenderer(appContext, layer.configuration)
            }

            val transformTarget = renderer as? SceneTransformTarget
                ?: throw IllegalStateException(
                    "Renderer for layer ${layer.id} does not support scene transforms"
                )
            val renderLayer = if (!layer.visible && layer.moduleId == SceneScreenLayerConfig.MODULE_ID) {
                layer.copy(transform = layer.transform.copy(opacity = 0f))
            } else layer
            renderLayer.applyTransformTo(transformTarget)

            nextSources[layer.id] = ActiveSource(
                moduleId = layer.moduleId,
                configuration = layer.configuration.toMap(),
                renderer = renderer
            )
            CompositorLayer(
                id = layer.id,
                zIndex = if (layer.moduleId == SceneScreenLayerConfig.MODULE_ID) Int.MIN_VALUE else layer.zIndex,
                renderer = renderer,
                // Keep Share Screen attached at zero opacity while hidden so the
                // MediaProjection VirtualDisplay is not released and recreated.
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
