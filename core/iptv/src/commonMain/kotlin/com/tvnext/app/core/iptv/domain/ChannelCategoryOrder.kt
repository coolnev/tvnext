package com.tvnext.app.core.iptv.domain

/** Provider/profile order: the first channel number in each category, with stable ties. */
class ChannelCategoryOrder {
    private val minimumNumbers = linkedMapOf<String, Int>()

    fun add(channel: Channel) {
        minimumNumbers[channel.category] = minOf(minimumNumbers[channel.category] ?: Int.MAX_VALUE, channel.number)
    }

    fun categories(): List<String> = minimumNumbers.keys.sortedBy { minimumNumbers.getValue(it) }
}

fun channelCategoriesInOrder(channels: List<Channel>): List<String> =
    ChannelCategoryOrder().apply { channels.forEach(::add) }.categories()
