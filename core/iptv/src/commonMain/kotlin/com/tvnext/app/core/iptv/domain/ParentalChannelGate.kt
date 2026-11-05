package com.tvnext.app.core.iptv.domain

/** Android installs a PIN prompt at the application root. Desktop is unaffected.
 * If access is pending the caller must not start the stream; the granted callback
 * re-enters the normal selection path after authorization. */
object ParentalChannelGate {
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
