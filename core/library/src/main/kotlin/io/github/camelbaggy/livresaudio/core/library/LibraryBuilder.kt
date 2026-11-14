package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem

/**
 * Turns an already listed Drive tree into books (CLAUDE.md §5.2 rules 1 to 5, docs/formes-de-noms.md). Pure.
 *
 * For each folder below the root:
 * - leaf folder (no sub-folder holding audio) with audio files: one book anchored on the folder, unless
 *   [PartsOrBooks] says every file is a book (then each file is a [BookAnchor.SingleFile] book);
 *   a leaf folder with a single audio file whose name is structured (series, volume or author) or whose folder is
 *   numbered (`5 - Préludes`) is read as a single-file book too;
 * - folder with no direct audio whose audio sub-folders are all disc folders (`CD 1`...): one book (rule 3);
 * - any other folder is a grouping: its own audio files are single-file books (rule 4), and it is walked on.
 * Audio files directly in the root are single-file books. Folders with no audio below them are ignored.
 *
 * Guards: depth limit and a visited set against `parentId` cycles.
 */
class LibraryBuilder(private val fileOrdering: FileOrdering = NaturalFileOrdering, private val maxDepth: Int = 12) {
    fun build(
        rootId: String,
        items: List<DriveItem>,
        groupingRole: GroupingRole = GroupingRole.AUTO
    ): List<DiscoveredBook> = Walk(rootId, items, groupingRole).run()

    private inner class Walk(private val rootId: String, items: List<DriveItem>, private val role: GroupingRole) {
        private val childrenByParent: Map<String, List<DriveItem>> =
            items.filter { it.parentId != null }.groupBy { it.parentId!! }
        private val byName = NaturalSort.by<DriveItem> { it.name }
        private val visited = mutableSetOf<String>()
        private val audioCache = mutableMapOf<String, Boolean>()
        private val out = mutableListOf<DiscoveredBook>()

        fun run(): List<DiscoveredBook> {
            visit(rootId, null, emptyList(), 0)
            return out
        }

        private fun children(folderId: String): List<DriveItem> =
            childrenByParent[folderId].orEmpty().sortedWith(byName)

        /** True if [folderId] holds audio (also unsupported audio) directly or below. */
        private fun hasAudio(folderId: String): Boolean {
            audioCache[folderId]?.let { return it }
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.add(folderId to 0)
            var found = false
            while (stack.isNotEmpty() && !found) {
                val (id, depth) = stack.removeLast()
                if (!seen.add(id) || depth > maxDepth) continue
                for (c in childrenByParent[id].orEmpty()) {
                    if (c.isFolder) {
                        stack.add(c.id to depth + 1)
                    } else if (isAudio(c)) {
                        found = true
                    }
                }
            }
            audioCache[folderId] = found
            return found
        }

        private fun isAudio(item: DriveItem): Boolean = AudioFormats.classify(item).let {
            it == AudioFormats.Kind.SUPPORTED_AUDIO || it == AudioFormats.Kind.UNSUPPORTED_AUDIO
        }

        private fun visit(folderId: String, folderName: String?, ancestors: List<String>, depth: Int) {
            if (depth > maxDepth || !visited.add(folderId)) return
            val kids = children(folderId)
            val folders = kids.filter { it.isFolder }
            val audio = kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.SUPPORTED_AUDIO }
            val unsupported = kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.UNSUPPORTED_AUDIO }
            val images = kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.IMAGE }
            val audioFolders = folders.filter { hasAudio(it.id) }
            val isRoot = folderName == null
            val selfGrouping = if (isRoot) ancestors else ancestors + folderName!!

            if (!isRoot && audioFolders.isEmpty() && (audio.isNotEmpty() || unsupported.isNotEmpty())) {
                leafFolder(folderId, folderName!!, ancestors, audio, unsupported, images)
                return
            }
            if (!isRoot && audio.isEmpty() && unsupported.isEmpty() &&
                DiscDetection.allDiscFolders(audioFolders.map { it.name }) && audioFolders.size == folders.size
            ) {
                discBook(folderId, folderName!!, ancestors, audioFolders, images)
                return
            }
            // Grouping folder (or the root): own audio files are single-file books, then walk on.
            val own = (audio + unsupported).sortedWith(byName)
            for (f in own) {
                addSingleFile(f, folderId, selfGrouping, images, soleAudio = own.size == 1 && audioFolders.isEmpty())
            }
            for (sub in audioFolders) visit(sub.id, sub.name, selfGrouping, depth + 1)
        }

        private fun leafFolder(
            folderId: String,
            folderName: String,
            ancestors: List<String>,
            audio: List<DriveItem>,
            unsupported: List<DriveItem>,
            images: List<DriveItem>
        ) {
            val all = (audio + unsupported).sortedWith(byName)
            val selfGrouping = ancestors + folderName
            val oneBookPerFile = if (all.size >= 2) {
                PartsOrBooks.decide(all.map { it.name }) == PartsOrBooks.Decision.ONE_BOOK_PER_FILE
            } else {
                singleFileIsStructured(folderName, all.first())
            }
            if (oneBookPerFile) {
                for (f in all) addSingleFile(f, folderId, selfGrouping, images, soleAudio = all.size == 1)
                return
            }
            out += DiscoveredBook(
                anchor = BookAnchor.Folder(folderId),
                rootId = rootId,
                files = fileOrdering.order(audio),
                groupingNames = ancestors,
                parsed = NameParser.parse(folderName, ancestors, role),
                coverCandidates = AudioFormats.selectCovers(images),
                unsupportedFiles = unsupported
            )
        }

        private fun singleFileIsStructured(folderName: String, file: DriveItem): Boolean {
            if (NameParser.stripOrderPrefix(folderName).second != null) return true
            val p = NameParser.parse(file.name)
            return p.seriesIndex != null || p.seriesName != null || p.author != null
        }

        private fun discBook(
            folderId: String,
            folderName: String,
            ancestors: List<String>,
            discs: List<DriveItem>,
            images: List<DriveItem>
        ) {
            val ordered = discs.sortedWith(
                compareBy<DriveItem> { DiscDetection.discNumber(it.name) ?: Int.MAX_VALUE }.then(byName)
            )
            val files = mutableListOf<DriveItem>()
            val unsupported = mutableListOf<DriveItem>()
            val covers = images.toMutableList()
            for (d in ordered) {
                val kids = children(d.id)
                files += fileOrdering.order(
                    kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.SUPPORTED_AUDIO }
                )
                unsupported += kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.UNSUPPORTED_AUDIO }
                covers += kids.filter { AudioFormats.classify(it) == AudioFormats.Kind.IMAGE }
            }
            visited += discs.map { it.id }
            out += DiscoveredBook(
                anchor = BookAnchor.Folder(folderId),
                rootId = rootId,
                files = files,
                groupingNames = ancestors,
                parsed = NameParser.parse(folderName, ancestors, role),
                coverCandidates = AudioFormats.selectCovers(covers),
                unsupportedFiles = unsupported
            )
        }

        private fun addSingleFile(
            file: DriveItem,
            parentFolderId: String,
            grouping: List<String>,
            images: List<DriveItem>,
            soleAudio: Boolean
        ) {
            val supported = AudioFormats.classify(file) == AudioFormats.Kind.SUPPORTED_AUDIO
            val stem = NameParser.key(AudioFormats.stripKnownExtension(file.name))
            val covers = if (soleAudio) {
                AudioFormats.selectCovers(images)
            } else {
                AudioFormats.selectCovers(
                    images.filter { NameParser.key(AudioFormats.stripKnownExtension(it.name)) == stem }
                )
            }
            out += DiscoveredBook(
                anchor = BookAnchor.SingleFile(file.id, parentFolderId),
                rootId = rootId,
                files = if (supported) listOf(file) else emptyList(),
                groupingNames = grouping,
                parsed = NameParser.parse(file.name, grouping, role),
                coverCandidates = covers,
                unsupportedFiles = if (supported) emptyList() else listOf(file)
            )
        }
    }
}
