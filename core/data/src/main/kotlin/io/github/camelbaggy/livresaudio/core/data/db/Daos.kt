package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.camelbaggy.livresaudio.core.model.BookState
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY addedAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY addedAt")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE accounts SET isProfile = (id = :id)")
    suspend fun setOnlyProfile(id: String)

    @Query("UPDATE accounts SET isProfile = 0")
    suspend fun clearProfile()
}

@Dao
interface WatchedRootDao {
    @Query("SELECT * FROM watched_roots ORDER BY addedAt")
    fun observeAll(): Flow<List<WatchedRootEntity>>

    @Query("SELECT * FROM watched_roots WHERE accountId = :accountId ORDER BY addedAt")
    suspend fun getForAccount(accountId: String): List<WatchedRootEntity>

    @Query("SELECT * FROM watched_roots ORDER BY addedAt")
    suspend fun getAll(): List<WatchedRootEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(root: WatchedRootEntity): Long

    @Query("DELETE FROM watched_roots WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Row for the library grid: a present book and its optional progress. */
data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "bookId", entityColumn = "bookId")
    val progress: ProgressEntity?
)

data class BookWithFiles(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "bookId", entityColumn = "bookId")
    val files: List<BookFileEntity>,
    @Relation(parentColumn = "bookId", entityColumn = "bookId")
    val chapters: List<ChapterEntity>
)

@Dao
interface BookDao {
    @Transaction
    @Query("SELECT * FROM books WHERE state = 'PRESENT'")
    fun observePresentWithProgress(): Flow<List<BookWithProgress>>

    @Transaction
    @Query("SELECT * FROM books WHERE bookId = :bookId")
    fun observeWithFiles(bookId: String): Flow<BookWithFiles?>

    @Transaction
    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun getWithFiles(bookId: String): BookWithFiles?

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun get(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE rootId = :rootId")
    suspend fun getByRoot(rootId: String): List<BookEntity>

    /** Roots holding a book anchored on one of [driveIds] (folder or single file). */
    @Query("SELECT DISTINCT rootId FROM books WHERE anchorDriveId IN (:driveIds) OR driveFolderId IN (:driveIds)")
    suspend fun rootIdsForDriveIds(driveIds: List<String>): List<String>

    @Query("SELECT * FROM books WHERE accountId = :accountId")
    suspend fun getByAccount(accountId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE fingerprint = :fingerprint")
    suspend fun getByFingerprint(fingerprint: String): List<BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET state = :state, missingSince = :missingSince WHERE bookId IN (:ids)")
    suspend fun setState(ids: List<String>, state: BookState, missingSince: Long?)

    @Query("UPDATE books SET totalDurationMs = :durationMs WHERE bookId = :bookId")
    suspend fun setTotalDuration(bookId: String, durationMs: Long?)

    @Query("SELECT bookId FROM books WHERE state = 'MISSING' AND missingSince < :cutoffMs")
    suspend fun idsMissingBefore(cutoffMs: Long): List<String>

    /** Deletes books only; files/chapters/candidates cascade, `progress` and `bookmarks` never do. */
    @Query("DELETE FROM books WHERE bookId IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface BookFileDao {
    /** Roots of the books owning one of these Drive files. */
    @Query(
        "SELECT DISTINCT b.rootId FROM book_files f JOIN books b ON b.bookId = f.bookId " +
            "WHERE f.driveFileId IN (:driveFileIds)"
    )
    suspend fun rootIdsForDriveFileIds(driveFileIds: List<String>): List<String>

    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY sortIndex")
    suspend fun getForBook(bookId: String): List<BookFileEntity>

    @Insert
    suspend fun insert(files: List<BookFileEntity>)

    @Update
    suspend fun update(files: List<BookFileEntity>)

    @Query("DELETE FROM book_files WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query(
        "UPDATE book_files SET durationMs = :durationMs, trackNumber = :track, discNumber = :disc, probed = 1, " +
            "tagsProbed = 1 WHERE id = :id"
    )
    suspend fun setProbed(id: Long, durationMs: Long?, track: Int?, disc: Int?)

    /** Phase 1 of the probe: tags and header duration known, chapters not yet read (`probed` stays as is). */
    @Query(
        "UPDATE book_files SET durationMs = :durationMs, trackNumber = :track, discNumber = :disc, tagsProbed = 1 " +
            "WHERE id = :id"
    )
    suspend fun setTagsProbed(id: Long, durationMs: Long?, track: Int?, disc: Int?)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY indexInBook")
    suspend fun getForBook(bookId: String): List<ChapterEntity>

    @Insert
    suspend fun insert(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("UPDATE chapters SET indexInBook = :indexInBook WHERE id = :id")
    suspend fun setIndex(id: Long, indexInBook: Int)

    @Query("DELETE FROM chapters WHERE fileId IN (:fileIds)")
    suspend fun deleteForFiles(fileIds: List<Long>)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress ORDER BY lastPlayedAt DESC")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE dirty = 1")
    suspend fun getDirty(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}

@Dao
interface BookmarkDao {
    /** Live bookmarks of a book (tombstones hidden). */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY bookPositionMs")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun get(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE dirty = 1")
    suspend fun getDirty(): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)
}

@Dao
interface MatchCandidateDao {
    @Query("SELECT * FROM match_candidates WHERE bookId = :bookId ORDER BY score DESC")
    fun observeForBook(bookId: String): Flow<List<MatchCandidateEntity>>

    @Query("SELECT * FROM match_candidates WHERE bookId = :bookId ORDER BY score DESC")
    suspend fun getForBook(bookId: String): List<MatchCandidateEntity>

    @Insert
    suspend fun insert(candidates: List<MatchCandidateEntity>)

    @Query("DELETE FROM match_candidates WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun delete(key: String)
}
