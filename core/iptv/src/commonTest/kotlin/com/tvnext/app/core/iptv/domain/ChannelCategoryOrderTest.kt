package com.tvnext.app.core.iptv.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelCategoryOrderTest {
    private fun channel(id: String, number: Int, category: String) = Channel(id,id,number,category,"https://unused.invalid/$id")

    @Test fun categoriesFollowTheirFirstChannelNumberInsteadOfAlphabetOrInputOrder() {
        val channels=listOf(channel("news20",20,"Я Новости"),channel("film",50,"А Кино"),
            channel("news1",1,"Я Новости"),channel("kids",3,"М Детские"))
        assertEquals(listOf("Я Новости","М Детские","А Кино"),channelCategoriesInOrder(channels))
    }

    @Test fun sameNumbersKeepFirstSeenOrderInTheChannelList() {
        val order=ChannelCategoryOrder()
        listOf(channel("z",5,"Я"),channel("a",5,"А"),channel("m",1,"М")).forEach(order::add)
        assertEquals(listOf("М","Я","А"),order.categories())
    }

    @Test fun personalRenumberingAndEmptyListsHaveTheSameOrderingRules() {
        val original=listOf(channel("a",1,"Я"),channel("b",2,"А"))
        assertEquals(listOf("А","Я"),channelCategoriesInOrder(original.map {it.copy(number=3-it.number)}))
        assertEquals(emptyList(),channelCategoriesInOrder(emptyList()))
    }
}
