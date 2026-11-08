package com.oss.mobile

/**
 * Persistent, renderer-independent description of one editable scene.
 *
 * Geometry is normalized to the scene canvas, not to the device screen. The editor,
 * preview, and live renderer must all consume this same document.
 */
data class SceneDocument(
    val id: String,
    val name: String,
    val canvas: SceneCanvas = SceneCanvas(),
    val layers: List<SceneLayer> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    init {
        require(id.isNotBlank()) { "Scene ID cannot be blank" }
        require(name.isNotBlank()) { "Scene name cannot be blank" }
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported scene schema version: $schemaVersion"
        }
        val duplicateIds = layers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Scene layer IDs must be unique: ${duplicateIds.joinToString()}"
        }
    }

    /** Visible layers in deterministic back-to-front order. */
    fun renderOrder(): List<SceneLayer> = layers
        .asSequence()
        .filter { it.visible }
        .sortedWith(compareBy<SceneLayer> { it.zIndex }.thenBy { it.id })
        .toList()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Output canvas dimensions in logical pixels; independent of preview view size. */
data class SceneCanvas(
    val width: Int = 720,
    val height: Int = 1280
) {
    init {
        require(width in 1..8192) { "Canvas width must be between 1 and 8192" }
        require(height in 1..8192) { "Canvas height must be between 1 and 8192" }
    }

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

/**
 * One source instance in a scene. moduleId identifies the source implementation;
 * configuration remains module-owned and renderer-independent.
 */
data class SceneLayer(
    val id: String,
    val moduleId: String,
    val displayName: String,
    val zIndex: Int,
    val transform: SceneTransform = SceneTransform(),
    val visible: Boolean = true,
    val locked: Boolean = false,
    val configuration: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Layer ID cannot be blank" }
        require(moduleId.isNotBlank()) { "Layer module ID cannot be blank" }
        require(displayName.isNotBlank()) { "Layer display name cannot be blank" }
    }
}

/**
 * Normalized top-left geometry. x/y and width/height are fractions of the scene
 * canvas, so changing preview size never changes the saved composition.
 *
 * Positions may extend slightly beyond the canvas to support intentional cropping.
 * Dimensions are bounded to prevent invalid or pathological renderer allocations.
 */
data class SceneTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0.25f,
    val height: Float = 0.25f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f
) {
    init {
        require(x.isFinite() && x in -1f..2f) { "x must be finite and between -1 and 2" }
        require(y.isFinite() && y in -1f..2f) { "y must be finite and between -1 and 2" }
        require(width.isFinite() && width in 0.001f..2f) {
            "width must be finite and between 0.001 and 2"
        }
        require(height.isFinite() && height in 0.001f..2f) {
            "height must be finite and between 0.001 and 2"
        }
        require(rotationDegrees.isFinite() && rotationDegrees in -360f..360f) {
            "rotationDegrees must be finite and between -360 and 360"
        }
        require(opacity.isFinite() && opacity in 0f..1f) {
            "opacity must be finite and between 0 and 1"
        }
    }
}
