package com.example.aimpmobile

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

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
                    val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist"
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

class Playlist(val id: Long, var name: String, val tracks: MutableList<Long> = ArrayList())

/** Власні плейлисти (створення, перейменування, видалення). «Уся музика» — вбудований і тут не зберігається. */
class PlaylistStore(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("playlists_v2", Context.MODE_PRIVATE)
    val lists = ArrayList<Playlist>()

    init { load() }

    private fun load() {
        try {
            val arr = JSONArray(prefs.getString("data", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = ArrayList<Long>()
                val ta = o.optJSONArray("tracks")
                if (ta != null) for (k in 0 until ta.length()) ids.add(ta.getLong(k))
                lists.add(Playlist(o.getLong("id"), o.getString("name"), ids))
            }
        } catch (e: Exception) {
            lists.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (p in lists) {
            val ta = JSONArray()
            for (t in p.tracks) ta.put(t)
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", ta))
        }
        prefs.edit().putString("data", arr.toString()).apply()
    }

    fun get(id: Long): Playlist? = lists.firstOrNull { it.id == id }

    fun create(name: String): Playlist {
        val p = Playlist(System.currentTimeMillis(), name.trim().ifEmpty { "Playlist" })
        lists.add(p)
        save()
        return p
    }

    fun rename(id: Long, name: String) {
        get(id)?.name = name.trim().ifEmpty { "Playlist" }
        save()
    }

    fun delete(id: Long) {
        lists.removeAll { it.id == id }
        save()
    }

    fun add(id: Long, trackId: Long) {
        val p = get(id) ?: return
        if (!p.tracks.contains(trackId)) { p.tracks.add(trackId); save() }
    }

    fun addAll(id: Long, ids: List<Long>) {
        val p = get(id) ?: return
        for (t in ids) if (!p.tracks.contains(t)) p.tracks.add(t)
        save()
    }

    fun remove(id: Long, trackId: Long) {
        get(id)?.tracks?.remove(trackId)
        save()
    }
}
