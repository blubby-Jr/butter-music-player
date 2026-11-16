package com.example.aimpmobile

import kotlin.math.min

data class Wanted(val title: String, val artist: String)

/**
 * Розбір списків треків, експортованих з Spotify (наприклад, CSV з Exportify), SoundCloud, YouTube Music (Google Takeout)
 * та простих текстових списків «Виконавець - Назва». Тут лише імпорт СПИСКУ та зіставлення з локальною бібліотекою.
 */
object ImportParser {
    private val titleKeys = listOf("track name", "trackname", "title", "track", "song", "song title", "name")
    private val artistKeys = listOf("artist name(s)", "artist name", "artistname", "artist", "artists", "author", "channel")

    fun parse(text: String): List<Wanted> {
        val t = text.removePrefix("\uFEFF").trim()
        if (t.isEmpty()) return emptyList()
        val lines = t.lines().filter { it.isNotBlank() }
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> lines.take(5).sumOf { l -> l.count { it == d } } } ?: ','
        val looksTabular = lines.size > 1 && lines.take(5).all { l -> l.count { it == delim } >= 1 }
        return if (looksTabular) parseCsv(t, delim) else parseLines(lines)
    }

    private fun parseLines(lines: List<String>): List<Wanted> = lines.mapNotNull { raw ->
        val l = raw.trim()
        if (l.startsWith("#") || l.startsWith("http")) return@mapNotNull null
        val i = l.indexOf(" - ")
        if (i > 0) Wanted(l.substring(i + 3).trim(), l.substring(0, i).trim()) else Wanted(l, "")
    }

    fun splitCsv(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else q = false
                } else cur.append(c)
            } else when (c) {
                '"' -> q = true
                delim -> { row.add(cur.toString()); cur.setLength(0) }
                '\n' -> { row.add(cur.toString()); cur.setLength(0); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
        return rows
    }

    private fun parseCsv(text: String, delim: Char): List<Wanted> {
        val rows = splitCsv(text, delim)
        if (rows.isEmpty()) return emptyList()
        val head = rows[0].map { it.trim().lowercase() }
        var ti = titleKeys.map { k -> head.indexOf(k) }.firstOrNull { it >= 0 } ?: -1
        var ai = artistKeys.map { k -> head.indexOf(k) }.firstOrNull { it >= 0 } ?: -1
        val hasHeader = ti >= 0
        if (!hasHeader) { ti = 0; ai = if (rows[0].size > 1) 1 else -1 }
        val out = ArrayList<Wanted>()
        for (r in rows.drop(if (hasHeader) 1 else 0)) {
            val title = r.getOrNull(ti)?.trim().orEmpty()
            if (title.isEmpty()) continue
            val artist = if (ai >= 0) r.getOrNull(ai)?.trim().orEmpty().split(';', ',').first().trim() else ""
            if (!hasHeader && artist.isEmpty() && title.contains(" - ")) {
                val i = title.indexOf(" - ")
                out.add(Wanted(title.substring(i + 3).trim(), title.substring(0, i).trim()))
            } else out.add(Wanted(title, artist))
        }
        return out
    }

    fun norm(s: String): String {
        var x = s.lowercase()
        x = x.replace(Regex("\\((feat|ft|with)[^)]*\\)|\\[(feat|ft|with)[^\\]]*\\]"), " ")
        x = x.replace(Regex("[-–]\\s*(remaster(ed)?|single version|radio edit|\\d{4} remaster).*"), " ")
        val sb = StringBuilder()
        for (ch in x) if (Character.isLetterOrDigit(ch)) sb.append(ch)
        return sb.toString()
    }

    /** Повертає індекс найкращого кандидата (title, artist) або -1. */
    fun bestMatch(w: Wanted, cands: List<Pair<String, String>>): Int {
        val t = norm(w.title)
        val a = norm(w.artist)
        if (t.isEmpty()) return -1
        var best = -1
        var bestScore = 0
        for ((i, c) in cands.withIndex()) {
            val ct = norm(c.first)
            val ca = norm(c.second)
            var s = 0
            if (ct == t) s += 60
            else if (min(ct.length, t.length) >= 4 && (ct.contains(t) || t.contains(ct))) s += 35
            else continue
            if (a.isNotEmpty() && ca.isNotEmpty()) {
                if (ca == a) s += 40 else if (ca.contains(a) || a.contains(ca)) s += 25 else s -= 20
            }
            if (s > bestScore) { bestScore = s; best = i }
        }
        return if (bestScore >= 55) best else -1
    }
}
