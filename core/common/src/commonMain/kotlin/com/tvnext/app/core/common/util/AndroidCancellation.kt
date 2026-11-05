package com.tvnext.app.core.common.util

import com.tvnext.app.core.common.platform.isAndroidPlatform
import kotlinx.coroutines.CancellationException

/** Preserve structured cancellation on Android; desktop error semantics stay unchanged. */
fun Throwable.rethrowAndroidCancellation() {
    if (isAndroidPlatform() && this is CancellationException) throw this
}

inline fun <T> runPlatformCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    error.rethrowAndroidCancellation()
    Result.failure(error)
}
