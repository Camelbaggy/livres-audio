package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.core.model.MatchState

/** How a book is anchored in Drive (mirror of `BookAnchor`). */
enum class AnchorKind { FOLDER, SINGLE_FILE }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String?,
    val role: AccountRole,
    val isProfile: Boolean,
    val addedAt: Long
)

@Entity(
    tableName = "watched_roots",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId"), Index(value = ["accountId", "driveFolderId"], unique = true)]
)
data class WatchedRootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val driveFolderId: String,
    val name: String,
    val addedAt: Long
)

/**
 * A book. No foreign key to accounts/roots: removing an account or a root must never delete books
 * (and thus never cascade towards listening data). Information fields carry their provenance in
 * [fieldSources] (JSON `{field: {source, updatedAt}}`, keys in [BookField]).
 */
@Entity(
    tableName = "books",
    indices = [Index("rootId"), Index("fingerprint"), Index("state")]
)
data class BookEntity(
    @PrimaryKey val bookId: String,
    val accountId: String,
    /** Drive folder id of the watched root the book was found under. */
    val rootId: String,
    /** Folder id (book folder, or parent folder of a single-file book). */
    val driveFolderId: String,
    val anchorKind: AnchorKind,
    /** Drive id that `bookId` is derived from (folder id or file id). */
    val anchorDriveId: String,
    val origin: BookOrigin,
    val fingerprint: String,
    val state: BookState,
    /** When the book was first seen missing (ms); null while PRESENT. Kept 90 days (CLAUDE.md §5.2). */
    val missingSince: Long?,
    val addedAt: Long,
    val totalDurationMs: Long?,
    val totalBytes: Long,
    val title: String?,
    val author: String?,
    val narrator: String?,
    val seriesName: String?,
    val seriesIndex: Double?,
    val description: String?,
    val year: Int?,
    val coverUri: String?,
    val fieldSources: Map<String, FieldMeta>,
    val matchState: MatchState,
    val providerIds: Map<String, String>
)

@Entity(
    tableName = "book_files",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId", "sortIndex"]),
        Index(value = ["bookId", "driveFileId"], unique = true),
        Index("md5")
    ]
)
data class BookFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val driveFileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val md5: String?,
    val sortIndex: Int,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val probed: Boolean
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "indexInBook"]), Index("fileId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val fileId: Long,
    val indexInBook: Int,
    val title: String?,
    val startMs: Long,
    val endMs: Long?
)

/**
 * Listening position, keyed by `bookId`. DELIBERATELY no foreign key to `books`: deleting or
 * purging a book must never delete a position (golden rule, CLAUDE.md).
 */
@Entity(tableName = "progress", indices = [Index("lastPlayedAt")])
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val driveFileId: String,
    val fileMd5: String?,
    val fileIndex: Int,
    val positionMs: Long,
    val bookPositionMs: Long,
    val status: ListeningStatus,
    val speed: Float?,
    val lastPlayedAt: Long,
    val updatedAt: Long,
    val dirty: Boolean
)

/** Bookmark; no foreign key to `books` for the same reason as [ProgressEntity]. [deleted] = tombstone. */
@Entity(tableName = "bookmarks", indices = [Index("bookId"), Index("dirty")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val fileMd5: String?,
    val positionMs: Long,
    val bookPositionMs: Long,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val dirty: Boolean
)

@Entity(
    tableName = "match_candidates",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class MatchCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val provider: String,
    val providerId: String,
    val title: String?,
    val author: String?,
    val narrator: String?,
    val series: String?,
    val seriesIndex: Double?,
    val year: Int?,
    val durationMs: Long?,
    val coverUrl: String?,
    val score: Double
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val driveFileId: String?,
    val remoteModifiedTime: Long?,
    val lastSyncedAt: Long
)
