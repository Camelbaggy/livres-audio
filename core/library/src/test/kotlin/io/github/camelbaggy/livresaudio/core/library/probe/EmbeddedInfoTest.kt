package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.data.db.AnchorKind
import io.github.camelbaggy.livresaudio.core.data.db.BookEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookField
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.FieldSource
import io.github.camelbaggy.livresaudio.core.model.MatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedInfoTest {
    private fun tags(
        title: String? = null,
        album: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        composer: String? = null,
        narrator: String? = null
    ) = AudioTags(
        title = title,
        album = album,
        artist = artist,
        albumArtist = albumArtist,
        composer = composer,
        narrator = narrator
    )

    @Test
    fun livreMultiFichiersTitreVientDeLAlbum() {
        val f = EmbeddedInfo.fieldsFor(
            listOf(
                tags(title = "Chapitre 1", album = "Le Livre", artist = "Auteur"),
                tags(title = "Chapitre 2", album = "Le Livre")
            ),
            multiFile = true
        )
        assertEquals("Le Livre", f.title)
        assertEquals("Auteur", f.author)
    }

    @Test
    fun livreMultiFichiersSansAlbumNAPasDeTitre() {
        assertNull(EmbeddedInfo.fieldsFor(listOf(tags(title = "Chapitre 1")), multiFile = true).title)
    }

    @Test
    fun livreDUnSeulFichierTitreSinonAlbum() {
        assertEquals("Titre", EmbeddedInfo.fieldsFor(listOf(tags(title = "Titre", album = "Album")), false).title)
        assertEquals("Album", EmbeddedInfo.fieldsFor(listOf(tags(album = "Album")), false).title)
    }

    @Test
    fun auteurEtNarrateur() {
        // Tableau : étiquettes -> (auteur, narrateur) attendus
        val cases = listOf(
            tags(albumArtist = "A", artist = "B", composer = "N") to ("A" to "N"),
            tags(artist = "B", composer = "N") to ("B" to "N"),
            tags(artist = "B", composer = "N", narrator = "Explicite") to ("B" to "Explicite"),
            tags(artist = "Même", composer = "même") to ("Même" to null),
            tags(artist = "Unknown Artist", composer = "Inconnu") to (null to null),
            tags(artist = "  Nom   Espacé ") to ("Nom Espacé" to null)
        )
        for ((t, expected) in cases) {
            val f = EmbeddedInfo.fieldsFor(listOf(t), false)
            assertEquals(expected, f.author to f.narrator, t.toString())
        }
    }

    @Test
    fun serieAnneeEtDescriptionPremierFichierQuiLesPorte() {
        val f = EmbeddedInfo.fieldsFor(
            listOf(
                AudioTags(album = "X"),
                AudioTags(series = "La Saga", seriesIndex = 0.0, year = 2010, description = "Résumé"),
                AudioTags(series = "Autre", seriesIndex = 4.0, year = 2011)
            ),
            multiFile = true
        )
        assertEquals("La Saga", f.seriesName)
        assertNull(f.seriesIndex) // 0 n'est pas un tome exploitable ici
        assertEquals(2010, f.year)
        assertEquals("Résumé", f.description)
        assertTrue(EmbeddedInfo.fieldsFor(emptyList(), false).isEmpty)
    }

    // --- fusion par provenance --------------------------------------------------------------------------

    private fun book(sources: Map<String, FieldMeta> = emptyMap(), title: String? = null, author: String? = null) =
        BookEntity(
            bookId = "b", accountId = "a", rootId = "r", driveFolderId = "f", anchorKind = AnchorKind.FOLDER,
            anchorDriveId = "f", origin = BookOrigin.PERSONAL, fingerprint = "", state = BookState.PRESENT,
            missingSince = null, addedAt = 0, totalDurationMs = null, totalBytes = 0, title = title, author = author,
            narrator = null, seriesName = null, seriesIndex = null, description = null, year = null, coverUri = null,
            fieldSources = sources, matchState = MatchState.UNMATCHED, providerIds = emptyMap()
        )

    @Test
    fun uneValeurUserNestJamaisEcrasee() {
        val user = FieldMeta(FieldSource.USER, 10)
        val b = book(mapOf(BookField.TITLE to user), title = "Mon titre").withEmbedded(
            EmbeddedFields(title = "Titre étiquette", author = "Auteur"),
            coverUri = null,
            nowMs = 5_000
        )
        assertEquals("Mon titre", b.title)
        assertEquals(user, b.fieldSources[BookField.TITLE])
        assertEquals("Auteur", b.author)
        assertEquals(FieldSource.EMBEDDED, b.fieldSources[BookField.AUTHOR]?.source)
    }

    @Test
    fun embeddedRemplaceFolderEtOnlineMaisPasLInverse() {
        val folder = FieldMeta(FieldSource.FOLDER, 9_000)
        val b = book(mapOf(BookField.TITLE to folder), title = "Titre du dossier")
            .withEmbedded(EmbeddedFields(title = "Titre étiquette"), "file:///c.jpg", 1_000)
        assertEquals("Titre étiquette", b.title)
        assertEquals("file:///c.jpg", b.coverUri)
        assertEquals(FieldSource.EMBEDDED, b.fieldSources[BookField.COVER_URI]?.source)

        val online = FieldMeta(FieldSource.ONLINE, 1)
        val b2 = book(
            mapOf(BookField.TITLE to online),
            title = "En ligne"
        ).withEmbedded(EmbeddedFields(title = "T"), null, 99)
        assertEquals("T", b2.title) // EMBEDDED > ONLINE (CLAUDE.md §4.3)
    }

    @Test
    fun uneValeurNulleNEffacePasEtLeResteEstInchange() {
        val b = book(title = "Garde", author = "Garde aussi").withEmbedded(EmbeddedFields(), null, 1)
        assertEquals("Garde", b.title)
        assertEquals("Garde aussi", b.author)
        assertTrue(b.fieldSources.isEmpty())
    }
}
