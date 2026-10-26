package com.v2f.ankirounds

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject

/**
 * อ่านรายชื่อ deck จาก AnkiDroid ผ่าน API ทางการ (อ่านอย่างเดียว)
 * ต้องได้สิทธิ์ com.ichi2.anki.permission.READ_WRITE_DATABASE จากผู้ใช้
 */
object AnkiApi {
    const val PERM = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private val DECKS: Uri = Uri.parse("content://com.ichi2.anki.flashcards/decks")

    data class DeckInfo(
        val id: Long,
        val name: String,          // ชื่อเต็ม เช่น "ไทย::deck 1"
        val presetId: Long,
        val presetName: String,
        val reviewsPerDay: Int,
        val filtered: Boolean,
        val due: Int,              // การ์ดที่ยังท่องได้วันนี้ (learn + review + new) ตามเพดานปัจจุบัน
    ) {
        val shortName: String get() = name.substringAfterLast("::")
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    /** คืนรายชื่อ deck ทั้งหมด หรือ throw พร้อมข้อความภาษาไทยถ้าอ่านไม่ได้ */
    fun decks(ctx: Context): List<DeckInfo> {
        val out = ArrayList<DeckInfo>()
        val c = try {
            ctx.contentResolver.query(DECKS, null, null, null, null)
        } catch (e: SecurityException) {
            throw IllegalStateException("AnkiDroid ไม่อนุญาตให้อ่านรายชื่อ deck (ให้สิทธิ์ \"AnkiDroid database\" ก่อน)")
        } ?: throw IllegalStateException("อ่านรายชื่อ deck ไม่ได้ (ลองเปิด AnkiDroid ครั้งหนึ่งแล้วลองใหม่)")
        c.use {
            val iName = it.getColumnIndex("deck_name")
            val iId = it.getColumnIndex("deck_id")
            val iOpt = it.getColumnIndex("options")
            val iDyn = it.getColumnIndex("deck_dyn")
            val iCnt = it.getColumnIndex("deck_count")
            while (it.moveToNext()) {
                val name = if (iName >= 0) it.getString(iName) ?: "" else ""
                var pid = -1L; var pname = ""; var rev = -1
                if (iOpt >= 0) {
                    try {
                        val o = JSONObject(it.getString(iOpt) ?: "{}")
                        pid = o.optLong("id", -1)
                        pname = o.optString("name", "")
                        rev = o.optJSONObject("rev")?.optInt("perDay", -1) ?: -1
                    } catch (_: Exception) {
                    }
                }
                val dyn = iDyn >= 0 && (it.getInt(iDyn) != 0)
                var due = 0
                if (iCnt >= 0) {
                    try {
                        val a = org.json.JSONArray(it.getString(iCnt) ?: "[]")
                        for (k in 0 until a.length()) due += a.optInt(k, 0)
                    } catch (_: Exception) {
                    }
                }
                out.add(DeckInfo(if (iId >= 0) it.getLong(iId) else -1, name, pid, pname, rev, dyn, due))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** deck ที่เลือกอยู่ใน AnkiDroid (deck ที่เพิ่งท่อง / เปิดหน้า Congratulations อยู่) */
    fun selectedDeckName(ctx: Context): String? = try {
        ctx.contentResolver.query(Uri.parse("content://com.ichi2.anki.flashcards/selected_deck"),
            null, null, null, null)?.use { c ->
            val i = c.getColumnIndex("deck_name")
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (_: Exception) { null }

    /** หา deck จากชื่อ (ชื่อเต็มหรือชื่อสั้นก็ได้) */
    fun find(list: List<DeckInfo>, name: String): DeckInfo? {
        val n = name.trim()
        return list.firstOrNull { it.name.equals(n, true) }
            ?: list.firstOrNull { it.shortName.equals(n.substringAfterLast("::"), true) }
    }
}
