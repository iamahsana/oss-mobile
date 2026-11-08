package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OverlayModuleRegistryTest {
    private fun module(id: String, minimumApi: Int = 1) = object : OverlaySourceModule {
        override val descriptor = OverlayModuleDescriptor(
            id = id,
            displayName = id,
            version = "1.0.0",
            minimumCoreApi = minimumApi,
            capabilities = setOf(OverlayModuleCapability.RENDER_VISUAL)
        )

        override fun createRenderer(context: Context, configuration: Map<String, String>): BaseFilterRender =
            error("Renderer creation is not part of registry tests")
    }

    @Test
    fun registersAndResolvesCompatibleModules() {
        val registry = OverlayModuleRegistry(coreApiVersion = 2)
        val source = module("test.source.image", minimumApi = 2)
        registry.register(source)
        assertEquals(source, registry.find("test.source.image"))
        assertEquals(listOf(source.descriptor), registry.descriptors())
    }

    @Test
    fun rejectsDuplicateIdsAndFutureCoreRequirements() {
        val registry = OverlayModuleRegistry(coreApiVersion = 1)
        registry.register(module("test.source.one"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(module("test.source.one"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(module("test.source.future", minimumApi = 2))
        }
    }

    @Test
    fun unregistersOnlyTheRequestedModule() {
        val registry = OverlayModuleRegistry()
        registry.register(module("test.source.one"))
        registry.register(module("test.source.two"))
        assertNotNull(registry.unregister("test.source.one"))
        assertNull(registry.find("test.source.one"))
        assertEquals(listOf("test.source.two"), registry.descriptors().map { it.id })
    }
}
