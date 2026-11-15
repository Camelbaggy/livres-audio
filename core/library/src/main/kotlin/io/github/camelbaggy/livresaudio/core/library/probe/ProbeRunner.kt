package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ProbeReport(
    /** Phase 1: books whose information (tags, cover) was just written. */
    val booksProbed: Int,
    /** Phase 2: files completed (duration, track/disc, chapters). */
    val filesProbed: Int,
    val booksReordered: Int,
    /** A transient failure (network, quota, authorisation) left work undone: retry later. */
    val retryLater: Boolean,
    /** The time budget ran out before the end of the work: run again now. */
    val timeUp: Boolean = false,
    /** HTTP requests and bytes received over the whole run (sources that report them), and the part of the requests spent in phase 1. */
    val requests: Int = 0,
    val bytes: Long = 0,
    val showcaseRequests: Int = 0,
    val elapsedMs: Long = 0
) {
    val progressed: Boolean get() = booksProbed + filesProbed > 0

    /** Something is still to probe (budget spent or failures): the caller must schedule another run. */
    val hasRemainingWork: Boolean get() = retryLater || timeUp
}

/**
 * Probes the audio files in two phases (CLAUDE.md §5.2, "Sondage"), the books being listened to first:
 *
 * 1. **Showcase**: for every book not yet described, ONLY its first file, in light mode (tags, cover, header
 *    duration, no chapters, no sample table). The information and the cover are committed at once, so the whole
 *    library gets titles and covers in a few minutes.
 * 2. **Details**: every file not fully probed: duration, track/disc, embedded chapters. One commit per file.
 *
 * Up to [parallelism] probes run at once (HTTP backoff on 403/5xx stays in the client's `RetryInterceptor`).
 * An error on one file skips its book until the next run; an account that fails [MAX_CONSECUTIVE_FAILURES] times
 * in a row (offline, authorisation revoked) is left alone for the rest of the run, which then reports
 * [ProbeReport.retryLater]. A file that cannot be analysed, or that Drive says is gone, is marked probed anyway to
 * avoid looping. The run stops starting new work once [run]'s time budget is spent ([ProbeReport.timeUp]); what
 * was committed stays.
 *
 * The tag order pass runs at the end of every run, because each library walk resets `sortIndex` to the natural
 * order. Book information is written by phase 1 only; phase 2 rewrites it only when the tag order makes another
 * file the first one (and that file was probed in the same run).
 */
class ProbeRunner(
    private val store: ProbeStore,
    private val sources: ProbeSourceFactory,
    private val probe: AudioProbe,
    private val covers: CoverStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val networkAvailable: () -> Boolean = { true },
    /**
     * Phase 2 reads whole `moov` boxes (several MB per M4B, ~1 GB for a large library): only on an unmetered
     * network (Wi-Fi), except for an on-demand run of a single book. Phase 1 (covers, titles) runs everywhere.
     */
    private val detailsAllowed: () -> Boolean = { true },
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) {
    /** [onlyBookId]: probe this book only (on-demand run, both phases). */
    suspend fun run(timeBudgetMs: Long = Long.MAX_VALUE, onlyBookId: String? = null): ProbeReport {
        val state = RunState(monotonicMs, timeBudgetMs)
        if (!networkAvailable()) return state.report(booksReordered = 0, retryLater = true)

        val showcase = prioritized(store.loadBooks(), onlyBookId).filter { it.needsShowcase() }
        forEachBounded(showcase) { showcase(state, it) }
        state.endOfShowcase()

        val details = prioritized(store.loadBooks(), onlyBookId).filter { b -> b.files.any { !it.probed } }
        if (onlyBookId != null || detailsAllowed()) {
            forEachBounded(details) { details(state, it) }
        } else if (details.isNotEmpty()) {
            state.incomplete.set(true) // retried later, typically once on Wi-Fi
        }

        val reordered = try {
            reorderAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        return state.report(reordered, retryLater = state.incomplete.get())
    }

    private fun ProbeBookRow.needsShowcase(): Boolean =
        files.firstOrNull()?.let { !it.tagsProbed && !it.probed } == true

    /** Books being listened to first (most recent `lastPlayedAt`), then the others in store order. */
    private fun prioritized(books: List<ProbeBookRow>, onlyBookId: String?): List<ProbeBookRow> =
        books.filter { onlyBookId == null || it.bookId == onlyBookId }
            .sortedByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }

    private suspend fun <T> forEachBounded(items: List<T>, action: suspend (T) -> Unit) = coroutineScope {
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        for (item in items) launch { gate.withPermit { action(item) } }
    }

    // ---- Phase 1 -----------------------------------------------------------------------------------------

    private suspend fun showcase(state: RunState, book: ProbeBookRow) {
        val first = book.files.first()
        if (!state.mayStart(book.accountId)) return
        val source = sources.open(book.accountId, first.driveFileId, first.sizeBytes.takeIf { it > 0 })
        try {
            val result = probeFile(source, first, withChapters = false)
            val fields = EmbeddedInfo.fieldsFor(listOf(result.tags), multiFile = book.files.size > 1)
                .takeUnless { it.isEmpty }
            val coverUri = saveCover(book.bookId, result.tags.cover, source)
            store.commitFile(
                FileProbeCommit(
                    bookId = book.bookId,
                    file = FileProbeOutcome(
                        fileId = first.id,
                        driveFileId = first.driveFileId,
                        md5 = first.md5,
                        durationMs = result.durationMs,
                        track = result.tags.track,
                        disc = result.tags.disc,
                        chapters = emptyList(),
                        complete = false
                    ),
                    fields = fields,
                    coverUri = coverUri,
                    nowMs = clock()
                )
            )
            state.succeeded(book.accountId)
            state.booksProbed.incrementAndGet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            state.failed(book.accountId)
        } finally {
            state.addTransfer(source)
        }
    }

    // ---- Phase 2 -----------------------------------------------------------------------------------------

    private suspend fun details(state: RunState, book: ProbeBookRow) {
        val results = HashMap<Long, ProbeResult>()
        val sourcesOf = HashMap<Long, ByteRangeSource>()
        for (file in book.files.filterNot { it.probed }) {
            if (!state.mayStart(book.accountId)) return
            val source = sources.open(book.accountId, file.driveFileId, file.sizeBytes.takeIf { it > 0 })
            try {
                val result = probeFile(source, file, withChapters = true)
                store.commitFile(
                    FileProbeCommit(
                        bookId = book.bookId,
                        file = FileProbeOutcome(
                            fileId = file.id,
                            driveFileId = file.driveFileId,
                            md5 = file.md5,
                            durationMs = result.durationMs,
                            track = result.tags.track,
                            disc = result.tags.disc,
                            chapters = result.chapters,
                            complete = true
                        ),
                        fields = null,
                        coverUri = null,
                        nowMs = clock()
                    )
                )
                state.succeeded(book.accountId)
                state.filesProbed.incrementAndGet()
                results[file.id] = result
                sourcesOf[file.id] = source
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                state.failed(book.accountId)
                return // the book is retried at the next run
            } finally {
                state.addTransfer(source)
            }
        }
        refreshInfoIfFirstFileChanged(state, book, results, sourcesOf)
    }

    /** The tag order may put another file first: the book information then comes from that file. */
    private suspend fun refreshInfoIfFirstFileChanged(
        state: RunState,
        book: ProbeBookRow,
        results: Map<Long, ProbeResult>,
        sourcesOf: Map<Long, ByteRangeSource>
    ) {
        if (book.files.size < 2) return
        val order = TagFileOrdering.coherentOrder(
            book.files.map { f ->
                val tags = results[f.id]?.tags
                f.id to FileTrack(tags?.disc ?: f.discNumber, tags?.track ?: f.trackNumber)
            }
        ) ?: return
        val newFirst = order.first()
        if (newFirst == book.files.first().id) return
        val result = results[newFirst] ?: return
        val source = sourcesOf[newFirst] ?: return
        try {
            val fields = EmbeddedInfo.fieldsFor(listOf(result.tags), multiFile = true).takeUnless { it.isEmpty }
            val coverUri = saveCover(book.bookId, result.tags.cover, source)
            store.commitInfo(book.bookId, fields, coverUri, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // the information of the first phase stays; nothing more to do
        } finally {
            state.addTransfer(source)
        }
    }

    // ---- Shared ------------------------------------------------------------------------------------------

    /** Permanent Drive errors give an empty result; every other I/O failure propagates (retry later). */
    private suspend fun probeFile(source: ByteRangeSource, file: ProbeFileRow, withChapters: Boolean): ProbeResult =
        try {
            probe.probe(source, file.mimeType, withChapters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveReadException) {
            if (e.error is DriveError.NotFound || e.error is DriveError.Forbidden) ProbeResult.EMPTY else throw e
        } catch (e: IOException) {
            throw e
        } catch (_: RuntimeException) {
            ProbeResult.EMPTY // a parser bug on one file must not make every run start over on it
        }

    private suspend fun saveCover(bookId: String, cover: EmbeddedCover?, source: ByteRangeSource): String? {
        if (cover == null || cover.length !in 1..MAX_COVER_BYTES) return null
        val bytes = try {
            source.read(cover.offset, cover.length.toInt())
        } catch (e: DriveReadException) {
            if (e.error is DriveError.NotFound || e.error is DriveError.Forbidden) return null else throw e
        }
        if (bytes.size.toLong() != cover.length) return null
        return covers.save(bookId, bytes, cover.mimeType)
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

    /** Counters and stop conditions of one run, shared by the concurrent probes. */
    private class RunState(private val monotonicMs: () -> Long, private val budgetMs: Long) {
        private val startedMs = monotonicMs()
        val booksProbed = AtomicInteger()
        val filesProbed = AtomicInteger()
        val incomplete = AtomicBoolean(false)
        private val timeUpFlag = AtomicBoolean(false)
        private val requests = AtomicInteger()
        private val bytes = AtomicLong()
        private val showcaseRequestsAtEnd = AtomicInteger()
        private val consecutiveFailures = ConcurrentHashMap<String, AtomicInteger>()

        /** False when the time budget is spent or the account has failed too often in a row. */
        fun mayStart(accountId: String): Boolean {
            if (monotonicMs() - startedMs >= budgetMs) timeUpFlag.set(true)
            if (timeUpFlag.get()) return false
            if ((consecutiveFailures[accountId]?.get() ?: 0) >= MAX_CONSECUTIVE_FAILURES) {
                incomplete.set(true)
                return false
            }
            return true
        }

        fun succeeded(accountId: String) {
            consecutiveFailures[accountId]?.set(0)
        }

        fun failed(accountId: String) {
            incomplete.set(true)
            consecutiveFailures.getOrPut(accountId) { AtomicInteger() }.incrementAndGet()
        }

        fun addTransfer(source: ByteRangeSource) {
            val stats = source as? TransferStats ?: return
            requests.addAndGet(stats.requestCount)
            bytes.addAndGet(stats.bytesFetched)
        }

        fun endOfShowcase() {
            showcaseRequestsAtEnd.set(requests.get())
        }

        fun report(booksReordered: Int, retryLater: Boolean) = ProbeReport(
            booksProbed = booksProbed.get(),
            filesProbed = filesProbed.get(),
            booksReordered = booksReordered,
            retryLater = retryLater,
            timeUp = timeUpFlag.get(),
            requests = requests.get(),
            bytes = bytes.get(),
            showcaseRequests = showcaseRequestsAtEnd.get(),
            elapsedMs = monotonicMs() - startedMs
        )
    }

    companion object {
        const val MAX_COVER_BYTES = 4L * 1024 * 1024
        const val DEFAULT_PARALLELISM = 4
        const val MAX_CONSECUTIVE_FAILURES = 5
        private const val NANOS_PER_MS = 1_000_000L
    }
}
