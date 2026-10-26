package com.v2f.ankirounds

import android.content.Context
import android.content.SharedPreferences

/**
 * การตั้งค่าทั้งหมดของแอป (เก็บในเครื่อง)
 * รองรับหลาย "ชุด" — ชุดแรก (g = 0) ใช้ key เดิมของเวอร์ชันก่อน จึงไม่ต้องตั้งค่าใหม่
 */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("rounds", Context.MODE_PRIVATE)

    data class Deck(val name: String, val base: Int, val step: Int)

    private fun pre(g: Int) = if (g == 0) "" else "g${g}_"

    // ------------------------------------------------------------------ groups (ชุด)

    var groupCount: Int
        get() = p.getInt("group_count", 1).coerceAtLeast(1)
        set(v) = p.edit().putInt("group_count", v.coerceAtLeast(1)).apply()

    /** ชุดที่ใช้ล่าสุด (ใช้กับปุ่มในแผงควบคุม) */
    var lastGroup: Int
        get() = p.getInt("last_group", 0).coerceIn(0, groupCount - 1)
        set(v) = p.edit().putInt("last_group", v).apply()

    fun groupName(g: Int): String =
        p.getString("${pre(g)}group_name", null)?.takeIf { it.isNotBlank() } ?: "ชุด ${g + 1}"

    fun setGroupName(g: Int, n: String) = p.edit().putString("${pre(g)}group_name", n.trim()).apply()

    fun addGroup(): Int {
        val g = groupCount
        val e = p.edit()
        for (i in 0 until MAX_DECKS) {
            e.putString("${pre(g)}deck${i}_name", "")
            e.putInt("${pre(g)}deck${i}_base", DEFAULT_BASE[i])
            e.putInt("${pre(g)}deck${i}_step", DEFAULT_STEP[i])
        }
        e.putInt("${pre(g)}round", 1).putString("${pre(g)}group_name", "ชุด ${g + 1}").apply()
        groupCount = g + 1
        return g
    }

    /** ลบชุด g (ชุดหลังจากนั้นเลื่อนขึ้นมาแทน) */
    fun removeGroup(g: Int) {
        val n = groupCount
        if (n <= 1) return
        for (k in g until n - 1) copyGroup(k + 1, k)
        groupCount = n - 1
        if (lastGroup >= n - 1) lastGroup = 0
    }

    private fun copyGroup(from: Int, to: Int) {
        for (i in 0 until MAX_DECKS) setDeck(to, i, deck(from, i))
        setRound(to, round(from))
        setGroupName(to, groupName(from))
    }

    // ------------------------------------------------------------------ decks in a group

    fun deck(g: Int, i: Int): Deck = Deck(
        p.getString("${pre(g)}deck${i}_name", if (g == 0) DEFAULT_NAMES[i] else "") ?: "",
        p.getInt("${pre(g)}deck${i}_base", DEFAULT_BASE[i]),
        p.getInt("${pre(g)}deck${i}_step", DEFAULT_STEP[i]),
    )

    fun setDeck(g: Int, i: Int, d: Deck) {
        p.edit().putString("${pre(g)}deck${i}_name", d.name.trim())
            .putInt("${pre(g)}deck${i}_base", d.base).putInt("${pre(g)}deck${i}_step", d.step).apply()
    }

    /** deck ที่ตั้งชื่อไว้ พร้อมช่องที่อยู่ */
    fun deckSlots(g: Int): List<Pair<Int, Deck>> =
        (0 until MAX_DECKS).map { it to deck(g, it) }.filter { it.second.name.isNotBlank() }

    /** deck ที่ตั้งชื่อไว้ในชุดนี้ */
    fun decks(g: Int): List<Deck> = (0 until MAX_DECKS).map { deck(g, it) }.filter { it.name.isNotBlank() }

    /** รอบปัจจุบันของชุด (1 = ค่าเริ่มต้น) */
    fun round(g: Int): Int = p.getInt("${pre(g)}round", 1)

    fun setRound(g: Int, v: Int) = p.edit().putInt("${pre(g)}round", v.coerceAtLeast(1)).apply()

    fun valueFor(d: Deck, r: Int): Int = d.base + d.step * (r - 1)

    fun summary(g: Int, r: Int = round(g)): String =
        decks(g).joinToString(" / ") { valueFor(it, r).toString() }

    // ------------------------------------------------------------------ bubble

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

    /** แสดงปุ่มเฉพาะหน้าแรก (รายการ deck) และหน้า Congratulations · ซ่อนตอนรีวิวการ์ด */
    var onlyHome: Boolean
        get() = p.getBoolean("only_home", true)
        set(v) = p.edit().putBoolean("only_home", v).apply()

    var bubbleX: Int
        get() = p.getInt("bubble_x", 0)
        set(v) = p.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = p.getInt("bubble_y", 400)
        set(v) = p.edit().putInt("bubble_y", v).apply()

    /** 0 = ปกติ, 1 = เร็ว, 2 = เร็วสุด */
    var speed: Int
        get() = p.getInt("speed", 1)
        set(v) = p.edit().putInt("speed", v.coerceIn(0, 2)).apply()

    val speedFactor: Float get() = floatArrayOf(1f, 0.6f, 0.35f)[speed]

    // ------------------------------------------------------------------ feature switches (เปิดไว้ก่อน ปิดได้)

    /** ช่องค้นหาในหน้าเลือก deck */
    var featSearch: Boolean
        get() = p.getBoolean("feat_search", true)
        set(v) = p.edit().putBoolean("feat_search", v).apply()

    /** หัวเมนูบอกสถานะ (ตอนนี้ … · เหลือ … ใบ) */
    var featStatus: Boolean
        get() = p.getBoolean("feat_status", true)
        set(v) = p.edit().putBoolean("feat_status", v).apply()

    /** หลายชุดในเมนู */
    var featGroups: Boolean
        get() = p.getBoolean("feat_groups", true)
        set(v) = p.edit().putBoolean("feat_groups", v).apply()

    /** ปุ่มลอยถูกเก็บไว้ในแจ้งเตือน */
    var parked: Boolean
        get() = p.getBoolean("parked", false)
        set(v) = p.edit().putBoolean("parked", v).apply()

    /** ลากไปทิ้งที่ขอบล่าง → เก็บในแจ้งเตือน (เปิดไว้) */
    var featTrash: Boolean
        get() = p.getBoolean("feat_trash", true)
        set(v) = p.edit().putBoolean("feat_trash", v).apply()

    /** ปุ่มชิดขอบจอ ซ่อนครึ่งหนึ่ง (ปิดไว้) */
    var featEdge: Boolean
        get() = p.getBoolean("feat_edge", false)
        set(v) = p.edit().putBoolean("feat_edge", v).apply()

    /** ซ่อนปุ่มตอนเลื่อนรายการใน AnkiDroid (ปิดไว้) */
    var featScrollHide: Boolean
        get() = p.getBoolean("feat_scroll_hide", false)
        set(v) = p.edit().putBoolean("feat_scroll_hide", v).apply()

    /** (ย่อยของหลายชุด) บอกการ์ดที่เหลือของแต่ละชุดในเมนู — ปิดไว้ */
    var featGroupRemaining: Boolean
        get() = p.getBoolean("feat_group_remaining", false)
        set(v) = p.edit().putBoolean("feat_group_remaining", v).apply()

    /** ปุ่ม ↶ ย้อน 1 รอบ */
    var featUndo: Boolean
        get() = p.getBoolean("feat_undo", true)
        set(v) = p.edit().putBoolean("feat_undo", v).apply()

    /** ชุดที่แสดงในเมนู (ปิดหลายชุด = เฉพาะชุดแรก) */
    fun visibleGroups(): List<Int> =
        if (featGroups) (0 until groupCount).filter { decks(it).isNotEmpty() } else listOf(0)

    // ------------------------------------------------------------------ screen labels

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
