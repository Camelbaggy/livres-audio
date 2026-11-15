package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Migrations of the library database: they must keep every row, positions first (CLAUDE.md §11.3). */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test
    fun migration1To2AddsTagsProbedAndKeepsEveryRow() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO books (bookId, accountId, rootId, driveFolderId, anchorKind, anchorDriveId, origin, " +
                    "fingerprint, state, addedAt, totalBytes, fieldSources, matchState, providerIds) " +
                    "VALUES ('b1', 'acc', 'root', 'F1', 'FOLDER', 'F1', 'PERSONAL', 'fp', 'PRESENT', 1, 10, '{}', " +
                    "'UNMATCHED', '{}')"
            )
            execSQL(
                "INSERT INTO book_files (bookId, driveFileId, name, mimeType, sizeBytes, md5, sortIndex, " +
                    "durationMs, trackNumber, discNumber, probed) VALUES ('b1', 'f1', 'a.mp3', 'audio/mpeg', 5, " +
                    "'m1', 0, 1000, 1, NULL, 1), ('b1', 'f2', 'b.mp3', 'audio/mpeg', 5, 'm2', 1, NULL, NULL, NULL, 0)"
            )
            execSQL(
                "INSERT INTO progress (bookId, driveFileId, fileMd5, fileIndex, positionMs, bookPositionMs, status, " +
                    "speed, lastPlayedAt, updatedAt, dirty) VALUES ('b1', 'f1', 'm1', 0, 123456, 123456, " +
                    "'IN_PROGRESS', NULL, 99, 99, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)

        db.query("SELECT driveFileId, probed, tagsProbed, durationMs FROM book_files ORDER BY sortIndex").use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals("f1", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals(1, it.getInt(2)) // already fully probed: tags are known
            assertEquals(1000, it.getInt(3))
            it.moveToNext()
            assertEquals("f2", it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
        }
        db.query("SELECT positionMs, dirty FROM progress WHERE bookId = 'b1'").use {
            it.moveToFirst()
            assertEquals(123456, it.getInt(0))
            assertEquals(1, it.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM books").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun theWholeMigrationChainOpensTheCurrentSchema() {
        helper.createDatabase(NAME, 1).close()
        helper.runMigrationsAndValidate(NAME, 2, true, *LIBRARY_MIGRATIONS).close()
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
