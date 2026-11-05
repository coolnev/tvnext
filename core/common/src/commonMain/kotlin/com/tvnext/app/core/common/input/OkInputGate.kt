package com.tvnext.app.core.common.input

/** Cross-screen guard for the physical OK key when a long press changes screens. */
object OkInputGate {
    @Volatile
    var suppressNextKeyUp: Boolean = false
}
