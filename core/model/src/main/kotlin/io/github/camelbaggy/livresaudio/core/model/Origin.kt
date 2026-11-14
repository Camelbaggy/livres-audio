package io.github.camelbaggy.livresaudio.core.model

/** Role of a connected Google account (CLAUDE.md §4.1). */
enum class AccountRole { SHARED, PERSONAL }

/** Origin of a book, deduced from the account it lives in (CLAUDE.md §4.1). */
enum class BookOrigin {
    SHARED,
    PERSONAL
    ;

    companion object {
        fun from(role: AccountRole): BookOrigin = when (role) {
            AccountRole.SHARED -> SHARED
            AccountRole.PERSONAL -> PERSONAL
        }
    }
}
