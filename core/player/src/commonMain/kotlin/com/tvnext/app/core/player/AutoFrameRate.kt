package com.tvnext.app.core.player

import kotlin.math.abs
import kotlin.math.round

/** Android display matching is opt-in. No default output mode is hard-coded. */
data class AutoFrameRatePreferences(
    val enabled: Boolean = false,
    val strategy: FrameRateSwitchStrategy = FrameRateSwitchStrategy.ALWAYS,
    val matchResolution: Boolean = false,
    val doubleFrameRate: Boolean = true,
    val fullscreenOnly: Boolean = true,
    val delayMs: Int = 1000,
) {
    fun normalized() = copy(delayMs = delayMs.takeIf { it in setOf(500,1000,2000,3000) } ?: 1000)
}
enum class FrameRateSwitchStrategy { SEAMLESS, ALWAYS }
data class VideoFrameSignal(val width: Int = 0, val height: Int = 0, val fps: Double = 0.0) {
    val validFps: Boolean get() = fps.isFinite() && fps in 10.0..240.0
}
data class VideoDisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Double,
    val alternativeRefreshRates: List<Double> = emptyList())
enum class FrameRateMatchState { OFF, WAITING, READY, REQUESTED, APPLIED, NO_MATCH, UNAVAILABLE, REJECTED }
data class AutoFrameRateStatus(
    val modes: List<VideoDisplayMode> = emptyList(),
    val current: VideoDisplayMode? = null,
    val requested: VideoDisplayMode? = null,
    val video: VideoFrameSignal? = null,
    val state: FrameRateMatchState = FrameRateMatchState.OFF,
)

fun frameRateNumber(value: Double): String = if (!value.isFinite() || value <= 0.0) "—" else
    (round(value * 1000.0) / 1000.0).toString().removeSuffix(".0")

/** A 24/23.976 or 60/59.94 mismatch must not count as exact cadence. */
fun cadenceMatches(refresh: Double, fps: Double): Boolean {
    if (!refresh.isFinite() || !fps.isFinite() || fps !in 10.0..240.0) return false
    val multiple = round(refresh / fps)
    return multiple in 1.0..12.0 && abs(refresh - fps * multiple) <= .015
}

fun chooseVideoDisplayMode(
    modes: List<VideoDisplayMode>, current: VideoDisplayMode, original: VideoDisplayMode,
    video: VideoFrameSignal, preferences: AutoFrameRatePreferences,
): VideoDisplayMode? {
    if (!preferences.enabled) return null
    val matchSize = preferences.matchResolution && preferences.strategy == FrameRateSwitchStrategy.ALWAYS &&
        video.width > 0 && video.height > 0
    var candidates = modes.filter { it.id > 0 && it.width > 0 && it.height > 0 && it.refreshRate.isFinite() && it.refreshRate in 10.0..300.0 }
    if (!matchSize) candidates = candidates.filter { it.width == original.width && it.height == original.height }
    if (preferences.strategy == FrameRateSwitchStrategy.SEAMLESS) candidates = candidates.filter {
        it.width == current.width && it.height == current.height &&
            (it.id == current.id || current.alternativeRefreshRates.any { hz -> abs(hz-it.refreshRate)<.015 })
    }
    if (candidates.isEmpty()) return null
    val width=maxOf(video.width,video.height);val height=minOf(video.width,video.height)
    if(matchSize) {
        val fits=candidates.filter{it.width>=width && it.height>=height}
        candidates=if(fits.isNotEmpty())fits else {
            val largest=candidates.maxOf{it.width.toLong()*it.height}
            candidates.filter{it.width.toLong()*it.height==largest}
        }
    }
    val matched = if (video.validFps) candidates.filter { cadenceMatches(it.refreshRate,video.fps) } else emptyList()
    if(matched.isEmpty() && !matchSize)return null
    // No suitable cadence: preserve the output rate. Resolution may still follow the video.
    candidates = if (matched.isNotEmpty()) matched else candidates.filter { abs(it.refreshRate-current.refreshRate)<.015 }
    if (candidates.isEmpty() || (!video.validFps && !matchSize)) return null
    if (matched.isNotEmpty() && preferences.doubleFrameRate && video.fps < 31) {
        val doubled = candidates.filter { it.refreshRate >= video.fps*2-.015 }
        if(doubled.isNotEmpty()) candidates=doubled
    }
    return candidates.minWithOrNull(compareBy<VideoDisplayMode>(
        { if(matchSize && (it.width<width || it.height<height)) 1 else 0 },
        { if(matchSize) abs(it.width.toLong()*it.height-width.toLong()*height) else 0L },
        { if(it.id==current.id) 0 else 1 },
        { it.refreshRate }, { it.id },
    ))
}

/** Bounded PTS sample, used only when extractor FPS is absent. Never measures UI/render wall time. */
class VideoCadenceEstimator {
    private val deltas = LongArray(48)
    private var size=0; private var cursor=0;private var last=Long.MIN_VALUE;private var published=0.0
    @Synchronized fun reset() {size=0;cursor=0;last=Long.MIN_VALUE;published=0.0}
    @Synchronized fun frame(presentationTimeUs:Long): Double? {
        if(last==Long.MIN_VALUE){last=presentationTimeUs;return null}
        val delta=presentationTimeUs-last;last=presentationTimeUs
        if(delta==0L)return null
        if(delta !in 3_000L..120_000L){size=0;cursor=0;return null}
        deltas[cursor]=delta;cursor=(cursor+1)%deltas.size;size=(size+1).coerceAtMost(deltas.size)
        if(size<24 || cursor%12!=0)return null
        val sorted=deltas.copyOf(size).sorted();val median=sorted[size/2].toDouble()
        if(sorted.count {abs(it-median)<median*.015}<size*.9)return null
        var fps=1_000_000.0/median
        val standards=listOf(24000.0/1001,24.0,25.0,30000.0/1001,30.0,48000.0/1001,48.0,50.0,60000.0/1001,60.0,100.0,120000.0/1001,120.0)
        standards.minByOrNull {abs(it-fps)}?.takeIf {abs(it-fps)<.012}?.let{fps=it}
        if(fps !in 10.0..240.0 || abs(fps-published)<.01)return null
        published=fps;return fps
    }
}
