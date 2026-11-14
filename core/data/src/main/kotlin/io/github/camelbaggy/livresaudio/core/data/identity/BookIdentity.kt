package io.github.camelbaggy.livresaudio.core.data.identity

import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Fixed UUID namespace of the project for deriving book identifiers (CLAUDE.md §4.4).
 * Random v4 value generated once; NEVER change it: both phones must compute the same `bookId`
 * for the same Drive anchor, and every stored position is keyed by it.
 */
const val BOOK_ID_NAMESPACE = "b3f1c2a4-5d6e-4f70-8a9b-0c1d2e3f4a5b"

/** `bookId = UUIDv5(project namespace, anchor.driveId)`: deterministic, no coordination needed. */
fun bookIdFor(anchor: BookAnchor): String = uuidV5(UUID.fromString(BOOK_ID_NAMESPACE), anchor.driveId).toString()

/** Name-based UUID version 5 (SHA-1), RFC 4122 §4.3 / RFC 9562 §5.5. */
fun uuidV5(namespace: UUID, name: String): UUID {
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(
        ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
    )
    sha1.update(name.toByteArray(Charsets.UTF_8))
    val hash = sha1.digest()
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte() // version 5
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte() // variant RFC 4122
    val buffer = ByteBuffer.wrap(hash, 0, 16)
    return UUID(buffer.long, buffer.long)
}

/**
 * Content fingerprint of a book (CLAUDE.md §4.4): SHA-256 (lowercase hex) of the sorted, lowercased
 * MD5 sums joined by `\n`. Independent of listing order.
 */
fun fingerprint(md5s: List<String>): String {
    val joined = md5s.map { it.lowercase() }.sorted().joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
