package com.tvnext.app.core.common.util

import com.tvnext.app.core.common.platform.isAndroidPlatform
import kotlinx.coroutines.CancellationException

/** Preserve structured cancellation on both Android and Desktop. */
fun Throwable.rethrowAndroidCancellation() {
    if (this is CancellationException) throw this
}

inline fun <T> runPlatformCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    error.rethrowAndroidCancellation()
    Result.failure(error)
}
