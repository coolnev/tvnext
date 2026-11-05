package com.tvnext.app

/** Half-open recording windows; touching boundaries are NOT conflicts. */
internal fun recordingSlotsOverlap(
    firstStartMs: Long,
    firstLengthMs: Long,
    secondStartMs: Long,
    secondLengthMs: Long,
): Boolean {
    require(firstLengthMs > 0 && secondLengthMs > 0)
    // Both lengths are bounded (up to six hours) before scheduling.
    return firstStartMs < secondStartMs + secondLengthMs &&
        secondStartMs < firstStartMs + firstLengthMs
}
