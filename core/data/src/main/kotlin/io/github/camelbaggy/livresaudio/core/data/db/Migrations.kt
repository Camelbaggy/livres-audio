package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: `book_files.tagsProbed` (phase 1 of the probe: tags and cover read). Additive; a file already fully
 * probed counts as tags-probed. No data is touched otherwise (positions and bookmarks live in other tables).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_files ADD COLUMN tagsProbed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE book_files SET tagsProbed = probed")
    }
}

/** Every migration, in order. `fallbackToDestructiveMigration` is forbidden (CLAUDE.md §11.3). */
val LIBRARY_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
