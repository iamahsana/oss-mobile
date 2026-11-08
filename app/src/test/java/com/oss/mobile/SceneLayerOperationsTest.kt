package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneLayerOperationsTest {
    private fun scene() = SceneDocument(
        id = "ops", name = "Operations",
        layers = listOf(
            SceneLayer("a", "m", "A", 0, SceneTransform(x=.1f,y=.1f,width=.2f,height=.2f)),
            SceneLayer("b", "m", "B", 1, SceneTransform(x=.5f,y=.5f,width=.2f,height=.2f)),
            SceneLayer("c", "m", "C", 2, SceneTransform(x=.8f,y=.8f,width=.1f,height=.1f))
        )
    )

    @Test fun groupsAndUngroupsWithoutChangingLayerGeometry() {
        val original = scene()
        val grouped = SceneLayerOperations.group(original, setOf("a","b"), "brand")
        assertEquals(listOf("brand","brand",null), grouped.layers.map { it.groupId })
        assertEquals(original.layers.map { it.transform }, grouped.layers.map { it.transform })
        assertEquals(listOf(null,null,null), SceneLayerOperations.ungroup(grouped,"brand").layers.map { it.groupId })
    }

    @Test fun movesGroupTogetherAndLeavesOtherLayersUntouched() {
        val moved = SceneLayerOperations.moveGroup(
            SceneLayerOperations.group(scene(), setOf("a","b"), "g"), "g", .1f, -.05f
        )
        assertEquals(.2f, moved.layers[0].transform.x)
        assertEquals(.45f, moved.layers[1].transform.y)
        assertEquals(.8f, moved.layers[2].transform.x)
    }

    @Test fun alignsAndDistributesSelectedLayers() {
        val aligned = SceneLayerOperations.align(scene(), setOf("a","b"), SceneLayerOperations.Edge.LEFT)
        assertEquals(.1f, aligned.layers[0].transform.x)
        assertEquals(.1f, aligned.layers[1].transform.x)
        val distributed = SceneLayerOperations.distribute(scene(), setOf("a","b","c"), SceneLayerOperations.Axis.HORIZONTAL)
        assertEquals(.1f, distributed.layers.first().transform.x)
        assertEquals(.8f, distributed.layers.last().transform.x)
    }
}
