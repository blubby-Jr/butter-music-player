package com.example.aimpmobile

/** Вузол автоматизації: час у мс від початку ОРИГІНАЛЬНОГО файлу + значення параметра. */
class AutoPoint(@Volatile var t: Long, @Volatile var v: Float)

object Automation {
    /** Лінійна інтерполяція між вузлами. Повертає null, якщо вузлів немає. Список має бути відсортований за t. */
    fun valueAt(points: List<AutoPoint>, t: Long): Float? {
        val a = points.toTypedArray()
        if (a.isEmpty()) return null
        if (t <= a[0].t) return a[0].v
        val last = a[a.size - 1]
        if (t >= last.t) return last.v
        for (i in 0 until a.size - 1) {
            val p = a[i]
            val q = a[i + 1]
            if (t >= p.t && t <= q.t) {
                val span = (q.t - p.t).toFloat()
                if (span <= 0f) return q.v
                return p.v + (q.v - p.v) * ((t - p.t) / span)
            }
        }
        return last.v
    }
}
