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

    fun bubbleX(c: Context): Int = sp(c).getInt("bx", -1)
    fun bubbleY(c: Context): Int = sp(c).getInt("by", -1)
    fun setBubble(c: Context, x: Int, y: Int) = sp(c).edit().putInt("bx", x).putInt("by", y).apply()

    fun intervalLabel(v: Float): String = if (v == 1f) "1" else v.toString()
    fun maxLabel(v: Int): String = if (v == 0) "ไม่จำกัด" else "$v วิ"
}
