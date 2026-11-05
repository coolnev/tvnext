package com.tvnext.app

fun main() {
    check(recordingSlotsOverlap(0, 60_000, 30_000, 90_000))
    check(recordingSlotsOverlap(30_000, 90_000, 0, 60_000))
    check(!recordingSlotsOverlap(0, 60_000, 60_000, 60_000))
    check(!recordingSlotsOverlap(60_000, 60_000, 0, 60_000))
    check(recordingSlotsOverlap(100_000, 10_000, 90_000, 21_000))
    println("Recording schedule overlap tests: OK")
}
