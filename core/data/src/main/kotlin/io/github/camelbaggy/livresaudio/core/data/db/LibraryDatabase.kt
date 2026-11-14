package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Local source of truth (CLAUDE.md §4.2). Schema exported to `core/data/schemas/`.
 * Never use `fallbackToDestructiveMigration`: it would erase listening positions (CLAUDE.md §11.3).
 */
@Database(
    entities = [
        AccountEntity::class,
        WatchedRootEntity::class,
        BookEntity::class,
        BookFileEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        MatchCandidateEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun watchedRootDao(): WatchedRootDao

    abstract fun bookDao(): BookDao

    abstract fun bookFileDao(): BookFileDao

    abstract fun chapterDao(): ChapterDao

    abstract fun progressDao(): ProgressDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun matchCandidateDao(): MatchCandidateDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "library.db"
    }
}
