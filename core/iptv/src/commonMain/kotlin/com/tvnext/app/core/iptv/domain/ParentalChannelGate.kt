package com.tvnext.app.core.iptv.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ParentalAccess(val enabled: Boolean = true, val unlocked: Boolean = false) {
    val restrictionsActive: Boolean get() = enabled && !unlocked
}

/** Android installs a PIN prompt at the application root. Desktop is unaffected.
 * If access is pending the caller must not start the stream; the granted callback
 * re-enters the normal selection path after authorization. */
object ParentalChannelGate {
    private val access = MutableStateFlow(ParentalAccess())
    val session = access.asStateFlow()
    fun setEnabled(enabled: Boolean) { access.update { it.copy(enabled = enabled) } }
    fun unlock() { access.update { it.copy(unlocked = true) } }
    fun lock() { access.update { it.copy(unlocked = false) } }

    var handler: ((Channel, () -> Unit) -> Boolean)? = null

    fun isRestricted(channel: Channel): Boolean {
        val categories = channel.category.lowercase()
        val title = channel.name.lowercase()
        val explicit = listOf("xxx", "порно", "эротик", "adult", "porn", "erotic", "для взрослых", "sex", "18+")
        val titles = listOf("xxx", "порно", "эротик", "porn", "erotic", "adult")
        return explicit.any { categories.contains(it) } || titles.any { title.contains(it) }
    }

    fun request(channel: Channel, onGranted: () -> Unit): Boolean =
        handler?.invoke(channel, onGranted) ?: true
}
