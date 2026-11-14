package io.github.camelbaggy.livresaudio.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscDetectionTest {
    @Test
    fun recognisesDiscAndPartFolders() {
        val table = mapOf(
            "CD 1" to 1, "CD1" to 1, "cd 02" to 2, "Disque 2" to 2, "Disc 3" to 3, "Disc1" to 1,
            "Partie 4" to 4, "Part 5" to 5, "Disque 3 - Suite" to 3
        )
        table.forEach { (name, n) -> assertEquals(n, DiscDetection.discNumber(name), name) }
    }

    @Test
    fun rejectsOtherNames() {
        listOf("Discours 2", "Partition", "Cycle Sombre", "2", "Les Brumes", "CD", "Particulier 3").forEach {
            assertNull(DiscDetection.discNumber(it), it)
        }
    }

    @Test
    fun allDiscFolders() {
        assertTrue(DiscDetection.allDiscFolders(listOf("CD 1", "CD 2")))
        assertFalse(DiscDetection.allDiscFolders(listOf("CD 1", "Bonus")))
        assertFalse(DiscDetection.allDiscFolders(emptyList()))
    }
}
