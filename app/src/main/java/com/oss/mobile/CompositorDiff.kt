package com.oss.mobile

/**
 * Returns the append-only delta when the current renderer sequence is a prefix of
 * the desired sequence. Returns null when an ordered rebuild is required.
 *
 * Renderer identity matters: a new instance under an existing layer ID must replace
 * the old GL filter rather than being mistaken for an unchanged layer.
 */
internal fun <T : Any> compositorAppendDelta(
    current: List<Pair<String, T>>,
    desired: List<Pair<String, T>>
): List<Pair<String, T>>? {
    if (current.size > desired.size) return null

    for (index in current.indices) {
        val old = current[index]
        val next = desired[index]
        if (old.first != next.first || old.second !== next.second) return null
    }

    return desired.drop(current.size)
}
