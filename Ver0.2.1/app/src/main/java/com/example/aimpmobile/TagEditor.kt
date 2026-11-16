package com.example.aimpmobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Основні теги треку, які можна редагувати. */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val track: String = "",
    val genre: String = ""
)

class TagUnsupported(msg: String) : Exception(msg)

/**
 * Редактор тегів без зовнішніх бібліотек.
 *  • MP3  — ID3v2.3 / ID3v2.4 (інші кадри, зокрема обкладинка APIC, зберігаються без змін).
 *  • FLAC — Vorbis comment (решта блоків, зокрема PICTURE, зберігається).
 * Файл переписується через тимчасовий файл у кеші, потім копіюється назад.
 */
object TagEditor {
    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun supported(name: String) = ext(name) == "mp3" || ext(name) == "flac"
    private fun isFlac(name: String) = ext(name) == "flac"

    fun read(ctx: Context, uri: Uri, name: String): Tags {
        val raw = ctx.contentResolver.openInputStream(uri) ?: throw IOException("open")
        raw.use {
            val s = BufferedInputStream(it, 65536)
            return if (isFlac(name)) {
                val blocks = readFlac(s)
                val vc = blocks.firstOrNull { b -> b.first == 4 }?.let { b -> parseVc(b.second) }
                if (vc == null) Tags() else flacTags(vc)
            } else {
                val id3 = readId3(s)
                if (id3 == null) Tags() else id3Tags(id3)
            }
        }
    }

    /** Записує лише змінені поля (orig → new). */
    fun write(ctx: Context, uri: Uri, name: String, orig: Tags, new: Tags) {
        val tmp = File.createTempFile("tag", ".tmp", ctx.cacheDir)
        try {
            val raw = ctx.contentResolver.openInputStream(uri) ?: throw IOException("open")
            raw.use {
                val s = BufferedInputStream(it, 65536)
                FileOutputStream(tmp).use { out ->
                    if (isFlac(name)) writeFlac(s, out, orig, new) else writeMp3(s, out, orig, new)
                    s.copyTo(out, 65536)
                }
            }
            val o = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("open for write")
            o.use { FileInputStream(tmp).use { fi -> fi.copyTo(o, 65536) } }
        } finally {
            tmp.delete()
        }
    }

    /** Одразу оновлюємо MediaStore, щоб список треків показав нові теги без очікування ре-скану. */
    fun updateMediaStore(ctx: Context, uri: Uri, t: Tags) {
        try {
            val v = ContentValues()
            if (t.title.isNotBlank()) v.put(MediaStore.Audio.Media.TITLE, t.title)
            if (t.artist.isNotBlank()) v.put(MediaStore.Audio.Media.ARTIST, t.artist)
            if (t.album.isNotBlank()) v.put(MediaStore.Audio.Media.ALBUM, t.album)
            t.year.take(4).toIntOrNull()?.let { v.put(MediaStore.Audio.Media.YEAR, it) }
            t.track.substringBefore('/').trim().toIntOrNull()?.let { v.put(MediaStore.Audio.Media.TRACK, it) }
            if (v.size() > 0) ctx.contentResolver.update(uri, v, null, null)
        } catch (e: Exception) {
            // не критично: MediaStore оновиться при наступному скані
        }
    }

    // ------------------------------------------------------------------ допоміжне

    private fun readFully(s: InputStream, b: ByteArray): Int {
        var o = 0
        while (o < b.size) {
            val r = s.read(b, o, b.size - o)
            if (r < 0) break
            o += r
        }
        return o
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun syncsafeBytes(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
    )

    private fun be32Bytes(n: Int) = byteArrayOf(
        ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
    )

    private fun le32Bytes(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte()
    )

    // ------------------------------------------------------------------ ID3v2 (MP3)

    private class Frame(val id: String, val raw: ByteArray)
    private class Id3(val major: Int, val frames: List<Frame>)

    /** Читає ID3v2 з початку потоку. Якщо тегу немає — потік лишається на початку, повертає null. */
    private fun readId3(s: BufferedInputStream): Id3? {
        s.mark(16)
        val h = ByteArray(10)
        val n = readFully(s, h)
        if (n < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) {
            s.reset()
            return null
        }
        val major = h[3].toInt()
        val flags = h[5].toInt() and 0xFF
        val size = syncsafe(h, 6)
        if (major != 3 && major != 4) throw TagUnsupported("ID3v2.$major")
        if (flags and 0x80 != 0) throw TagUnsupported("unsynchronisation")
        val body = ByteArray(size)
        if (readFully(s, body) < size) throw IOException("short tag")
        if (major == 4 && flags and 0x10 != 0) readFully(s, ByteArray(10)) // footer
        var pos = 0
        if (flags and 0x40 != 0 && body.size >= 4) pos = if (major == 4) syncsafe(body, 0) else be32(body, 0) + 4
        val frames = ArrayList<Frame>()
        while (pos + 10 <= body.size) {
            if (body[pos].toInt() == 0) break
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            val fs = if (major == 4) syncsafe(body, pos + 4) else be32(body, pos + 4)
            if (fs < 0 || pos + 10 + fs > body.size) break
            frames.add(Frame(id, body.copyOfRange(pos, pos + 10 + fs)))
            pos += 10 + fs
        }
        return Id3(major, frames)
    }

    private fun frameText(f: Frame?): String {
        if (f == null || f.raw.size <= 11 || f.raw[9].toInt() != 0) return ""
        val data = f.raw.copyOfRange(10, f.raw.size)
        val cs = when (data[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(data, 1, data.size - 1, cs).replace("\uFEFF", "")
            .split('\u0000').filter { it.isNotEmpty() }.joinToString(" / ")
    }

    private fun id3Tags(t: Id3): Tags {
        fun get(vararg ids: String): String {
            for (id in ids) {
                val v = frameText(t.frames.firstOrNull { it.id == id })
                if (v.isNotEmpty()) return v
            }
            return ""
        }
        return Tags(get("TIT2"), get("TPE1"), get("TALB"), get("TYER", "TDRC"), get("TRCK"), get("TCON"))
    }

    private fun textFrame(id: String, text: String, major: Int): Frame {
        val ascii = text.all { it.code < 128 }
        val enc: Int
        val bytes: ByteArray
        when {
            ascii -> { enc = 0; bytes = text.toByteArray(Charsets.ISO_8859_1) }
            major >= 4 -> { enc = 3; bytes = text.toByteArray(Charsets.UTF_8) }
            else -> { enc = 1; bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE) }
        }
        val data = byteArrayOf(enc.toByte()) + bytes
        val size = if (major >= 4) syncsafeBytes(data.size) else be32Bytes(data.size)
        return Frame(id, id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + data)
    }

    private fun writeMp3(s: BufferedInputStream, out: OutputStream, orig: Tags, new: Tags) {
        val old = readId3(s)
        val major = old?.major ?: 3
        val frames = ArrayList<Frame>(old?.frames ?: emptyList())
        fun change(ids: List<String>, newId: String, text: String) {
            frames.removeAll { it.id in ids }
            if (text.isNotBlank()) frames.add(textFrame(newId, text.trim(), major))
        }
        if (new.title != orig.title) change(listOf("TIT2"), "TIT2", new.title)
        if (new.artist != orig.artist) change(listOf("TPE1"), "TPE1", new.artist)
        if (new.album != orig.album) change(listOf("TALB"), "TALB", new.album)
        if (new.year != orig.year) change(listOf("TYER", "TDRC"), if (major >= 4) "TDRC" else "TYER", new.year)
        if (new.track != orig.track) change(listOf("TRCK"), "TRCK", new.track)
        if (new.genre != orig.genre) change(listOf("TCON"), "TCON", new.genre)
        if (frames.isEmpty()) return
        var total = 0
        for (f in frames) total += f.raw.size
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0))
        out.write(syncsafeBytes(total))
        for (f in frames) out.write(f.raw)
    }

    // ------------------------------------------------------------------ FLAC (Vorbis comment)

    private class Vc(val vendor: ByteArray, val entries: MutableList<Pair<String, String>>)

    /** Читає всі метаблоки FLAC (type, data); потік лишається на початку аудіокадрів. */
    private fun readFlac(s: InputStream): List<Pair<Int, ByteArray>> {
        val m = ByteArray(4)
        if (readFully(s, m) < 4 || String(m, Charsets.ISO_8859_1) != "fLaC") throw TagUnsupported("not a plain FLAC")
        val out = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            val h = ByteArray(4)
            if (readFully(s, h) < 4) throw IOException("short flac")
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            val data = ByteArray(len)
            if (readFully(s, data) < len) throw IOException("short flac block")
            out.add(type to data)
            if (last) break
        }
        return out
    }

    private fun parseVc(b: ByteArray): Vc {
        try {
            var p = 0
            val vl = le32(b, p); p += 4
            val vendor = b.copyOfRange(p, p + vl); p += vl
            val count = le32(b, p); p += 4
            val list = ArrayList<Pair<String, String>>()
            repeat(count) {
                val l = le32(b, p); p += 4
                val str = String(b, p, l, Charsets.UTF_8); p += l
                val i = str.indexOf('=')
                if (i > 0) list.add(str.substring(0, i) to str.substring(i + 1))
            }
            return Vc(vendor, list)
        } catch (e: Exception) {
            throw IOException("bad vorbis comment")
        }
    }

    private fun flacTags(vc: Vc): Tags {
        fun get(vararg keys: String): String {
            for (k in keys) {
                val v = vc.entries.firstOrNull { it.first.equals(k, true) }?.second
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }
        return Tags(get("TITLE"), get("ARTIST"), get("ALBUM"), get("DATE", "YEAR"), get("TRACKNUMBER"), get("GENRE"))
    }

    private fun writeFlac(s: BufferedInputStream, out: OutputStream, orig: Tags, new: Tags) {
        val blocks = readFlac(s)
        val vc = blocks.firstOrNull { it.first == 4 }?.let { parseVc(it.second) }
            ?: Vc("AimpMobile".toByteArray(Charsets.UTF_8), ArrayList())
        fun change(keys: List<String>, newKey: String, text: String) {
            vc.entries.removeAll { e -> keys.any { it.equals(e.first, true) } }
            if (text.isNotBlank()) vc.entries.add(newKey to text.trim())
        }
        if (new.title != orig.title) change(listOf("TITLE"), "TITLE", new.title)
        if (new.artist != orig.artist) change(listOf("ARTIST"), "ARTIST", new.artist)
        if (new.album != orig.album) change(listOf("ALBUM"), "ALBUM", new.album)
        if (new.year != orig.year) change(listOf("DATE", "YEAR"), "DATE", new.year)
        if (new.track != orig.track) change(listOf("TRACKNUMBER"), "TRACKNUMBER", new.track)
        if (new.genre != orig.genre) change(listOf("GENRE"), "GENRE", new.genre)

        val body = java.io.ByteArrayOutputStream()
        body.write(le32Bytes(vc.vendor.size)); body.write(vc.vendor)
        body.write(le32Bytes(vc.entries.size))
        for ((k, v) in vc.entries) {
            val e = "$k=$v".toByteArray(Charsets.UTF_8)
            body.write(le32Bytes(e.size)); body.write(e)
        }
        val others = blocks.filter { it.first != 4 && it.first != 1 } // без старого коментаря й padding
        val result = ArrayList<Pair<Int, ByteArray>>()
        if (others.isNotEmpty()) result.add(others[0]) // STREAMINFO завжди перший
        result.add(4 to body.toByteArray())
        for (i in 1 until others.size) result.add(others[i])

        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        for ((i, b) in result.withIndex()) {
            val last = i == result.size - 1
            val len = b.second.size
            out.write(((if (last) 0x80 else 0) or b.first).toByte().toInt())
            out.write((len shr 16) and 0xFF); out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
            out.write(b.second)
        }
    }
}
