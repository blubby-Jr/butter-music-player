package com.example.aimpmobile

import kotlin.math.max
import kotlin.math.min

/** Виділена ділянка треку (наприклад, дроп), мс від початку оригінального файлу. */
class Region(var start: Long, var end: Long) {
    val length: Long get() = end - start
}

/** Чиста логіка роботи з кількома ділянками: нормалізація, пошук, час відтворення. */
object Sel {
    /** Сортує та зливає ділянки, що перетинаються; відкидає надто короткі (< 100 мс). */
    fun normalize(list: List<Region>): List<Region> {
        val out = ArrayList<Region>()
        for (r in list.sortedBy { it.start }) {
            if (r.end - r.start < 100) continue
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end) last.end = max(last.end, r.end)
            else out.add(Region(r.start, r.end))
        }
        return out
    }

    /** Ділянки, що реально відтворюються. Якщо виділення вимкнене чи порожнє — весь файл. */
    fun active(enabled: Boolean, regions: List<Region>, durMs: Long): List<Region> {
        if (!enabled || regions.isEmpty()) return listOf(Region(0, max(1L, durMs)))
        return regions.sortedBy { it.start }.map { Region(it.start, if (durMs > 0) min(it.end, durMs) else it.end) }
    }

    fun regionAt(act: List<Region>, t: Long): Region? = act.firstOrNull { t >= it.start && t < it.end }
    fun nextAfter(act: List<Region>, t: Long): Region? = act.firstOrNull { it.start > t }

    fun total(act: List<Region>): Long = act.sumOf { it.length }

    /** Скільки мс виділення вже відіграно, якщо позиція в джерелі = pos. */
    fun elapsed(act: List<Region>, pos: Long): Long {
        var sum = 0L
        for (r in act) {
            if (pos >= r.end) sum += r.length
            else { if (pos > r.start) sum += pos - r.start; break }
        }
        return sum
    }

    /** Переводить «час у виділенні» назад у позицію в джерелі. */
    fun toSource(act: List<Region>, selMs: Long): Long {
        var left = max(0L, selMs)
        for (r in act) {
            if (left < r.length) return r.start + left
            left -= r.length
        }
        return act.lastOrNull()?.end ?: 0L
    }
}
