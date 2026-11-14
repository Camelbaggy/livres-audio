package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

data class ProbeReport(
    val booksProbed: Int,
    val filesProbed: Int,
    val booksReordered: Int,
    /** A transient failure (network, quota, authorisation) stopped the run: files stay unprobed, retry later. */
    val retryLater: Boolean
)

/**
 * Probes the files not yet probed, book by book, then re-applies the tag order (CLAUDE.md §5.2).
 *
 * Rules: a book is written all at once (nothing is saved if one of its files fails transiently, so tags are
 * never half applied); a file that cannot be analysed, or that Drive says is gone, is marked probed anyway to
 * avoid looping; a transient error (network...) leaves everything unprobed and stops the run.
 * The order pass runs on every book at every run, because each library walk resets `sortIndex` to the natural
 * order.
 */
class ProbeRunner(
    private val store: ProbeStore,
    private val sources: ProbeSourceFactory,
    private val probe: AudioProbe,
    private val covers: CoverStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun run(): ProbeReport {
        var books = 0
        var files = 0
        var retry = false
        for (book in store.loadBooks()) {
            val pending = book.files.filterNot { it.probed }
            if (pending.isEmpty()) continue
            try {
                probeBook(book, pending)
                books++
                files += pending.size
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                retry = true
                break
            }
        }
        val reordered = try {
            reorderAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        return ProbeReport(books, files, reordered, retry)
    }

    private suspend fun probeBook(book: ProbeBookRow, pending: List<ProbeFileRow>) {
        val results = LinkedHashMap<Long, ProbeResult>()
        for (file in pending) results[file.id] = probeFile(book, file)

        val order = TagFileOrdering.coherentOrder(
            book.files.map { f ->
                val tags = results[f.id]?.tags
                f.id to FileTrack(tags?.disc ?: f.discNumber, tags?.track ?: f.trackNumber)
            }
        ) ?: book.files.map { it.id }

        // Book information comes from this run's tags, and only if the first file of the book is among them:
        // otherwise a previous run already described the book and later files must not override it.
        val firstInRun = order.firstOrNull()?.let { it in results } == true
        var fields: EmbeddedFields? = null
        var coverUri: String? = null
        if (firstInRun) {
            val ordered = order.mapNotNull { results[it] }
            fields = EmbeddedInfo.fieldsFor(ordered.map { it.tags }, multiFile = book.files.size > 1)
                .takeUnless { it.isEmpty }
            coverUri = saveCover(book, order, results)
        }
        val outcomes = pending.map { f ->
            val r = results.getValue(f.id)
            FileProbeOutcome(f.id, f.driveFileId, f.md5, r.durationMs, r.tags.track, r.tags.disc, r.chapters)
        }
        store.commit(BookProbeCommit(book.bookId, outcomes, fields, coverUri, clock()))
    }

    /** Permanent Drive errors give an empty result; every other I/O failure propagates (retry later). */
    private suspend fun probeFile(book: ProbeBookRow, file: ProbeFileRow): ProbeResult = try {
        probe.probe(sources.open(book.accountId, file.driveFileId, file.sizeBytes.takeIf { it > 0 }), file.mimeType)
    } catch (e: DriveReadException) {
        if (e.error is DriveError.NotFound || e.error is DriveError.Forbidden) ProbeResult.EMPTY else throw e
    }

    private suspend fun saveCover(book: ProbeBookRow, order: List<Long>, results: Map<Long, ProbeResult>): String? {
        val byId = book.files.associateBy { it.id }
        for (id in order) {
            val cover = results[id]?.tags?.cover ?: continue
            if (cover.length !in 1..MAX_COVER_BYTES) continue
            val file = byId[id] ?: continue
            val source = sources.open(book.accountId, file.driveFileId, file.sizeBytes.takeIf { it > 0 })
            val bytes = try {
                source.read(cover.offset, cover.length.toInt())
            } catch (e: DriveReadException) {
                if (e.error is DriveError.NotFound || e.error is DriveError.Forbidden) continue else throw e
            }
            if (bytes.size.toLong() != cover.length) continue
            covers.save(book.bookId, bytes, cover.mimeType)?.let { return it }
        }
        return null
    }

    private suspend fun reorderAll(): Int {
        var changed = 0
        for (book in store.loadBooks()) {
            if (book.files.size < 2 || book.files.any { !it.probed }) continue
            val wanted = TagFileOrdering.coherentOrder(
                book.files.map { it.id to FileTrack(it.discNumber, it.trackNumber) }
            ) ?: continue
            if (wanted != book.files.map { it.id }) {
                store.applyOrder(book.bookId, wanted)
                changed++
            }
        }
        return changed
    }

    companion object {
        const val MAX_COVER_BYTES = 4L * 1024 * 1024
    }
}
