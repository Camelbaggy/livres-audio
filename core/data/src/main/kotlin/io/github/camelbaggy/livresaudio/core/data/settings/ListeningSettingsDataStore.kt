package io.github.camelbaggy.livresaudio.core.data.settings

import javax.inject.Qualifier

/** Qualifies the `DataStore<Preferences>` of the listening settings (the accounts one is unqualified). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ListeningSettingsDataStore
