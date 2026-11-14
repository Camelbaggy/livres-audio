package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.model.DriveItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagFileOrderingTest {
    private fun t(disc: Int?, track: Int?) = FileTrack(disc, track)

    @Test
    fun ordreParPisteQuandCoherent() {
        val got = TagFileOrdering.coherentOrder(listOf("a" to t(null, 3), "b" to t(null, 1), "c" to t(null, 2)))
        assertEquals(listOf("b", "c", "a"), got)
    }

    @Test
    fun disqueAvantPiste() {
        val got = TagFileOrdering.coherentOrder(
            listOf("a" to t(2, 1), "b" to t(1, 2), "c" to t(2, 2), "d" to t(1, 1))
        )
        assertEquals(listOf("d", "b", "a", "c"), got)
    }

    @Test
    fun incoherentDonneNull() {
        // Tableau : nom du cas -> entrées
        val cases = mapOf(
            "un seul fichier" to listOf("a" to t(null, 1)),
            "piste manquante" to listOf("a" to t(null, 1), "b" to t(null, null)),
            "étiquettes absentes" to listOf("a" to t(null, 1), "b" to null),
            "pistes en double" to listOf("a" to t(null, 1), "b" to t(null, 1)),
            "disque présent sur une partie" to listOf("a" to t(1, 1), "b" to t(null, 2)),
            "même piste sur deux disques sans numéro de disque" to listOf("a" to t(null, 1), "b" to t(null, 1)),
            "liste vide" to emptyList()
        )
        for ((name, entries) in cases) assertNull(TagFileOrdering.coherentOrder(entries), name)
    }

    @Test
    fun deuxDisquesAvecPistesQuiRecommencentSontCoherents() {
        val got = TagFileOrdering.coherentOrder(listOf("a" to t(2, 1), "b" to t(1, 1)))
        assertEquals(listOf("b", "a"), got)
    }

    @Test
    fun implementationFileOrderingRepliSurLeTriNaturel() {
        fun item(id: String, name: String) = DriveItem(id, name, "audio/mpeg", "p")
        val files = listOf(item("1", "10.mp3"), item("2", "2.mp3"), item("3", "1.mp3"))
        val byTags = TagFileOrdering(mapOf("1" to t(null, 1), "2" to t(null, 2), "3" to t(null, 3)))
        assertEquals(listOf("1", "2", "3"), byTags.order(files).map { it.id })
        val none = TagFileOrdering(emptyMap())
        assertEquals(listOf("3", "2", "1"), none.order(files).map { it.id })
    }
}
