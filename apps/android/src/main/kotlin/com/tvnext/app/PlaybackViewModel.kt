package com.tvnext.app

import androidx.lifecycle.ViewModel
import com.tvnext.app.core.player.AndroidPlaybackController
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlayerState
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.GlobalContext

/** Activity-retained playback owner; never retains an Activity or a Compose callback. */
class PlaybackViewModel : ViewModel() {
    private val controller = GlobalContext.get().get<PlaybackController>() as AndroidPlaybackController
    val state: StateFlow<PlayerState> = controller.state

    fun onForeground() = controller.enterForeground()
    fun onBackground() = controller.enterBackground()

    override fun onCleared() {
        controller.release()
    }
}
