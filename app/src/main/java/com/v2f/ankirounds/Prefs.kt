package com.v2f.ankirounds

import android.content.Context
import android.content.SharedPreferences

/** การตั้งค่าทั้งหมดของแอป (เก็บในเครื่อง) */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("rounds", Context.MODE_PRIVATE)

    data class Deck(val name: String, val base: Int, val step: Int)

    fun deck(i: Int): Deck = Deck(
        p.getString("deck${i}_name", DEFAULT_NAMES[i]) ?: "",
        p.getInt("deck${i}_base", DEFAULT_BASE[i]),
        p.getInt("deck${i}_step", DEFAULT_STEP[i]),
    )

    fun setDeck(i: Int, d: Deck) {
        p.edit().putString("deck${i}_name", d.name.trim())
            .putInt("deck${i}_base", d.base).putInt("deck${i}_step", d.step).apply()
    }

    /** deck ที่ตั้งชื่อไว้ (deck ที่ใช้ preset ร่วมกัน ใส่แค่ deck เดียวก็พอ) */
    fun decks(): List<Deck> = (0 until MAX_DECKS).map { deck(it) }.filter { it.name.isNotBlank() }

    /** รอบปัจจุบัน (1 = ค่าเริ่มต้น) */
    var round: Int
        get() = p.getInt("round", 1)
        set(v) = p.edit().putInt("round", v.coerceAtLeast(1)).apply()

    fun valueFor(d: Deck, r: Int = round): Int = d.base + d.step * (r - 1)

    fun summary(r: Int = round): String = decks().joinToString(" / ") { valueFor(it, r).toString() }

    var bubble: Boolean
        get() = p.getBoolean("bubble", true)
        set(v) = p.edit().putBoolean("bubble", v).apply()

    /** ปุ่มลอยจางลงเองเมื่อไม่ได้ใช้ */
    var bubbleFade: Boolean
        get() = p.getBoolean("bubble_fade", true)
        set(v) = p.edit().putBoolean("bubble_fade", v).apply()

    /** แสดงปุ่มลอยเฉพาะตอนเปิด AnkiDroid */
    var onlyInAnki: Boolean
        get() = p.getBoolean("only_in_anki", true)
        set(v) = p.edit().putBoolean("only_in_anki", v).apply()

    var bubbleX: Int
        get() = p.getInt("bubble_x", 0)
        set(v) = p.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = p.getInt("bubble_y", 400)
        set(v) = p.edit().putInt("bubble_y", v).apply()

    /** ข้อความที่ใช้หาบนหน้าจอ AnkiDroid (คั่นหลายแบบด้วย | ) */
    var menuLabels: String
        get() = p.getString("menu_labels", DEFAULT_MENU) ?: DEFAULT_MENU
        set(v) = p.edit().putString("menu_labels", v).apply()

    var fieldLabels: String
        get() = p.getString("field_labels", DEFAULT_FIELD) ?: DEFAULT_FIELD
        set(v) = p.edit().putString("field_labels", v).apply()

    var saveLabels: String
        get() = p.getString("save_labels", DEFAULT_SAVE) ?: DEFAULT_SAVE
        set(v) = p.edit().putString("save_labels", v).apply()

    var lastLog: String
        get() = p.getString("last_log", "") ?: ""
        set(v) = p.edit().putString("last_log", v.takeLast(200_000)).apply()

    companion object {
        const val MAX_DECKS = 3
        val DEFAULT_NAMES = arrayOf("deck 1", "deck 2", "")
        val DEFAULT_BASE = intArrayOf(2, 1, 1)
        val DEFAULT_STEP = intArrayOf(2, 1, 1)
        const val DEFAULT_MENU = "Deck options|Options|ตัวเลือกสำรับ|ตัวเลือกเด็ค|ตัวเลือกของสำรับ|ตัวเลือก|ตั้งค่า"
        const val DEFAULT_FIELD = "Maximum reviews/day|Maximum reviews|จำนวนการทบทวนสูงสุด|ทบทวนสูงสุด|รีวิวสูงสุด"
        const val DEFAULT_SAVE = "Save|บันทึก"
        const val ANKI_PKG = "com.ichi2.anki"
    }
}
