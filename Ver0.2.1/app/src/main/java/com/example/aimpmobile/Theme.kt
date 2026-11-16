package com.example.aimpmobile

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Динамічна тема: світла, темна, AMOLED або власна (фон/текст/акцент). */
object Theme {
    var isLight = false
    var BG = Color.parseColor("#16181C")
    var PANEL = Color.parseColor("#1F2227")
    var PANEL2 = Color.parseColor("#2A2E35")
    var ACCENT = Color.parseColor("#FF9800")
    var TEXT = Color.parseColor("#E6E6E6")
    var DIM = Color.parseColor("#8A8F98")
    var VIEWBG = Color.parseColor("#0D0E10")

    fun apply(a: AppSettings) {
        when (a.theme) {
            "light" -> set(Color.parseColor("#F4F5F7"), Color.parseColor("#1B1D21"), Color.parseColor("#E65100"))
            "amoled" -> set(Color.BLACK, Color.parseColor("#EDEDED"), Color.parseColor("#FF9800"))
            "custom" -> set(a.customBg, a.customText, a.customAccent)
            else -> set(Color.parseColor("#16181C"), Color.parseColor("#E6E6E6"), Color.parseColor("#FF9800"))
        }
    }

    private fun set(bg: Int, text: Int, accent: Int) {
        BG = bg
        TEXT = text
        ACCENT = accent
        isLight = ColorUtils.calculateLuminance(bg) > 0.5
        PANEL = ColorUtils.blendARGB(bg, text, 0.07f)
        PANEL2 = ColorUtils.blendARGB(bg, text, 0.14f)
        DIM = ColorUtils.blendARGB(text, bg, 0.45f)
        VIEWBG = ColorUtils.blendARGB(bg, if (isLight) Color.WHITE else Color.BLACK, 0.5f)
    }
}
