package com.oss.mobile

import kotlin.math.max
import kotlin.math.min

/** Pure scene operations shared by the editor UI and future automation modules. */
object SceneLayerOperations {
    fun group(document: SceneDocument, layerIds: Set<String>, groupId: String): SceneDocument {
        require(groupId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid group ID" }
        require(layerIds.size >= 2) { "A group needs at least two layers" }
        require(document.layers.count { it.id in layerIds } == layerIds.size) { "Unknown layer in group" }
        return document.copy(layers = document.layers.map {
            if (it.id in layerIds) it.copy(groupId = groupId) else it
        })
    }

    fun ungroup(document: SceneDocument, groupId: String): SceneDocument =
        document.copy(layers = document.layers.map {
            if (it.groupId == groupId) it.copy(groupId = null) else it
        })

    /** Moves every member by the same normalized delta while preserving relative geometry. */
    fun moveGroup(document: SceneDocument, groupId: String, dx: Float, dy: Float): SceneDocument {
        require(dx.isFinite() && dy.isFinite())
        return document.copy(layers = document.layers.map { layer ->
            if (layer.groupId == groupId && !layer.locked) {
                layer.copy(transform = layer.transform.copy(
                    x = (layer.transform.x + dx).coerceIn(-1f, 2f),
                    y = (layer.transform.y + dy).coerceIn(-1f, 2f)
                ))
            } else layer
        })
    }

    fun align(document: SceneDocument, layerIds: Set<String>, edge: Edge): SceneDocument {
        val selected = document.layers.filter { it.id in layerIds && !it.locked }
        if (selected.size < 2) return document
        val target = when (edge) {
            Edge.LEFT -> selected.minOf { it.transform.x }
            Edge.CENTER_X -> selected.map { it.transform.x + it.transform.width / 2f }.average().toFloat()
            Edge.RIGHT -> selected.maxOf { it.transform.x + it.transform.width }
            Edge.TOP -> selected.minOf { it.transform.y }
            Edge.CENTER_Y -> selected.map { it.transform.y + it.transform.height / 2f }.average().toFloat()
            Edge.BOTTOM -> selected.maxOf { it.transform.y + it.transform.height }
        }
        return document.copy(layers = document.layers.map { layer ->
            if (layer !in selected) layer else {
                val t = layer.transform
                val next = when (edge) {
                    Edge.LEFT -> t.copy(x = target)
                    Edge.CENTER_X -> t.copy(x = target - t.width / 2f)
                    Edge.RIGHT -> t.copy(x = target - t.width)
                    Edge.TOP -> t.copy(y = target)
                    Edge.CENTER_Y -> t.copy(y = target - t.height / 2f)
                    Edge.BOTTOM -> t.copy(y = target - t.height)
                }
                layer.copy(transform = next)
            }
        })
    }

    /**
     * Resizes a whole group from its shared top-left anchor while preserving each
     * member's relative position and proportions. The bounds are captured at gesture start.
     */
    fun resizeGroup(
        document: SceneDocument,
        groupId: String,
        startBounds: SceneTransform,
        newWidth: Float,
        newHeight: Float
    ): SceneDocument {
        require(newWidth.isFinite() && newHeight.isFinite() && newWidth > 0f && newHeight > 0f)
        val scaleX = (newWidth / startBounds.width).coerceIn(0.01f, 100f)
        val scaleY = (newHeight / startBounds.height).coerceIn(0.01f, 100f)
        return document.copy(layers = document.layers.map { layer ->
            if (layer.groupId == groupId && !layer.locked) {
                val t = layer.transform
                layer.copy(transform = t.copy(
                    x = startBounds.x + (t.x - startBounds.x) * scaleX,
                    y = startBounds.y + (t.y - startBounds.y) * scaleY,
                    width = (t.width * scaleX).coerceIn(0.03f, 2f),
                    height = (t.height * scaleY).coerceIn(0.03f, 2f)
                ))
            } else layer
        })
    }

    /** Evenly spaces selected layers by their leading-edge order, retaining their sizes. */
    fun distribute(document: SceneDocument, layerIds: Set<String>, axis: Axis): SceneDocument {
        val selected = document.layers.filter { it.id in layerIds && !it.locked }
            .sortedBy { if (axis == Axis.HORIZONTAL) it.transform.x else it.transform.y }
        if (selected.size < 3) return document
        val first = selected.first().transform
        val last = selected.last().transform
        val start = if (axis == Axis.HORIZONTAL) first.x else first.y
        val end = if (axis == Axis.HORIZONTAL) {
            last.x + last.width
        } else last.y + last.height
        val totalSize = selected.sumOf {
            (if (axis == Axis.HORIZONTAL) it.transform.width else it.transform.height).toDouble()
        }.toFloat()
        val gap = (end - start - totalSize) / (selected.size - 1)
        var cursor = start
        val positions = selected.associate { layer ->
            val size = if (axis == Axis.HORIZONTAL) layer.transform.width else layer.transform.height
            val position = cursor
            cursor += size + gap
            layer.id to position
        }
        return document.copy(layers = document.layers.map { layer ->
            val position = positions[layer.id] ?: return@map layer
            layer.copy(transform = if (axis == Axis.HORIZONTAL) layer.transform.copy(x = position)
                else layer.transform.copy(y = position))
        })
    }

    enum class Edge { LEFT, CENTER_X, RIGHT, TOP, CENTER_Y, BOTTOM }
    enum class Axis { HORIZONTAL, VERTICAL }
}
