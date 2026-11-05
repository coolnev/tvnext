import com.tvnext.app.core.player.VideoAspectMode
import com.tvnext.app.core.player.videoViewportSize

fun main() {
    check(VideoAspectMode.entries.size == VideoAspectMode.entries.map { it.token }.toSet().size)
    VideoAspectMode.entries.forEach { check(VideoAspectMode.fromToken(it.token) == it) }
    check(VideoAspectMode.resolved("inherit", "4:3") == VideoAspectMode.RATIO_4_3)
    check(VideoAspectMode.resolved("16:9", "4:3") == VideoAspectMode.RATIO_16_9)
    check(VideoAspectMode.resolved(null, null) == VideoAspectMode.AUTO)
    check(videoViewportSize(1280, 720, 4f / 3f) == (960 to 720))
    check(videoViewportSize(1280, 720, 9f / 16f) == (405 to 720))
    check(videoViewportSize(1280, 720, null) == (1280 to 720))
    check(videoViewportSize(1280, 720, Float.NaN) == (1280 to 720))
    println("Video aspect mode smoke: OK")
}
