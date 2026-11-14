package io.github.camelbaggy.livresaudio.core.data.repo

import javax.inject.Qualifier

/** Coroutine dispatcher used by the repositories (injected; tests pass a test dispatcher). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DataIoDispatcher
