package io.github.camelbaggy.livresaudio.core.data.identity

import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BookIdentityTest {
    private val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    @Test
    fun uuidV5MatchesReferenceVectors() {
        // Reference values computed with Python's uuid.uuid5 (RFC 4122 DNS namespace).
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", uuidV5(dns, "python.org").toString())
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", uuidV5(dns, "www.example.com").toString())
    }

    @Test
    fun bookIdUsesProjectNamespaceOnDriveId() {
        assertEquals("f4142e1c-3dfc-5614-b43c-883b631cce6b", bookIdFor(BookAnchor.Folder("folder-abc123")))
    }

    @Test
    fun singleFileBookIdIsBasedOnFileIdNotParent() {
        val a = bookIdFor(BookAnchor.SingleFile("fileX", "parent1"))
        val b = bookIdFor(BookAnchor.SingleFile("fileX", "parent2"))
        assertEquals(a, b)
        assertNotEquals(a, bookIdFor(BookAnchor.SingleFile("fileY", "parent1")))
    }

    @Test
    fun fingerprintIsSha256OfSortedLowercasedMd5s() {
        // sha256("aaa\nbbb") computed with hashlib.
        val expected = "f62677b0cf3c886e32d8bd5c6d95860d0af79de15382e48b5b0770d65592870c"
        assertEquals(expected, fingerprint(listOf("bbb", "aaa")))
        assertEquals(expected, fingerprint(listOf("AAA", "bbb")))
        assertNotEquals(expected, fingerprint(listOf("aaa")))
    }
}
