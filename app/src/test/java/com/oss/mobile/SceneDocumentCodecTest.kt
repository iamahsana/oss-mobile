package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneDocumentCodecTest {
    @Test
    fun roundTripsCompleteSceneIncludingUnicodeAndModuleConfiguration() {
        val original = SceneDocument(
            id = "scene-main_1",
            name = "Live 🎮 / Portrait",
            canvas = SceneCanvas(1080, 1920),
            layers = listOf(
                SceneLayer(
                    id = "background",
                    moduleId = "oss.source.image.static",
                    displayName = "Latar belakang",
                    zIndex = -2,
                    transform = SceneTransform(
                        x = -0.05f,
                        y = 0.1f,
                        width = 1.1f,
                        height = 0.8f,
                        rotationDegrees = -12.5f,
                        opacity = 0.85f
                    ),
                    visible = true,
                    locked = true,
                    configuration = mapOf(
                        "resource_id" to "2131230001",
                        "custom key=1" to "value with spaces; and = signs",
                        "unicode" to "Kitsune 狐"
                    )
                ),
                SceneLayer(
                    id = "title",
                    moduleId = "oss.source.text",
                    displayName = "Judul",
                    zIndex = 4,
                    transform = SceneTransform(x = 0.2f, y = 0.7f, width = 0.6f, height = 0.1f),
                    visible = false,
                    locked = false,
                    configuration = emptyMap()
                )
            )
        )

        val decoded = SceneDocumentCodec.decode(SceneDocumentCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(listOf("background", "title"), decoded.layers.map { it.id })
    }

    @Test
    fun roundTripsAnEmptyScene() {
        val original = SceneDocument(id = "empty", name = "Empty")

        assertEquals(original, SceneDocumentCodec.decode(SceneDocumentCodec.encode(original)))
    }

    @Test
    fun rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneDocumentCodec.decode("scene.id=missing-name\n".toByteArray())
        }
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val bytes = SceneDocumentCodec.encode(SceneDocument(id = "scene", name = "Test"))
        val text = bytes.toString(Charsets.ISO_8859_1).replace(
            "schema_version=1",
            "schema_version=99"
        )

        assertThrows(IllegalArgumentException::class.java) {
            SceneDocumentCodec.decode(text.toByteArray(Charsets.ISO_8859_1))
        }
    }

    @Test
    fun rejectsDocumentsLargerThanSafetyLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneDocumentCodec.decode(ByteArray(2 * 1024 * 1024 + 1))
        }
    }
}


    @Test
    fun preservesLayerGroupIdsAndSupportsLegacyDocumentsWithoutGroups() {
        val grouped = SceneDocument(
            id = "grouped",
            name = "Grouped",
            layers = listOf(
                SceneLayer("a", "module.a", "A", 0, groupId = "brand"),
                SceneLayer("b", "module.b", "B", 1, groupId = "brand"),
                SceneLayer("c", "module.c", "C", 2)
            )
        )
        val decoded = SceneDocumentCodec.decode(SceneDocumentCodec.encode(grouped))
        assertEquals(listOf("brand", "brand", null), decoded.layers.map { it.groupId })
    }

    @Test
    fun rejectsUnsafeGroupIds() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneLayer("a", "module.a", "A", 0, groupId = "../unsafe")
        }
    }
