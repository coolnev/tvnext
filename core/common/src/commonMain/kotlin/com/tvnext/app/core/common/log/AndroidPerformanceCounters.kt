package com.tvnext.app.core.common.log

/** Bounded numeric telemetry only. No URLs, titles, descriptions or playlist credentials. */
object AndroidPerformanceCounters {
    private val lock = Any()
    private val reports = ArrayDeque<String>()
    fun report(line: String) = synchronized(lock) {
        reports.addLast(line)
        while (reports.size > 20) reports.removeFirst()
    }
    fun recentReport(): String = synchronized(lock) { reports.joinToString("\n") }
    private val values = linkedMapOf<String, Long>()
    fun set(name: String, value: Long) = synchronized(lock) {
        if (name in values || values.size < 40) values[name] = value
    }
    fun increment(name: String, count: Long = 1L) = synchronized(lock) {
        if (name in values || values.size < 40) values[name] = (values[name] ?: 0L) + count
    }
    fun snapshot(): Map<String, Long> = synchronized(lock) { values.toMap() }
}
