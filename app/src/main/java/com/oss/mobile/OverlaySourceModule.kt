package com.oss.mobile

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender

/**
 * Stable contract between OSS Core and an installed visual-source module.
 *
 * A module describes its identity and capabilities, then creates renderer instances
 * from validated, module-owned configuration. Core does not need to know whether the
 * source is text, an image, an animation, a browser, or another visual type.
 */
interface OverlaySourceModule {
    val descriptor: OverlayModuleDescriptor

    /**
     * Create a renderer for one scene source.
     *
     * The returned renderer is owned by the module instance and is attached to the
     * stream only by OverlayCompositor.
     */
    fun createRenderer(
        context: Context,
        configuration: Map<String, String>
    ): BaseFilterRender
}

/**
 * Manifest-level information used by Plugin Center before a module is activated.
 */
data class OverlayModuleDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val minimumCoreApi: Int,
    val capabilities: Set<OverlayModuleCapability>
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) {
            "Module ID must be a stable lowercase identifier"
        }
        require(displayName.isNotBlank()) { "Module display name cannot be blank" }
        require(version.isNotBlank()) { "Module version cannot be blank" }
        require(minimumCoreApi > 0) { "Minimum Core API must be positive" }
    }
}

/**
 * Explicit capability declarations keep module permissions narrow and reviewable.
 */
enum class OverlayModuleCapability {
    RENDER_VISUAL,
    READ_SELECTED_MEDIA,
    ACCESS_NETWORK
}

/**
 * Versioned Core API exposed to compatible modules.
 * Increase only when a breaking module-contract change is introduced.
 */
object OverlayModuleApi {
    const val CURRENT_VERSION: Int = 1
}
