package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneMediaAssetTest {
    @Test fun classifiesGifVideoAndStaticAssets() {
        assertEquals(SceneMediaAsset.Kind.ANIMATED_IMAGE,
            SceneMediaAsset("a".repeat(32), "image/gif", 100, "overlay.gif").kind)
        assertEquals(SceneMediaAsset.Kind.VIDEO,
            SceneMediaAsset("b".repeat(32), "video/mp4", 100, "clip.mp4").kind)
        assertEquals(SceneMediaAsset.Kind.STATIC_IMAGE,
            SceneMediaAsset("c".repeat(32), "image/png", 100, "logo.png").kind)
    }

    @Test fun onlyAllowsExplicitlySupportedMimeTypes() {
        assertTrue(SceneMediaAsset.supports("image/gif"))
        assertTrue(SceneMediaAsset.supports("video/mp4"))
        assertFalse(SceneMediaAsset.supports("application/zip"))
        assertFalse(SceneMediaAsset.supports(null))
    }
}
