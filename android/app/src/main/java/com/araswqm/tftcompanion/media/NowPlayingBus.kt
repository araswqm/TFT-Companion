package com.araswqm.tftcompanion.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Şu an çalan medya akışı. Hem MediaSessionWatcher (birincil) hem de
 * MediaNotificationListener (yedek) buraya besler; ViewModel tek noktadan dinler.
 */
object NowPlayingBus {

    private val _flow = MutableSharedFlow<NowPlaying>(replay = 1, extraBufferCapacity = 8)
    val flow: SharedFlow<NowPlaying> = _flow.asSharedFlow()

    fun emit(nowPlaying: NowPlaying) {
        _flow.tryEmit(nowPlaying)
    }
}
