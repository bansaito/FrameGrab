package com.v2f.framegrab

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("framegrab", Context.MODE_PRIVATE)

    val INTERVALS = floatArrayOf(0.25f, 0.5f, 1f)
    val MAX_SECONDS = intArrayOf(15, 30, 60, 0) // 0 = ไม่จำกัด (สูงสุด 5 นาที)

    fun interval(c: Context): Float = sp(c).getFloat("interval", 0.25f)
    fun setInterval(c: Context, v: Float) = sp(c).edit().putFloat("interval", v).apply()

    fun maxSeconds(c: Context): Int = sp(c).getInt("maxSec", 30)
    fun setMaxSeconds(c: Context, v: Int) = sp(c).edit().putInt("maxSec", v).apply()

    /** แสดงปุ่มหยุดระหว่างอัด (ใช้เมื่ออนุญาตแบบ "แอปเดียว" ปุ่มจะไม่ติดในภาพ) */
    fun keepStopButton(c: Context): Boolean = sp(c).getBoolean("keepStop2", true)
    fun setKeepStopButton(c: Context, v: Boolean) = sp(c).edit().putBoolean("keepStop2", v).apply()

    /** หยุดอัดเองเมื่อคลิปวนกลับมาต้น */
    fun autoStop(c: Context): Boolean = sp(c).getBoolean("autoStop", true)
    fun setAutoStop(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoStop", v).apply()
    /** ใส่ชื่อบัญชีจากหน้าจอในชื่อไฟล์ (ปิดได้ถ้าได้ชื่อผิด) */
    fun nameFromScreen(c: Context): Boolean = sp(c).getBoolean("nameFromScreen", true)
    fun setNameFromScreen(c: Context, v: Boolean) = sp(c).edit().putBoolean("nameFromScreen", v).apply()
    /** ⟲ บันทึกย้อนหลังกี่วินาที */
    val REPLAY_SECS = intArrayOf(15, 30, 60, 120)
    fun replaySec(c: Context): Int = sp(c).getInt("replaySec", 30)
    fun setReplaySec(c: Context, v: Int) = sp(c).edit().putInt("replaySec", v).apply()
    /** 🎥 หยุดอัดวิดีโอเองเมื่อภาพนิ่ง 10 วิ (ไลฟ์จบ) */
    fun videoAutoStop(c: Context): Boolean = sp(c).getBoolean("videoAutoStop", true)
    fun setVideoAutoStop(c: Context, v: Boolean) = sp(c).edit().putBoolean("videoAutoStop", v).apply()
    /** เมนู "คัดลอกข้อความบนจอ" (ปิดเป็นค่าเริ่มต้น) */
    fun copyText(c: Context): Boolean = sp(c).getBoolean("copyText", false)
    fun setCopyText(c: Context, v: Boolean) = sp(c).edit().putBoolean("copyText", v).apply()
    /** ป้าย ✓ ภาพที่บันทึกแล้ว + กันบันทึกซ้ำ (เปิดเป็นค่าเริ่มต้น) */
    fun saveMarks(c: Context): Boolean = sp(c).getBoolean("saveMarks", true)
    fun setSaveMarks(c: Context, v: Boolean) = sp(c).edit().putBoolean("saveMarks", v).apply()
    /** สั่นต่างกันตามผลการบันทึก (ปิดเป็นค่าเริ่มต้น) */
    fun saveHaptic(c: Context): Boolean = sp(c).getBoolean("saveHaptic", false)
    fun setSaveHaptic(c: Context, v: Boolean) = sp(c).edit().putBoolean("saveHaptic", v).apply()
    /** แอนิเมชันตอนบันทึก (ปิดเป็นค่าเริ่มต้น) */
    fun saveAnim(c: Context): Boolean = sp(c).getBoolean("saveAnim", false)
    fun setSaveAnim(c: Context, v: Boolean) = sp(c).edit().putBoolean("saveAnim", v).apply()
    /** ข้ามภาพที่ซ้ำกับภาพก่อนหน้า (วิดีโอค้าง/รูปนิ่ง) */
    fun skipDuplicates(c: Context): Boolean = sp(c).getBoolean("skipDup", true)
    fun setSkipDuplicates(c: Context, v: Boolean) = sp(c).edit().putBoolean("skipDup", v).apply()
    /** กดค้างหน้าจอให้เองตอนจับภาพ/อัด (ซ่อนชื่อโปรไฟล์ของสตอรี่) */
    fun holdShot(c: Context): Boolean = sp(c).getBoolean("holdShot", false)
    fun setHoldShot(c: Context, v: Boolean) = sp(c).edit().putBoolean("holdShot", v).apply()
    fun holdRec(c: Context): Boolean = sp(c).getBoolean("holdRec", false)
    fun setHoldRec(c: Context, v: Boolean) = sp(c).edit().putBoolean("holdRec", v).apply()
    /** หยุดอัดเองเมื่อขึ้นสตอรี่ใหม่ */
    fun stopOnNewStory(c: Context): Boolean = sp(c).getBoolean("stopNewStory", true)
    fun setStopOnNewStory(c: Context, v: Boolean) = sp(c).edit().putBoolean("stopNewStory", v).apply()

    /** ส่วนที่จะเก็บ: 0 ทั้งจอ, 1 ตัดขอบอัตโนมัติ, 2 กรอบที่ตั้งเอง */
    fun cropMode(c: Context): Int = sp(c).getInt("cropMode", 0)
    fun setCropMode(c: Context, v: Int) = sp(c).edit().putInt("cropMode", v).apply()
    /** กรอบที่เลือก: "full", "auto", "custom" หรือสัดส่วนเช่น "9:16" */
    fun cropPreset(c: Context): String = sp(c).getString("cropPreset", null) ?: when (cropMode(c)) {
        1 -> "auto"; 2 -> "custom"; else -> "full"
    }
    fun setCropPreset(c: Context, v: String) = sp(c).edit().putString("cropPreset", v).apply()

    /** v3.3: เวอร์ชันก่อนลากกรอบแล้วกลายเป็น "custom" ถาวร (กรอบหยุดตามวิดีโอ) → คืนเป็นอัตโนมัติครั้งเดียว */
    fun migrateCustomOnce(c: Context) {
        val p = sp(c)
        if (p.getBoolean("mig33", false)) return
        p.edit().putBoolean("mig33", true).apply {
            if (p.getString("cropPreset", null) == "custom") putString("cropPreset", "auto")
        }.apply()
    }

    /** แสดงกรอบบนจอ (ซ่อนเองตอนจับภาพ จึงไม่ติดในภาพ) */
    fun showGuide(c: Context): Boolean = sp(c).getBoolean("showGuide", true)
    fun setShowGuide(c: Context, v: Boolean) = sp(c).edit().putBoolean("showGuide", v).apply()

    /** 📸 จับภาพนี้ แล้วบันทึกลงแกลเลอรีให้เลย */
    fun autoSaveSingle(c: Context): Boolean = sp(c).getBoolean("autoSaveSingle", true)
    fun setAutoSaveSingle(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoSaveSingle", v).apply()

    /** แสดงปุ่มลอยเฉพาะใน TikTok / Instagram / Facebook */
    fun onlyInVideoApps(c: Context): Boolean = sp(c).getBoolean("onlyInApps", true)
    fun setOnlyInVideoApps(c: Context, v: Boolean) = sp(c).edit().putBoolean("onlyInApps", v).apply()

    /** ภาพที่ดูค้างไว้ในหน้าดูภาพ (ชื่อไฟล์) */
    fun viewerLast(c: Context): String? = sp(c).getString("viewerLast", null)
    fun setViewerLast(c: Context, v: String?) = sp(c).edit().putString("viewerLast", v).apply()

    /** แสดงปุ่มลอยและกรอบเฉพาะตอนดูสตอรี่ (TikTok / IG / FB) */
    fun storyOnly(c: Context): Boolean = sp(c).getBoolean("storyOnly", true)
    fun setStoryOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("storyOnly", v).apply()

    fun hasCustomCrop(c: Context): Boolean = sp(c).contains("cropL")
    fun cropRect(c: Context): FloatArray = sp(c).let {
        floatArrayOf(it.getFloat("cropL", 0f), it.getFloat("cropT", 0f), it.getFloat("cropR", 1f), it.getFloat("cropB", 1f))
    }
    fun setCropRect(c: Context, l: Float, t: Float, r: Float, b: Float) =
        sp(c).edit().putFloat("cropL", l).putFloat("cropT", t).putFloat("cropR", r).putFloat("cropB", b).apply()

    fun bubbleX(c: Context): Int = sp(c).getInt("bx", -1)
    fun bubbleY(c: Context): Int = sp(c).getInt("by", -1)
    fun setBubble(c: Context, x: Int, y: Int) = sp(c).edit().putInt("bx", x).putInt("by", y).apply()

    fun intervalLabel(v: Float): String = if (v == 1f) "1" else v.toString()
    fun maxLabel(v: Int): String = if (v == 0) "ไม่จำกัด" else "$v วิ"
}
