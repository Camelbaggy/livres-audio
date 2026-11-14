package io.github.camelbaggy.livresaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class OriginTest {
    @Test
    fun originFollowsAccountRole() {
        assertEquals(BookOrigin.SHARED, BookOrigin.from(AccountRole.SHARED))
        assertEquals(BookOrigin.PERSONAL, BookOrigin.from(AccountRole.PERSONAL))
    }
}
