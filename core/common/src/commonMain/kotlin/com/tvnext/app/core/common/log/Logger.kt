package com.tvnext.app.core.common.log

/** Stable public logging API; only top-level functions are expect/actual.
 *  This avoids the experimental expect/actual class mechanism on Kotlin 2.4.
 */
object Logger {
    fun d(tag: String, message: String) = platformLogDebug(tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        platformLogError(tag, message, throwable)
}

internal expect fun platformLogDebug(tag: String, message: String)
internal expect fun platformLogError(tag: String, message: String, throwable: Throwable?)
