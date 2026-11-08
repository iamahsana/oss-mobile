package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneMediaAssetReferencesTest {
    @Test fun keepsOnlyAssetsReferencedByGifOrVideoLayers() {
        val gifId = "a".repeat(32)
        val videoId = "b".repeat(32)
        val unusedId = "c".repeat(32)
        val scene = SceneDocument(
            id = "scene_media_refs",
            name = "Media references",
            layers = listOf(
                SceneLayer("gif", SceneGifLayerConfig.MODULE_ID, "GIF", 0,
                    configuration = SceneGifLayerConfig(gifId).toMap()),
                SceneLayer("video", SceneVideoLayerConfig.MODULE_ID, "Video", 1,
                    configuration = SceneVideoLayerConfig(videoId).toMap()),
                SceneLayer("image", "oss.source.image.static", "Image", 2,
                    configuration = mapOf("asset_id" to unusedId)),
                SceneLayer("bad", SceneGifLayerConfig.MODULE_ID, "Bad", 3,
                    configuration = mapOf("asset_id" to "../unsafe"))
            )
        )
        assertEquals(setOf(gifId, videoId), SceneMediaAssetReferences.collect(listOf(scene)))
    }

    @Test fun collectsReferencesAcrossMultipleScenes() {
        val firstId = "d".repeat(32)
        val secondId = "e".repeat(32)
        val scenes = listOf(
            SceneDocument("one", "One", layers = listOf(
                SceneLayer("a", SceneGifLayerConfig.MODULE_ID, "GIF", 0,
                    configuration = SceneGifLayerConfig(firstId).toMap())
            )),
            SceneDocument("two", "Two", layers = listOf(
                SceneLayer("b", SceneVideoLayerConfig.MODULE_ID, "Video", 0,
                    configuration = SceneVideoLayerConfig(secondId).toMap())
            ))
        )
        assertEquals(setOf(firstId, secondId), SceneMediaAssetReferences.collect(scenes))
    }
}
