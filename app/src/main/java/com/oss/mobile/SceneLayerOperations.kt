package com.oss.mobile

import java.util.UUID

/**
 * Pure scene-layer operations shared by the editor UI and future layer panel.
 * Every operation returns a new immutable document and keeps z-order deterministic.
 */
object SceneLayerOperations {
    fun duplicate(document: SceneDocument, layerId: String): SceneDocument {
        val source = document.layers.firstOrNull { it.id == layerId } ?: return document
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            displayName = uniqueCopyName(document, source.displayName),
            zIndex = nextZ,
            transform = source.transform.copy(
                x = (source.transform.x + 0.025f).coerceIn(-1f, 2f),
                y = (source.transform.y + 0.025f).coerceIn(-1f, 2f)
            ),
            locked = false
        )
        return document.copy(layers = document.layers + copy)
    }

    fun moveToFront(document: SceneDocument, layerId: String): SceneDocument =
        moveToEdge(document, layerId, front = true)

    fun moveToBack(document: SceneDocument, layerId: String): SceneDocument =
        moveToEdge(document, layerId, front = false)

    fun remove(document: SceneDocument, layerId: String): SceneDocument {
        if (document.layers.none { it.id == layerId }) return document
        return document.copy(layers = document.layers.filterNot { it.id == layerId })
    }

    fun toggleVisibility(document: SceneDocument, layerId: String): SceneDocument =
        document.copy(layers = document.layers.map {
            if (it.id == layerId) it.copy(visible = !it.visible) else it
        })

    fun toggleLock(document: SceneDocument, layerId: String): SceneDocument =
        document.copy(layers = document.layers.map {
            if (it.id == layerId) it.copy(locked = !it.locked) else it
        })

    private fun moveToEdge(
        document: SceneDocument,
        layerId: String,
        front: Boolean
    ): SceneDocument {
        if (document.layers.none { it.id == layerId }) return document
        val ordered = document.layers.sortedWith(
            compareBy<SceneLayer> { it.zIndex }.thenBy { it.id }
        ).toMutableList()
        val selected = ordered.firstOrNull { it.id == layerId } ?: return document
        ordered.remove(selected)
        if (front) ordered.add(selected) else ordered.add(0, selected)
        val normalized = ordered.mapIndexed { index, layer -> layer.copy(zIndex = index) }
        val byId = normalized.associateBy { it.id }
        return document.copy(layers = document.layers.map { byId.getValue(it.id) })
    }

    private fun uniqueCopyName(document: SceneDocument, original: String): String {
        val base = "$original copy"
        val existing = document.layers.map { it.displayName }.toSet()
        if (base !in existing) return base
        var suffix = 2
        while ("$base $suffix" in existing) suffix++
        return "$base $suffix"
    }
}
