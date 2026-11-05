package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Watchdog stall/repair-decision contracts: never confuse real network
 * buffering, a just-performed seek or a healthy position with a decoder stall;
 * never repair twice per attempt; explicit HARDWARE repairs IN PLACE on the
 * same GPU pipeline (never silently switches to software).
 */
class StallFallbackTest {

    private fun input(
        isNonLive: Boolean = true,
        isHardwareOutput: Boolean = true,
        fallbackAlreadyApplied: Boolean = false,
        isBuffering: Boolean = false,
        positionFrozenForMs: Long = 7_000L,
    ) = StallDecisionInput(
        isNonLive = isNonLive,
        isHardwareOutput = isHardwareOutput,
        fallbackAlreadyApplied = fallbackAlreadyApplied,
        isBuffering = isBuffering,
        positionFrozenForMs = positionFrozenForMs,
    )

    private val stallTimeoutMs = 6_000L

    @Test
    fun confirmedStallTriggersRepair() {
        assertTrue(shouldRepairOnStall(input(), stallTimeoutMs))
    }

    @Test
    fun realNetworkBufferingNeverTriggersRepair() {
        // libVLC reports BUFFERING (the demuxer is catching up) — the player
        // must be left alone, NOT destroyed/recreated.
        assertFalse(
            shouldRepairOnStall(input(isBuffering = true), stallTimeoutMs),
            "active buffering must suppress the stall repair",
        )
    }

    @Test
    fun briefFreezeBelowWindowIsNotAStall() {
        // A short freeze (e.g. while VLC locates the next HLS segment) is not
        // a decoder stall yet.
        assertFalse(
            shouldRepairOnStall(input(positionFrozenForMs = 3_000L), stallTimeoutMs),
            "freeze shorter than the window must not repair",
        )
    }

    @Test
    fun exactlyAtWindowTriggersRepair() {
        assertTrue(
            shouldRepairOnStall(input(positionFrozenForMs = stallTimeoutMs), stallTimeoutMs),
        )
    }

    @Test
    fun liveContentNeverTriggersRepair() {
        assertFalse(
            shouldRepairOnStall(input(isNonLive = false), stallTimeoutMs),
            "LIVE hardware is stable — never touch it",
        )
    }

    @Test
    fun explicitHardwareStillRepairsInPlace() {
        // The user asked for hardware only (weak CPUs): a confirmed stall in
        // explicit HARDWARE must rebuild the SAME hardware pipeline (like the
        // F11 window round-trip that restores the picture) — never switch to
        // software. The decision itself applies to hardware regardless of mode.
        assertTrue(
            shouldRepairOnStall(input(), stallTimeoutMs),
            "hardware stall must trigger an in-place rebuild",
        )
    }

    @Test
    fun softwareOutputNeverTriggersRepair() {
        assertFalse(
            shouldRepairOnStall(input(isHardwareOutput = false), stallTimeoutMs),
            "software backend has nothing to repair",
        )
    }

    @Test
    fun noRepairLoop() {
        assertFalse(
            shouldRepairOnStall(input(fallbackAlreadyApplied = true), stallTimeoutMs),
            "one repair per play attempt — no rebuild loop",
        )
    }
}
