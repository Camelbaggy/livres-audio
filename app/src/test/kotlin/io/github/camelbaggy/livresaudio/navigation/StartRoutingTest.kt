package io.github.camelbaggy.livresaudio.navigation

import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.onboarding.account
import io.github.camelbaggy.livresaudio.onboarding.toChosenRoots
import kotlin.test.Test
import kotlin.test.assertEquals

class StartRoutingTest {
    @Test
    fun routingTable() {
        val cases = listOf(
            Triple(0, 0, AppDestination.Onboarding),
            Triple(1, 0, AppDestination.Onboarding),
            Triple(0, 1, AppDestination.Onboarding),
            Triple(1, 1, AppDestination.Library),
            Triple(2, 3, AppDestination.Library)
        )
        for ((accounts, roots, expected) in cases) {
            assertEquals(expected, resolveStartDestination(accounts, roots), "accounts=$accounts roots=$roots")
        }
    }

    @Test
    fun rootsAreJoinedWithTheirAccount() {
        val a = account("a@example.org", AccountRole.PERSONAL)
        val chosen = toChosenRoots(listOf(a), listOf(WatchedRootEntity(7, a.id.value, "f1", "Livres", 0)))
        assertEquals("a@example.org", chosen.single().accountEmail)
        assertEquals(7L, chosen.single().id)
    }
}
