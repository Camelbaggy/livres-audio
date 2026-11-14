package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.FileOrdering
import io.github.camelbaggy.livresaudio.core.library.NaturalFileOrdering
import io.github.camelbaggy.livresaudio.core.model.DriveItem

/** Disc and track numbers of one file, as read from its tags. */
data class FileTrack(val disc: Int?, val track: Int?)

/**
 * Order by disc then track, used AFTER the probe (CLAUDE.md §5.2): only when the numbers are coherent over the
 * whole book, otherwise [fallback] (natural sort of the names). Coherent = at least two files, every file has a
 * track number, discs are all present or all absent, and no (disc, track) pair repeats.
 */
class TagFileOrdering(
    private val tracks: Map<String, FileTrack>,
    private val fallback: FileOrdering = NaturalFileOrdering
) : FileOrdering {
    override fun order(files: List<DriveItem>): List<DriveItem> {
        val ids = coherentOrder(files.map { it.id to tracks[it.id] }) ?: return fallback.order(files)
        val byId = files.associateBy { it.id }
        return ids.map { byId.getValue(it) }
    }

    companion object {
        /** Keys in disc-then-track order, or null when the tags are not coherent (keep the current order). */
        fun <K> coherentOrder(entries: List<Pair<K, FileTrack?>>): List<K>? {
            if (entries.size < 2) return null
            val tracks = entries.map { it.second ?: return null }
            if (tracks.any { it.track == null }) return null
            val withDisc = tracks.count { it.disc != null }
            if (withDisc != 0 && withDisc != tracks.size) return null
            val keys = tracks.map { (it.disc ?: 1) to it.track!! }
            if (keys.toSet().size != keys.size) return null
            return entries.indices.sortedWith(
                compareBy({
                    keys[it].first
                }, { keys[it].second })
            ).map { entries[it].first }
        }
    }
}
