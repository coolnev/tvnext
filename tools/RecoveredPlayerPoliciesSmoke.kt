import com.tvnext.app.core.player.*

/** Standalone JVM smoke checks; no Gradle, Android SDK or network necessary. */
fun main() {
    check(normalizePlaybackSeekStep(60_000) == 60_000L)
    check(normalizePlaybackSeekStep(300_000) == 300_000L)
    check(normalizePlaybackSeekStep(120_000) == 60_000L)
    check(!isTransientStreamFailure(PlaybackError.Network, httpStatus = 401))
    check(!isTransientStreamFailure(PlaybackError.Network, httpStatus = 403))
    check(!isTransientStreamFailure(PlaybackError.Decoder))
    check(!isTransientStreamFailure(PlaybackError.UnsupportedFormat))
    check(isTransientStreamFailure(PlaybackError.Network, httpStatus = 503))
    val recording = PlaybackItem("saved", "Test", "file:///tmp/test.ts", PlaybackContentKind.VOD)
    check(isNaturalStreamEnd(recording, 9_000L, 10_000L, false))
    check(!isNaturalStreamEnd(recording.copy(kind = PlaybackContentKind.LIVE), 9_000L, 10_000L, false))
    check(!isNaturalStreamEnd(recording, 9_000L, 10_000L, true))
    println("Player recovery policy smoke checks: OK")
}
