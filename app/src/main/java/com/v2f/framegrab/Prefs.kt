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
    fun keepStopButton(c: Context): Boolean = sp(c).getBoolean("keepStop", false)
    fun setKeepStopButton(c: Context, v: Boolean) = sp(c).edit().putBoolean("keepStop", v).apply()

    /** หยุดอัดเองเมื่อคลิปวนกลับมาต้น */
    fun autoStop(c: Context): Boolean = sp(c).getBoolean("autoStop", true)
    fun setAutoStop(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoStop", v).apply()

    /** ส่วนที่จะเก็บ: 0 ทั้งจอ, 1 ตัดขอบอัตโนมัติ, 2 กรอบที่ตั้งเอง */
    fun cropMode(c: Context): Int = sp(c).getInt("cropMode", 0)
    fun setCropMode(c: Context, v: Int) = sp(c).edit().putInt("cropMode", v).apply()
    /** กรอบที่เลือก: "full", "auto", "custom" หรือสัดส่วนเช่น "9:16" */
    fun cropPreset(c: Context): String = sp(c).getString("cropPreset", null) ?: when (cropMode(c)) {
        1 -> "auto"; 2 -> "custom"; else -> "full"
    }
    fun setCropPreset(c: Context, v: String) = sp(c).edit().putString("cropPreset", v).apply()

    /** แสดงกรอบบนจอ (ซ่อนเองตอนจับภาพ จึงไม่ติดในภาพ) */
    fun showGuide(c: Context): Boolean = sp(c).getBoolean("showGuide", true)
    fun setShowGuide(c: Context, v: Boolean) = sp(c).edit().putBoolean("showGuide", v).apply()

    /** 📸 จับภาพนี้ แล้วบันทึกลงแกลเลอรีให้เลย */
    fun autoSaveSingle(c: Context): Boolean = sp(c).getBoolean("autoSaveSingle", true)
    fun setAutoSaveSingle(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoSaveSingle", v).apply()

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
