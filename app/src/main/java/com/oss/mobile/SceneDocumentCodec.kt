package com.oss.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Small versioned, dependency-free scene document codec.
 * Properties provides escaping for names/config values; indexed entries preserve layer order.
 */
object SceneDocumentCodec {
    private const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    private const val MAX_LAYERS = 256
    private const val MAX_CONFIG_ENTRIES_PER_LAYER = 128

    fun encode(document: SceneDocument): ByteArray {
        require(document.layers.size <= MAX_LAYERS) { "Scene has too many layers" }
        val properties = Properties().apply {
            setProperty("schema_version", document.schemaVersion.toString())
            setProperty("scene.id", document.id)
            setProperty("scene.name", document.name)
            setProperty("canvas.width", document.canvas.width.toString())
            setProperty("canvas.height", document.canvas.height.toString())
            setProperty("layers.count", document.layers.size.toString())
        }

        document.layers.forEachIndexed { index, layer ->
            require(layer.configuration.size <= MAX_CONFIG_ENTRIES_PER_LAYER) {
                "Layer ${layer.id} has too many configuration entries"
            }
            val prefix = "layer.$index."
            properties.setProperty(prefix + "id", layer.id)
            properties.setProperty(prefix + "module_id", layer.moduleId)
            properties.setProperty(prefix + "display_name", layer.displayName)
            properties.setProperty(prefix + "z_index", layer.zIndex.toString())
            properties.setProperty(prefix + "visible", layer.visible.toString())
            properties.setProperty(prefix + "locked", layer.locked.toString())
            layer.groupId?.let { properties.setProperty(prefix + "group_id", it) }
            properties.setProperty(prefix + "transform.x", layer.transform.x.toString())
            properties.setProperty(prefix + "transform.y", layer.transform.y.toString())
            properties.setProperty(prefix + "transform.width", layer.transform.width.toString())
            properties.setProperty(prefix + "transform.height", layer.transform.height.toString())
            properties.setProperty(prefix + "transform.rotation", layer.transform.rotationDegrees.toString())
            properties.setProperty(prefix + "transform.opacity", layer.transform.opacity.toString())
            properties.setProperty(prefix + "config.count", layer.configuration.size.toString())
            layer.configuration.entries.forEachIndexed { configIndex, entry ->
                val configPrefix = prefix + "config.$configIndex."
                properties.setProperty(configPrefix + "key", entry.key)
                properties.setProperty(configPrefix + "value", entry.value)
            }
        }

        val output = ByteArrayOutputStream()
        properties.store(output, "OSS Scene Document")
        return output.toByteArray().also {
            require(it.size <= MAX_DOCUMENT_BYTES) { "Scene document exceeds size limit" }
        }
    }

    fun decode(bytes: ByteArray): SceneDocument {
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "Scene document exceeds size limit" }
        val properties = Properties().apply {
            ByteArrayInputStream(bytes).use { load(it) }
        }

        fun required(key: String): String =
            properties.getProperty(key) ?: throw IllegalArgumentException("Missing scene field: $key")

        fun int(key: String, min: Int, max: Int): Int {
            val value = required(key).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid integer scene field: $key")
            require(value in min..max) { "Out-of-range scene field: $key" }
            return value
        }

        fun float(key: String): Float {
            val value = required(key).toFloatOrNull()
                ?: throw IllegalArgumentException("Invalid float scene field: $key")
            require(value.isFinite()) { "Non-finite scene field: $key" }
            return value
        }

        fun boolean(key: String): Boolean = when (required(key)) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid boolean scene field: $key")
        }

        val schemaVersion = int("schema_version", 1, SceneDocument.CURRENT_SCHEMA_VERSION)
        require(schemaVersion == SceneDocument.CURRENT_SCHEMA_VERSION) {
            "Unsupported scene schema version: $schemaVersion"
        }
        val layerCount = int("layers.count", 0, MAX_LAYERS)
        val layers = (0 until layerCount).map { index ->
            val prefix = "layer.$index."
            val configCount = int(prefix + "config.count", 0, MAX_CONFIG_ENTRIES_PER_LAYER)
            val configuration = linkedMapOf<String, String>()
            repeat(configCount) { configIndex ->
                val configPrefix = prefix + "config.$configIndex."
                val key = required(configPrefix + "key")
                require(!configuration.containsKey(key)) { "Duplicate layer configuration key: $key" }
                configuration[key] = required(configPrefix + "value")
            }

            SceneLayer(
                id = required(prefix + "id"),
                moduleId = required(prefix + "module_id"),
                displayName = required(prefix + "display_name"),
                zIndex = int(prefix + "z_index", Int.MIN_VALUE, Int.MAX_VALUE),
                transform = SceneTransform(
                    x = float(prefix + "transform.x"),
                    y = float(prefix + "transform.y"),
                    width = float(prefix + "transform.width"),
                    height = float(prefix + "transform.height"),
                    rotationDegrees = float(prefix + "transform.rotation"),
                    opacity = float(prefix + "transform.opacity")
                ),
                visible = boolean(prefix + "visible"),
                locked = boolean(prefix + "locked"),
                configuration = configuration,
                groupId = properties.getProperty(prefix + "group_id")
            )
        }

        return SceneDocument(
            id = required("scene.id"),
            name = required("scene.name"),
            canvas = SceneCanvas(
                width = int("canvas.width", 1, 8192),
                height = int("canvas.height", 1, 8192)
            ),
            layers = layers,
            schemaVersion = schemaVersion
        )
    }
}
