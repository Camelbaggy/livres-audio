package io.github.camelbaggy.livresaudio.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderBrowserTest {
    @Test
    fun naturalSortTable() {
        val cases = listOf(
            listOf("Livres 10", "Livres 2", "livres 1") to listOf("livres 1", "Livres 2", "Livres 10"),
            listOf("CD 02", "CD 1", "CD 10") to listOf("CD 1", "CD 02", "CD 10"),
            listOf("b", "A", "a1") to listOf("A", "a1", "b"),
            listOf("Tome 3 - x", "Tome 12 - x") to listOf("Tome 3 - x", "Tome 12 - x")
        )
        for ((input, expected) in cases) {
            assertEquals(expected, input.map { FolderEntry(it, it) }.sortedNaturally().map { it.name })
        }
    }

    @Test
    fun foldersQueryMatchesSpec() {
        assertEquals(
            "'abc' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
            foldersQuery("abc")
        )
    }
}
