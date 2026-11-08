package com.oss.mobile

/** Pure reference scan used by media garbage collection and unit tests. */
object SceneMediaAssetReferences {
    fun collect(documents: Collection<SceneDocument>): Set<String> =
        documents.asSequence()
            .flatMap { it.layers.asSequence() }
            .mapNotNull { layer ->
                when (layer.moduleId) {
                    SceneGifLayerConfig.MODULE_ID,
                    SceneVideoLayerConfig.MODULE_ID -> layer.configuration["asset_id"]
                    else -> null
                }
            }
            .filter { it.matches(Regex("[a-f0-9]{32}")) }
            .toSet()
}
