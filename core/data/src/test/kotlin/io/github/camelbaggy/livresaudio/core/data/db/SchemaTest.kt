package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Validates that the exported v1 schema (core/data/schemas) opens and matches the entities. */
@RunWith(RobolectricTestRunner::class)
class SchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test
    fun version1SchemaCreatesAndValidates() {
        helper.createDatabase("schema-test.db", 1).close()
        // Re-open with the room runtime validation of the current entities against the exported v1 schema.
        helper.runMigrationsAndValidate("schema-test.db", 1, true).close()
    }
}
