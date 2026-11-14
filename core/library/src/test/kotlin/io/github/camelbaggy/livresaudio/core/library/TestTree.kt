package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.DriveItem

/** Tiny DSL building a flat Drive listing with generated ids. All names used in tests are invented. */
class TestTree {
    val items = mutableListOf<DriveItem>()
    private var counter = 0

    val rootId = "root"

    fun folder(name: String, parent: String = rootId, block: (String) -> Unit = {}): String {
        val id = "d${++counter}"
        items += DriveItem(id, name, DriveItem.FOLDER_MIME_TYPE, parent)
        block(id)
        return id
    }

    fun file(name: String, parent: String = rootId, size: Long = 1000, mime: String? = null): String {
        val id = "f${++counter}"
        val m = mime ?: when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4b", "m4a" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt" -> "text/plain"
            "gsheet" -> "application/vnd.google-apps.spreadsheet"
            else -> "application/octet-stream"
        }
        items += DriveItem(id, name, m, parent, sizeBytes = size)
        return id
    }

    fun files(parent: String, vararg names: String) = names.forEach { file(it, parent) }
}
