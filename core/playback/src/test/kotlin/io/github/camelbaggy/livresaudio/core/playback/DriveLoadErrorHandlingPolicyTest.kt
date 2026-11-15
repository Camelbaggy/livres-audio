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
import java.net.ConnectException
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

    private val open = HttpDataSource.HttpDataSourceException.TYPE_OPEN

    private fun httpException(cause: IOException) =
        HttpDataSource.HttpDataSourceException.createForIOException(cause, dataSpec, open)

    /** Every way the network can fail is retried forever, 1 s growing to 8 s, never given up. */
    @Test
    fun everyKindOfNetworkFailureWaitsInsteadOfFailing() {
        val failures = mapOf(
            "unknown host" to UnknownHostException(),
            "timeout" to SocketTimeoutException(),
            "connection refused" to ConnectException(),
            "connection reset" to java.net.SocketException("Connection reset"),
            "http exception without a code (host)" to httpException(UnknownHostException()),
            "http exception without a code (connect)" to httpException(ConnectException()),
            "http exception without a code (timeout)" to httpException(SocketTimeoutException()),
            "no network, token unobtainable" to AuthFailedException(AuthResult.NetworkUnavailable),
            "server busy" to http(503),
            "server error" to http(500),
            "throttled" to http(429),
            "request timeout" to http(408)
        )
        val expected = mapOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 5 to 8_000L, 100_000 to 8_000L)
        failures.forEach { (name, error) ->
            expected.forEach { (count, delay) ->
                assertEquals(delay, policy.getRetryDelayMsFor(info(error, count)), "$name, errorCount=$count")
            }
        }
    }

    /** 404 is final; 401 reaches the policy only after the Authenticator's single renewal failed: nothing to retry. */
    @Test
    fun finalFailuresStopTheLoader() {
        listOf(401, 403, 404, 410, 416).forEach { code ->
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(http(code), 1)), "HTTP $code")
        }
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(AuthFailedException(AuthResult.NeedsUserAction), 1)))
    }

    @Test
    fun everyFailureIsReportedToTheSignal() {
        val seen = mutableListOf<LoadFailure>()
        val reporting = DriveLoadErrorHandlingPolicy { seen += it }
        reporting.getRetryDelayMsFor(info(SocketTimeoutException(), 1))
        reporting.getRetryDelayMsFor(info(http(404), 1))
        assertEquals(listOf(LoadFailure.Network, LoadFailure.Http(404)), seen)
    }
}

@Suppress("DiscouragedPrivateApi")
internal fun blankDataSpec(): DataSpec {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(DataSpec::class.java) as DataSpec
}
