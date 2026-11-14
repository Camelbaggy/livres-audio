package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriveMediaUriTest {
    private val ref = DriveMediaRef("acc-1", "1AbC_dEf-2", "0123456789abcdef0123456789abcdef")

    @Test
    fun buildsInternalUri() {
        assertEquals(
            "drive://acc-1/1AbC_dEf-2?md5=0123456789abcdef0123456789abcdef",
            DriveMediaUri.build(ref)
        )
    }

    @Test
    fun omitsMd5WhenUnknown() {
        assertEquals("drive://acc-1/F1", DriveMediaUri.build(DriveMediaRef("acc-1", "F1", null)))
        assertEquals("drive://acc-1/F1", DriveMediaUri.build(DriveMediaRef("acc-1", "F1", "")))
    }

    @Test
    fun parseIsInverseOfBuild() {
        val refs = listOf(
            ref,
            DriveMediaRef("acc-1", "F1", null),
            DriveMediaRef("a b/c", "id?x=1&y", "ab cd"),
            DriveMediaRef("é", "ü", "m")
        )
        refs.forEach { assertEquals(it, DriveMediaUri.parse(DriveMediaUri.build(it)), it.toString()) }
    }

    @Test
    fun parseRejectsMalformedUris() {
        listOf(
            "",
            "https://www.googleapis.com/drive/v3/files/x?alt=media",
            "drive://",
            "drive://acc",
            "drive://acc/",
            "drive:///file",
            "drive://acc/file/extra"
        ).forEach { assertNull(DriveMediaUri.parse(it), it) }
    }

    @Test
    fun buildsDownloadUrl() {
        assertEquals(
            "https://www.googleapis.com/drive/v3/files/1AbC_dEf-2?alt=media",
            DriveMediaUri.toHttpUrl(ref)
        )
        assertEquals(
            "http://localhost:1/x/F1?alt=media",
            DriveMediaUri.toHttpUrl(ref.copy(fileId = "F1"), "http://localhost:1/x/")
        )
    }

    @Test
    fun cacheKeyIsStablePerFileAndContent() {
        assertEquals("drive:1AbC_dEf-2:0123456789abcdef0123456789abcdef", DriveMediaUri.cacheKey(ref))
        assertEquals("drive:F1:unknown", DriveMediaUri.cacheKey(DriveMediaRef("acc-1", "F1", null)))
        // The account does not change the key: the same file read by the other account shares the cache.
        assertEquals(DriveMediaUri.cacheKey(ref), DriveMediaUri.cacheKey(ref.copy(accountId = "acc-2")))
    }
}
