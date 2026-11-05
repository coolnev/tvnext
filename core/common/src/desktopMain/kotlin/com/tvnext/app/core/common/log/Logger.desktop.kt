package com.tvnext.app.core.common.log

internal actual fun platformLogDebug(tag: String, message: String) {
    println("[$tag] $message")
}

internal actual fun platformLogError(tag: String, message: String, throwable: Throwable?) {
    System.err.println("[$tag] $message")
    throwable?.printStackTrace()
}
