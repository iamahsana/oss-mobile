package com.oss.mobile

/**
 * Built-in starter scenes for the beta. Templates are ordinary SceneDocuments:
 * after insertion they can be edited, saved, and rendered through the same LIVE path.
 *
 * Keep this catalog deliberately small; community templates belong in the future
 * extension ecosystem and must not expand the frozen beta element set.
 */
object SceneTemplateCatalog {
    data class Entry(
        val id: String,
        val title: String,
        val canvas: SceneCanvas,
        val includeShareScreen: Boolean = true,
        val includeTitle: Boolean = false
    )

    val entries = listOf(
        Entry("blank_portrait", "Kosong • 9:16", SceneCanvas(720, 1280), false),
        Entry("gameplay_portrait", "Gameplay • 9:16", SceneCanvas(720, 1280)),
        Entry("gameplay_landscape", "Gameplay • 16:9", SceneCanvas(1280, 720)),
        Entry("gameplay_title", "Gameplay + teks • 9:16", SceneCanvas(720, 1280), true, true)
    )

    fun create(entry: Entry, sceneId: String): SceneDocument {
        val layers = mutableListOf<SceneLayer>()
        if (entry.includeShareScreen) {
            layers += SceneLayer(
                id = "screen_template",
                moduleId = SceneScreenLayerConfig.MODULE_ID,
                displayName = "Share Screen",
                zIndex = 0,
                transform = SceneTransform(0f, 0f, 1f, 1f),
                configuration = SceneScreenLayerConfig().toMap()
            )
        }
        if (entry.includeTitle) {
            val y = if (entry.canvas.height > entry.canvas.width) 0.035f else 0.06f
            layers += SceneLayer(
                id = "template_title",
                moduleId = StaticTextLayerConfig.MODULE_ID,
                displayName = "Judul LIVE",
                zIndex = 1,
                transform = SceneTransform(
                    x = 0.08f,
                    y = y,
                    width = 0.84f,
                    height = 0.09f
                ),
                configuration = StaticTextLayerConfig(
                    text = "LIVE STREAM",
                    textSize = 64f,
                    bold = true,
                    alignment = StaticTextLayerConfig.Alignment.CENTER,
                    outlineWidth = 3f,
                    shadow = true
                ).toMap()
            )
        }
        return SceneDocument(
            id = sceneId,
            name = entry.title,
            canvas = entry.canvas,
            layers = layers
        )
    }
}
