package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthFailedException
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Without Robolectric `Uri.parse` returns null and `DataSpec` refuses a null URI, so a blank [DataSpec] is
 * allocated without running its constructor: the policy never reads it, it only has to exist.
 */
@UnstableApi
class DriveLoadErrorHandlingPolicyTest {
    private val policy = DriveLoadErrorHandlingPolicy()
    private val dataSpec: DataSpec = blankDataSpec()

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))

    private fun info(error: IOException, errorCount: Int) = LoadErrorInfo(
        LoadEventInfo(1, dataSpec, dataSpec.uri, emptyMap(), 0, 0, 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        errorCount
    )

    @Test
    fun classifiesExceptions() {
        assertEquals(LoadFailure.Http(404), DriveLoadErrorHandlingPolicy.classify(http(404)))
        assertEquals(LoadFailure.Network, DriveLoadErrorHandlingPolicy.classify(SocketTimeoutException()))
        assertEquals(LoadFailure.Network, DriveLoadErrorHandlingPolicy.classify(UnknownHostException()))
        val wrapped = HttpDataSource.HttpDataSourceException.createForIOException(
            UnknownHostException(),
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
        )
        assertEquals(LoadFailure.Network, DriveLoadErrorHandlingPolicy.classify(wrapped))
        assertEquals(
            LoadFailure.AuthBlocked,
            DriveLoadErrorHandlingPolicy.classify(AuthFailedException(AuthResult.NeedsUserAction))
        )
        assertEquals(
            LoadFailure.Other,
            DriveLoadErrorHandlingPolicy.classify(ParserException.createForMalformedContainer("x", null))
        )
    }

    @Test
    fun networkErrorsAreRetriedForeverWithCappedDelay() {
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        val expected = mapOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 500 to 8_000L)
        expected.forEach { (count, delay) ->
            assertEquals(delay, policy.getRetryDelayMsFor(info(SocketTimeoutException(), count)), "count=$count")
        }
    }

    @Test
    fun http404StopsTheLoader() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(http(404), 1)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(AuthFailedException(AuthResult.NeedsUserAction), 1)))
    }

    @Test
    fun serverErrorsAreRetried() {
        assertEquals(1_000L, policy.getRetryDelayMsFor(info(http(503), 1)))
    }
}

@Suppress("DiscouragedPrivateApi")
private fun blankDataSpec(): DataSpec {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(DataSpec::class.java) as DataSpec
}
