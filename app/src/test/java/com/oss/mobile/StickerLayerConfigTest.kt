package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerLayerConfigTest {
    @Test
    fun preservesAllowedSticker() {
        val config = StickerLayerConfig("🦊")
        assertEquals("🦊", StickerLayerConfig.from(config.toMap()).emoji)
    }

    @Test
    fun rejectsUnknownStickerAndUsesSafeFallbackWhenParsing() {
        val parsed = StickerLayerConfig.from(mapOf(StickerLayerConfig.KEY_EMOJI to "not-an-emoji"))
        assertEquals("⭐", parsed.emoji)
    }
}
