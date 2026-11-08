package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneGifLayerConfigTest {
    @Test fun roundTripsAssetId() {
        val id = "a".repeat(32)
        assertEquals(id, SceneGifLayerConfig.from(SceneGifLayerConfig(id).toMap()).assetId)
    }

    @Test fun rejectsInvalidAssetId() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SceneGifLayerConfig("not-a-safe-id")
        }
    }
}
