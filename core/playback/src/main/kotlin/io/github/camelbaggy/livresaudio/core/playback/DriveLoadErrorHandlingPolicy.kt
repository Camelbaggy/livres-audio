package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthFailedException
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import java.io.IOException

/**
 * Load error policy for Drive sources (CLAUDE.md §5.4, trial version): see [LoadRetryRules].
 *
 * [getMinimumLoadableRetryCount] is huge on purpose: ExoPlayer surfaces a load error to the player only after
 * that many failures, so the player stays in "buffering" while the loader keeps retrying.
 */
@UnstableApi
class DriveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
        when (val decision = LoadRetryRules.decide(classify(loadErrorInfo.exception), loadErrorInfo.errorCount)) {
            is RetryDecision.RetryAfter -> decision.delayMs
            RetryDecision.Fatal -> C.TIME_UNSET
            RetryDecision.UseDefault -> super.getRetryDelayMsFor(loadErrorInfo)
        }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    companion object {
        /** Maps a Media3 load exception to a [LoadFailure]. */
        fun classify(exception: IOException): LoadFailure {
            val chain = generateSequence<Throwable>(exception) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                ?.let { return LoadFailure.Http(it.responseCode) }
            chain.filterIsInstance<AuthFailedException>().firstOrNull()?.let {
                return if (it.result == AuthResult.NetworkUnavailable) LoadFailure.Network else LoadFailure.AuthBlocked
            }
            if (chain.any { it is ParserException || it is HttpDataSource.CleartextNotPermittedException }) {
                return LoadFailure.Other
            }
            return LoadFailure.Network
        }

        private const val MAX_CAUSE_DEPTH = 8
    }
}
