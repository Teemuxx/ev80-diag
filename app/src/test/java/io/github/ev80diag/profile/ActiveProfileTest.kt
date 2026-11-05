package io.github.ev80diag.profile

import io.github.ev80diag.data.store.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveProfileTest {
    private val vans = listOf(ProfileEntity(1, "ET", 0), ProfileEntity(2, "Lytee", 0))

    @Test fun resolve_storedOrFirst() {
        assertEquals(2L, ActiveProfile.resolve(vans, 2)?.id)
        assertEquals("stored van deleted", 1L, ActiveProfile.resolve(vans, 9)?.id)
        assertEquals("never set", 1L, ActiveProfile.resolve(vans, -1)?.id)
        assertNull(ActiveProfile.resolve(emptyList(), 1))
    }
}
