package io.github.camelbaggy.livresaudio.core.playback

import javax.inject.Qualifier

/** Dispatcher of the service's coroutines (player thread, `Dispatchers.Main.immediate` in production). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackMainDispatcher

/** Dispatcher of the position writes (never the main thread, see [PositionKeeper.flushBlocking]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackWriteDispatcher
