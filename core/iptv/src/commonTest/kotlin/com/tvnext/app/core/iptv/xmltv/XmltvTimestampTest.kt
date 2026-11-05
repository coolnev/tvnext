package com.tvnext.app.core.iptv.xmltv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmltvTimestampTest {

    @Test
    fun utcTimestamp() {
        // 2026-07-01 09:00:00 UTC
        assertEquals(1782896400000L, XmltvTimestamp.parse("20260701090000 +0000"))
    }

    @Test
    fun positiveOffsetShiftsTime() {
        // 2026-07-01 09:00:00 +0300 == 2026-07-01 06:00:00 UTC
        assertEquals(
            XmltvTimestamp.parse("20260701060000 +0000"),
            XmltvTimestamp.parse("20260701090000 +0300"),
        )
    }

    @Test
    fun negativeOffsetShiftsTime() {
        // 2026-07-01 09:00:00 -0500 == 2026-07-01 14:00:00 UTC
        assertEquals(
            XmltvTimestamp.parse("20260701140000 +0000"),
            XmltvTimestamp.parse("20260701090000 -0500"),
        )
    }

    @Test
    fun colonOffsetSupported() {
        assertEquals(
            XmltvTimestamp.parse("20260701090000 +0300"),
            XmltvTimestamp.parse("20260701090000 +03:00"),
        )
    }

    @Test
    fun missingOffsetIsUtc() {
        assertEquals(XmltvTimestamp.parse("20260701090000 +0000"), XmltvTimestamp.parse("20260701090000"))
    }

    @Test
    fun trailingSpaceTolerated() {
        assertEquals(
            XmltvTimestamp.parse("20260701090000 +0000"),
            XmltvTimestamp.parse(" 20260701090000 +0000 "),
        )
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(XmltvTimestamp.parse(""))
        assertNull(XmltvTimestamp.parse("not-a-date"))
        assertNull(XmltvTimestamp.parse("2026070109")) // too short
    }

    @Test
    fun outOfRangeComponentsReturnNull() {
        assertNull(XmltvTimestamp.parse("20261301090000 +0000")) // month 13
        assertNull(XmltvTimestamp.parse("20260732900000 +0000")) // hour 32
        assertNull(XmltvTimestamp.parse("20260701090000 +2500")) // offset 25h
    }

    @Test
    fun leapYearDay() {
        // 2024-02-29 00:00:00 UTC
        assertEquals(1709164800000L, XmltvTimestamp.parse("20240229000000 +0000"))
    }

    @Test
    fun beforeEpoch() {
        // 1960-01-01 00:00:00 UTC is a negative epoch.
        val millis = XmltvTimestamp.parse("19600101000000 +0000")
        assertEquals(-315619200000L, millis)
    }
}
