package com.oss.mobile

/**
 * Serializable-safe settings for static image sources. A layer references either
 * an app resource (legacy/test source) or a private imported-media ID.
 */
data class StaticImageLayerConfig(
    val resourceId: Int = 0,
    val imageFileId: String? = null,
    val xPercent: Float = 5f,
    val yPercent: Float = 5f,
    val widthPercent: Float = 24f,
    val opacity: Float = 1f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
) {
    init {
        require((resourceId > 0) xor (imageFileId != null)) {
            "Exactly one image source must be specified"
        }
        require(imageFileId == null || imageFileId.matches(Regex("[a-f0-9]{32}"))) {
            "imageFileId must be a valid imported image ID"
        }
        require(xPercent in 0f..100f) { "xPercent must be between 0 and 100" }
        require(yPercent in 0f..100f) { "yPercent must be between 0 and 100" }
        require(widthPercent in 1f..100f) { "widthPercent must be between 1 and 100" }
        require(opacity in 0f..1f) { "opacity must be between 0 and 1" }
    }

    companion object {
        fun from(values: Map<String, String>): StaticImageLayerConfig {
            fun floatValue(key: String, default: Float): Float =
                values[key]?.toFloatOrNull() ?: default

            val resourceId = values[KEY_RESOURCE_ID]?.toIntOrNull() ?: 0
            val imageFileId = values[KEY_IMAGE_FILE_ID]
            require((resourceId > 0) xor (imageFileId != null)) {
                "Missing or ambiguous image source configuration"
            }

            return StaticImageLayerConfig(
                resourceId = resourceId,
                imageFileId = imageFileId,
                xPercent = floatValue(KEY_X, 5f),
                yPercent = floatValue(KEY_Y, 5f),
                widthPercent = floatValue(KEY_WIDTH, 24f),
                opacity = floatValue(KEY_OPACITY, 1f),
                flipHorizontal = values[KEY_FLIP_HORIZONTAL]?.toBooleanStrictOrNull() ?: false,
                flipVertical = values[KEY_FLIP_VERTICAL]?.toBooleanStrictOrNull() ?: false
            )
        }

        const val KEY_RESOURCE_ID = "resource_id"
        const val KEY_IMAGE_FILE_ID = "image_file_id"
        const val KEY_X = "x_percent"
        const val KEY_Y = "y_percent"
        const val KEY_WIDTH = "width_percent"
        const val KEY_OPACITY = "opacity"
        const val KEY_FLIP_HORIZONTAL = "flip_horizontal"
        const val KEY_FLIP_VERTICAL = "flip_vertical"
    }
}
