package com.oss.mobile

/**
 * Local RTMP destination settings.
 * Do not log or expose streamKey in UI diagnostics or crash reports.
 */
data class StreamDestination(
    val name: String,
    val serverUrl: String,
    val streamKey: String
) {
    fun isValid(): Boolean =
        (serverUrl.startsWith("rtmp://") || serverUrl.startsWith("rtmps://")) &&
            streamKey.isNotBlank()
}
