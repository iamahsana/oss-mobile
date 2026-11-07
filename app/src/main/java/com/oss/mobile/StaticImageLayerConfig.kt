package com.oss.mobile

/**
 * Serializable-safe settings for the first static image source proof.
 * Coordinates and dimensions are percentages of the output canvas.
 */
data class StaticImageLayerConfig(
    val resourceId: Int,
    val xPercent: Float = 5f,
    val yPercent: Float = 5f,
    val widthPercent: Float = 24f,
    val opacity: Float = 1f
) {
    init {
        require(resourceId > 0) { "resourceId must be a positive Android resource ID" }
        require(xPercent in 0f..100f) { "xPercent must be between 0 and 100" }
        require(yPercent in 0f..100f) { "yPercent must be between 0 and 100" }
        require(widthPercent in 1f..100f) { "widthPercent must be between 1 and 100" }
        require(opacity in 0f..1f) { "opacity must be between 0 and 1" }
    }

    companion object {
        fun from(values: Map<String, String>): StaticImageLayerConfig {
            fun floatValue(key: String, default: Float): Float =
                values[key]?.toFloatOrNull() ?: default

            val resourceId = values[KEY_RESOURCE_ID]?.toIntOrNull()
                ?: throw IllegalArgumentException("Missing or invalid $KEY_RESOURCE_ID")

            return StaticImageLayerConfig(
                resourceId = resourceId,
                xPercent = floatValue(KEY_X, 5f),
                yPercent = floatValue(KEY_Y, 5f),
                widthPercent = floatValue(KEY_WIDTH, 24f),
                opacity = floatValue(KEY_OPACITY, 1f)
            )
        }

        const val KEY_RESOURCE_ID = "resource_id"
        const val KEY_X = "x_percent"
        const val KEY_Y = "y_percent"
        const val KEY_WIDTH = "width_percent"
        const val KEY_OPACITY = "opacity"
    }
}
