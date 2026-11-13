package com.example.aimpmobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** Файл, який користувач вибрав через системний picker (окремі файли або папка). */
data class Picked(val name: String, val size: Long, val mediaId: Long?)

/**
 * Перетворення вибраних файлів/папки на треки бібліотеки (MediaStore).
 * Плейлисти зберігають id треків з MediaStore, тому вибраний файл шукаємо в бібліотеці:
 * спершу за id (якщо picker віддав media-URI), потім за ім'ям файлу + розміром.
 */
object PickImport {
    private val AUDIO_EXT = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "mka", "amr", "mid", "midi", "3gp"
    )

    private fun isAudio(name: String, mime: String?): Boolean =
        (mime != null && (mime.startsWith("audio/") || mime == "application/ogg")) ||
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    /** Один або кілька файлів (ACTION_OPEN_DOCUMENT). */
    fun fromFiles(ctx: Context, uris: List<Uri>): List<Picked> {
        val out = ArrayList<Picked>()
        for (u in uris) {
            var name = ""
            var size = -1L
            try {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0).orEmpty()
                        if (!it.isNull(1)) size = it.getLong(1)
                    }
                }
            } catch (e: Exception) {
                continue
            }
            out.add(Picked(name, size, mediaIdOf(u)))
        }
        return out
    }

    /** Уся папка з підпапками (ACTION_OPEN_DOCUMENT_TREE). */
    fun fromTree(ctx: Context, tree: Uri): List<Picked> {
        val out = ArrayList<Picked>()
        try {
            walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), out, 0)
        } catch (e: Exception) {
            // немає доступу до папки — повертаємо те, що встигли зібрати
        }
        return out
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, out: MutableList<Picked>, depth: Int) {
        if (depth > 32) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        class Entry(val id: String, val name: String, val mime: String?, val size: Long)
        val entries = ArrayList<Entry>()
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                entries.add(Entry(c.getString(0), c.getString(1).orEmpty(), c.getString(2), if (c.isNull(3)) -1L else c.getLong(3)))
            }
        }
        entries.sortBy { it.name.lowercase() }
        for (e in entries) {
            if (e.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(ctx, tree, e.id, out, depth + 1)
            } else if (isAudio(e.name, e.mime)) {
                out.add(Picked(e.name, e.size, null))
            }
        }
    }

    /** media-URI вигляду content://com.android.providers.media.documents/document/audio:123 → 123. */
    private fun mediaIdOf(u: Uri): Long? {
        if (u.authority != "com.android.providers.media.documents") return null
        val id = try { DocumentsContract.getDocumentId(u) } catch (e: Exception) { return null }
        if (!id.startsWith("audio:")) return null
        return id.removePrefix("audio:").toLongOrNull()
    }

    /** Повертає id знайдених треків бібліотеки (без повторів, у порядку вибору) і кількість не знайдених. */
    fun match(picked: List<Picked>, lib: List<Track>): Pair<List<Long>, Int> {
        val libIds = lib.mapTo(HashSet()) { it.id }
        val byNameSize = lib.groupBy { it.displayName.lowercase() to it.size }
        val byName = lib.groupBy { it.displayName.lowercase() }
        val found = LinkedHashSet<Long>()
        var missing = 0
        for (p in picked) {
            val id: Long? = when {
                p.mediaId != null && p.mediaId in libIds -> p.mediaId
                else -> {
                    val key = p.name.lowercase()
                    byNameSize[key to p.size]?.firstOrNull()?.id
                        ?: byName[key]?.takeIf { it.size == 1 }?.first()?.id
                }
            }
            if (id != null) found.add(id) else missing++
        }
        return found.toList() to missing
    }
}
