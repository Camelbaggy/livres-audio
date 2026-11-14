package io.github.camelbaggy.livresaudio.core.library

/** Disc or part sub-folders (`CD 1`, `Disque 2`, `Partie 4`...) that make a folder a single book (rule 3). */
object DiscDetection {
    private val discRegex = Regex(
        """(?iu)^(?:cd|dvd|disque|disc|disk|partie|part)\s*[-_.]?\s*(\d{1,3})(?:\s*[-–:]\s*.*|\s+.*)?$"""
    )

    /** Disc or part number if [folderName] looks like a disc folder, else null. */
    fun discNumber(folderName: String): Int? =
        discRegex.matchEntire(NaturalSort.fold(folderName).trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isDiscFolderName(folderName: String): Boolean = discNumber(folderName) != null

    /** True when there is at least one name and all of them are disc folders. */
    fun allDiscFolders(folderNames: List<String>): Boolean =
        folderNames.isNotEmpty() && folderNames.all(::isDiscFolderName)
}
