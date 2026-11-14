package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.DriveItem
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatsTest {
    private fun item(name: String, mime: String = "application/octet-stream", size: Long? = null) =
        DriveItem("id-$name", name, mime, "p", sizeBytes = size)

    @Test
    fun classifiesByExtension() {
        val table = mapOf(
            "a.mp3" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.M4B" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.m4a" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.aac" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.ogg" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.opus" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.flac" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.wav" to AudioFormats.Kind.SUPPORTED_AUDIO,
            "a.wma" to AudioFormats.Kind.UNSUPPORTED_AUDIO,
            "a.jpg" to AudioFormats.Kind.IMAGE,
            "a.PNG" to AudioFormats.Kind.IMAGE,
            "a.txt" to AudioFormats.Kind.OTHER,
            "a.pdf" to AudioFormats.Kind.OTHER,
            "notes" to AudioFormats.Kind.OTHER
        )
        table.forEach { (name, kind) -> assertEquals(kind, AudioFormats.classify(item(name)), name) }
    }

    @Test
    fun classifiesByMimeWhenExtensionIsUnknown() {
        assertEquals(AudioFormats.Kind.SUPPORTED_AUDIO, AudioFormats.classify(item("piste", "audio/mpeg")))
        assertEquals(AudioFormats.Kind.UNSUPPORTED_AUDIO, AudioFormats.classify(item("piste", "audio/x-ms-wma")))
        assertEquals(AudioFormats.Kind.IMAGE, AudioFormats.classify(item("pochette", "image/jpeg")))
    }

    @Test
    fun googleDocumentsAndFoldersAreNotAudio() {
        assertEquals(
            AudioFormats.Kind.OTHER,
            AudioFormats.classify(item("Notes.gsheet", "application/vnd.google-apps.spreadsheet"))
        )
        assertEquals(AudioFormats.Kind.OTHER, AudioFormats.classify(item("dossier.mp3", DriveItem.FOLDER_MIME_TYPE)))
    }

    @Test
    fun stripsOnlyKnownExtensions() {
        assertEquals("Série - 3.5", AudioFormats.stripKnownExtension("Série - 3.5"))
        assertEquals("Série - 3.5", AudioFormats.stripKnownExtension("Série - 3.5.m4b"))
        assertEquals("Nom", AudioFormats.stripKnownExtension("Nom.mp3"))
    }

    @Test
    fun coverNamedCoverWinsThenFolderThenLargest() {
        val images = listOf(
            item("big.jpg", size = 900),
            item("Folder.png", size = 10),
            item("small.jpg", size = 5),
            item("cover.jpg", size = 1)
        )
        assertEquals(
            listOf("cover.jpg", "Folder.png", "big.jpg", "small.jpg"),
            AudioFormats.selectCovers(images).map { it.name }
        )
    }

    @Test
    fun noImageMeansNoCandidate() {
        assertEquals(emptyList(), AudioFormats.selectCovers(emptyList()))
    }
}
