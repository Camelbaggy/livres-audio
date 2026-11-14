package io.github.camelbaggy.livresaudio.core.library.probe

import java.io.File
import java.security.MessageDigest

/** Keeps the cover pictures extracted from audio files. */
interface CoverStore {
    /** Stores [bytes] as the cover of [bookId] and returns the URI to put in `coverUri`, or null on failure. */
    fun save(bookId: String, bytes: ByteArray, mimeType: String?): String?
}

/**
 * Files in [dir] (app private storage, not the system-purgeable cache), named `<bookId>-<hash>.<ext>`:
 * the hash changes with the picture so that image loaders never serve a stale cover. Older files of the same
 * book are deleted once the new one is in place.
 */
class FileCoverStore(private val dir: File) : CoverStore {
    override fun save(bookId: String, bytes: ByteArray, mimeType: String?): String? = try {
        val ext = extensionFor(bytes) ?: return null
        dir.mkdirs()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }
        val target = File(dir, "$bookId-$hash.$ext")
        if (!target.exists()) {
            val tmp = File(dir, "$bookId-$hash.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
        }
        dir.listFiles { f -> f.name.startsWith("$bookId-") && f != target }?.forEach { it.delete() }
        "file://${target.absolutePath}"
    } catch (_: java.io.IOException) {
        null
    }

    companion object {
        private const val HASH_BYTES = 6

        /** Magic numbers win over the declared type: a corrupt or unsupported picture is refused. */
        fun extensionFor(bytes: ByteArray): String? = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"

            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"

            bytes.size > 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"

            else -> null
        }
    }
}
