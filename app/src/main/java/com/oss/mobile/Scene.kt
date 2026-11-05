package com.oss.mobile

/**
 * A scene is a named composition shown in the studio preview.
 * Sources are intentionally metadata-only until the compositor is implemented.
 */
data class Scene(
    val id: String,
    val name: String,
    val sources: List<SceneSource> = emptyList()
)

data class SceneSource(
    val id: String,
    val type: SourceType,
    val displayName: String,
    val enabled: Boolean = true
)

enum class SourceType {
    TEXT,
    IMAGE,
    VIDEO,
    CAMERA,
    SCREEN,
    BROWSER
}
