package com.tvnext.app.core.common.util

/**
 * Formats an epoch-millis timestamp as local wall-clock `HH:mm`.
 * Used by the EPG UI (programme start/stop times).
 */
expect fun formatTimeOfDay(epochMillis: Long): String

/** Formats an epoch-millis timestamp as `dd.MM.yyyy HH:mm` (local time). */
expect fun formatDateTime(epochMillis: Long): String

/** Formats an epoch-millis timestamp as a local date `d MMMM` (e.g. «10 августа»). */
expect fun formatDate(epochMillis: Long): String

/** Formats an epoch-millis timestamp as local wall-clock `HH:mm:ss` (on-screen clock). */
expect fun formatClockTime(epochMillis: Long): String

/** Start of the LOCAL day (00:00:00.000) containing [epochMillis]. */
expect fun startOfDay(epochMillis: Long): Long

/** Start of the local calendar day [dayOffset] days from the day containing [epochMillis]. */
expect fun startOfLocalDayOffset(epochMillis: Long, dayOffset: Int): Long
