package com.example.aimpmobile

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val size: Long,
    val displayName: String
) {
    val baseName: String get() = displayName.substringBeforeLast('.')
}

object Library {
    /**
     * Скан музики через MediaStore. Назви/виконавці/альбоми MediaStore бере з тегів
     * (ID3v1/ID3v2, Vorbis, MP4) самого файлу.
     */
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(6) ?: "track_$id"
                    val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "Невідомий виконавець"
                    out.add(
                        Track(
                            id,
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: name.substringBeforeLast('.'),
                            artist,
                            c.getString(3) ?: "",
                            c.getLong(4),
                            c.getLong(5),
                            name
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // немає дозволу — повертаємо порожній список
        }
        return out
    }

    /** Вбудована обкладинка (ID3 APIC / MP4 covr / FLAC picture). */
    fun cover(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val b = r.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(b, 0, b.size, opts)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }
}

/** Плейлист 1 = уся музика пристрою; плейлисти 2 і 3 — користувацькі (довге натискання на трек). */
class Playlists(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    fun ids(n: Int): MutableList<Long> =
        (prefs.getString("pl$n", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toMutableList()

    private fun put(n: Int, list: List<Long>) {
        prefs.edit().putString("pl$n", list.joinToString(",")).apply()
    }

    fun add(n: Int, id: Long) {
        val l = ids(n)
        if (!l.contains(id)) { l.add(id); put(n, l) }
    }

    fun remove(n: Int, id: Long) {
        val l = ids(n)
        l.remove(id)
        put(n, l)
    }
}
