package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTemplateCatalogTest {
    @Test
    fun blankPortraitTemplateHasNoSourceLayers() {
        val entry = SceneTemplateCatalog.entries.first { it.id == "blank_portrait" }
        val scene = SceneTemplateCatalog.create(entry, "template_test")

        assertEquals(SceneCanvas(720, 1280), scene.canvas)
        assertTrue(scene.layers.isEmpty())
        assertEquals("template_test", scene.id)
    }

    @Test
    fun gameplayTemplatesAddShareScreenAsBackgroundLayer() {
        SceneTemplateCatalog.entries
            .filter { it.includeShareScreen }
            .forEach { entry ->
                val scene = SceneTemplateCatalog.create(entry, "template_test")
                val screen = scene.layers.single { it.moduleId == SceneScreenLayerConfig.MODULE_ID }

                assertEquals(0f, screen.transform.x)
                assertEquals(0f, screen.transform.y)
                assertEquals(1f, screen.transform.width)
                assertEquals(1f, screen.transform.height)
                assertEquals(0, scene.renderOrder().indexOf(screen))
            }
    }

    @Test
    fun titleTemplateKeepsTextAsIndependentEditableLayer() {
        val entry = SceneTemplateCatalog.entries.first { it.includeTitle }
        val scene = SceneTemplateCatalog.create(entry, "template_test")
        val title = scene.layers.single { it.moduleId == StaticTextLayerConfig.MODULE_ID }

        assertTrue(title.configuration[StaticTextLayerConfig.KEY_TEXT].orEmpty().isNotBlank())
        assertTrue(title.transform.y < 0.15f)
        assertFalse(title.locked)
    }

    @Test
    fun everyTemplateHasUniqueLayerIds() {
        SceneTemplateCatalog.entries.forEach { entry ->
            val scene = SceneTemplateCatalog.create(entry, "template_test")
            assertEquals(scene.layers.size, scene.layers.map { it.id }.toSet().size)
        }
    }
}
