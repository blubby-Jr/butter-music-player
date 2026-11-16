package com.example.aimpmobile

/** Опис одного параметра (слайдера), який можна змінювати вручну й автоматизувати. */
data class ParamDef(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val unit: String
)

object Params {
    /** Центральні частоти 18 смуг еквалайзера, Гц. */
    val EQ_FREQS = intArrayOf(
        55, 77, 110, 156, 220, 311, 440, 622, 880,
        1200, 1800, 2500, 3500, 5000, 7000, 10000, 14000, 20000
    )
    const val EQ_RANGE_DB = 12f

    val volume = ParamDef("volume", "Volume", 0f, 1f, 1f, "")

    val eq: List<ParamDef> = EQ_FREQS.mapIndexed { i, f ->
        val label = if (f >= 1000) ("${f / 1000.0}".removeSuffix(".0") + " kHz") else "$f Hz"
        ParamDef("eq.band.$i", label, -EQ_RANGE_DB, EQ_RANGE_DB, 0f, "dB")
    }

    private fun fx(name: String, label: String, min: Float, max: Float, def: Float, unit: String) =
        ParamDef("effect.$name", label, min, max, def, unit)

    /** Ефекти у тому порядку, як на макеті: 3 колонки по 3 слайдери. */
    val fxColumns: List<List<ParamDef>> = listOf(
        listOf(
            fx("echo", "Echo", 0f, 100f, 0f, "%"),
            fx("reverb", "Reverb", 0f, 100f, 0f, "%"),
            fx("flanger", "Flanger", 0f, 100f, 0f, "%")
        ),
        listOf(
            fx("chorus", "Chorus", 0f, 100f, 0f, "%"),
            fx("bass", "Bass", 0f, 100f, 0f, "%"),
            fx("stereo_width", "Stereo width", 0f, 100f, 0f, "%")
        ),
        listOf(
            fx("speed", "Speed", 0.5f, 2f, 1f, "×"),
            fx("tempo", "Tempo", 0.5f, 2f, 1f, "×"),
            fx("pitch_st", "Pitch", -12f, 12f, 0f, "st")
        )
    )
    val fx: List<ParamDef> = fxColumns.flatten()

    val all: List<ParamDef> = listOf(volume) + eq + fx
    val byId: Map<String, ParamDef> = all.associateBy { it.id }
}
