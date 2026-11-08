package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneVideoLayerConfigTest {
    @Test fun roundTripsAssetId() {
        val id = "d".repeat(32)
        assertEquals(id, SceneVideoLayerConfig.from(SceneVideoLayerConfig(id).toMap()).assetId)
    }

    @Test fun rejectsInvalidAssetId() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SceneVideoLayerConfig("../video")
        }
    }
}
