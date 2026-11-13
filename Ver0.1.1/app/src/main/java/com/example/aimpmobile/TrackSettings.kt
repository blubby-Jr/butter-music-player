package com.example.aimpmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class Marker(var t: Long, var label: String)

/** Усі індивідуальні налаштування одного треку (зберігаються в track_name.json). */
class TrackSettings {
    /** true = грати лише виділені ділянки (дропи); false = увесь трек. */
    @Volatile var selectionOnly = false
    /** Виділені ділянки (одна або кілька). Список замінюється атомарно. */
    @Volatile var regions: List<Region> = emptyList()
    @Volatile var eqBypass = false

    fun active(durMs: Long): List<Region> = Sel.active(selectionOnly, regions, durMs)

    /** Ручні значення параметрів (id -> значення). Відсутній id = значення за замовчуванням. */
    val values = ConcurrentHashMap<String, Float>()
    /** Доріжки автоматизації (id -> відсортовані вузли). Непорожня доріжка перекриває ручне значення. */
    val lanes = ConcurrentHashMap<String, CopyOnWriteArrayList<AutoPoint>>()
    val markers = CopyOnWriteArrayList<Marker>()

    fun lane(id: String): CopyOnWriteArrayList<AutoPoint> = lanes.getOrPut(id) { CopyOnWriteArrayList() }

    fun toJson(t: Track): JSONObject {
        val o = JSONObject()
        o.put("schema_version", 1)
        o.put(
            "track", JSONObject()
                .put("file_name", t.displayName)
                .put("fingerprint", TrackStore.fingerprint(t))
                .put("duration_ms", t.durationMs)
        )
        val regArr = JSONArray()
        for (r in regions) regArr.put(JSONObject().put("start_ms", r.start).put("end_ms", r.end))
        o.put("selection", JSONObject().put("enabled", selectionOnly).put("regions", regArr))
        val bands = JSONArray()
        for (p in Params.eq) bands.put((values[p.id] ?: p.def).toDouble())
        o.put("eq", JSONObject().put("bypass", eqBypass).put("bands_db", bands))
        val fx = JSONObject()
        for (p in Params.fx) fx.put(p.id.removePrefix("effect."), (values[p.id] ?: p.def).toDouble())
        o.put("effects", fx)
        o.put("volume", (values["volume"] ?: 1f).toDouble())
        val auto = JSONObject()
        for ((id, lane) in lanes) {
            if (lane.isEmpty()) continue
            val arr = JSONArray()
            for (pt in lane) arr.put(JSONObject().put("t", pt.t).put("v", pt.v.toDouble()))
            auto.put(id, arr)
        }
        o.put("automation", auto)
        val mk = JSONArray()
        for (m in markers) mk.put(JSONObject().put("t", m.t).put("label", m.label))
        o.put("markers", mk)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TrackSettings {
            val s = TrackSettings()
            o.optJSONObject("trim")?.let {
                // старий формат v0.1: одна ділянка
                val st = it.optLong("start_ms", 0L)
                val en = it.optLong("end_ms", -1L)
                s.selectionOnly = it.optBoolean("enabled", false)
                if (en > st) s.regions = listOf(Region(st, en))
            }
            o.optJSONObject("selection")?.let { sel ->
                s.selectionOnly = sel.optBoolean("enabled", false)
                val arr = sel.optJSONArray("regions")
                val list = ArrayList<Region>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    list.add(Region(r.optLong("start_ms", 0L), r.optLong("end_ms", 0L)))
                }
                s.regions = Sel.normalize(list)
            }
            o.optJSONObject("eq")?.let { eq ->
                s.eqBypass = eq.optBoolean("bypass", false)
                val arr = eq.optJSONArray("bands_db")
                if (arr != null) for (i in 0 until minOf(arr.length(), 18)) s.values["eq.band.$i"] = arr.optDouble(i, 0.0).toFloat()
            }
            o.optJSONObject("effects")?.let { fx ->
                for (p in Params.fx) {
                    val k = p.id.removePrefix("effect.")
                    if (fx.has(k)) s.values[p.id] = fx.optDouble(k, p.def.toDouble()).toFloat()
                }
            }
            if (o.has("volume")) s.values["volume"] = o.optDouble("volume", 1.0).toFloat()
            o.optJSONObject("automation")?.let { auto ->
                val keys = auto.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val arr = auto.optJSONArray(id) ?: continue
                    val lane = s.lane(id)
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        lane.add(AutoPoint(p.optLong("t", 0L), p.optDouble("v", 0.0).toFloat()))
                    }
                    lane.sortBy { it.t }
                }
            }
            o.optJSONArray("markers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    s.markers.add(Marker(m.optLong("t", 0L), m.optString("label", "Мітка")))
                }
            }
            return s
        }
    }
}

object TrackStore {
    fun fingerprint(t: Track): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${t.displayName}|${t.size}".toByteArray())
        return d.take(6).joinToString("") { "%02x".format(it) }
    }

    /** <каталог додатка>/track_settings/<назва_треку>__<fingerprint>.json */
    fun file(ctx: Context, t: Track): File {
        val dir = File(ctx.filesDir, "track_settings")
        dir.mkdirs()
        val safe = t.baseName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
        return File(dir, safe + "__" + fingerprint(t) + ".json")
    }

    fun load(ctx: Context, t: Track): TrackSettings {
        val f = file(ctx, t)
        if (!f.exists()) return TrackSettings()
        return try {
            TrackSettings.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            TrackSettings() // пошкоджений JSON не має ламати програму
        }
    }

    /** Атомарний запис: тимчасовий файл → rename. Сам аудіофайл ніколи не змінюється. */
    fun save(ctx: Context, t: Track, s: TrackSettings) {
        try {
            val f = file(ctx, t)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(s.toJson(t).toString(2))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // ігноруємо: налаштування не критичні
        }
    }
}
