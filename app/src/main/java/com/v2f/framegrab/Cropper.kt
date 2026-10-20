package com.v2f.framegrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

/**
 * เลือกส่วนของจอที่จะเก็บ
 *  0 = ทั้งจอ
 *  1 = ตัดขอบอัตโนมัติ (แถบสถานะ, แถบนำทาง, ขอบดำรอบวิดีโอ)
 *  2 = กรอบที่ผู้ใช้ตั้งเอง (เก็บเป็นสัดส่วน 0–1 ของจอ)
 * ปุ่ม/ตัวหนังสือที่แอปวาดทับบนวิดีโอตัดไม่ได้ เพราะอยู่ในภาพเดียวกัน
 */
object Cropper {
    const val FULL = 0
    const val AUTO = 1
    const val CUSTOM = 2

    fun modeLabel(m: Int): String = when (m) {
        AUTO -> "ตัดขอบอัตโนมัติ"
        CUSTOM -> "กรอบที่ตั้งเอง"
        else -> "ทั้งจอ"
    }

    /** ภาพหน้าจอเต็มล่าสุดที่ใช้ตั้งกรอบ */
    fun refFile(c: Context) = File(c.filesDir, "crop_ref.jpg")

    private fun realScreenWidth(c: Context): Int {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") c.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        return m.widthPixels
    }

    private fun sysDimen(c: Context, name: String): Int {
        val id = c.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) c.resources.getDimensionPixelSize(id) else 0
    }

    fun rectFor(c: Context, bmp: Bitmap): Rect {
        val w = bmp.width; val h = bmp.height
        return when (Prefs.cropMode(c)) {
            AUTO -> autoRect(c, bmp)
            CUSTOM -> {
                val r = Prefs.cropRect(c)
                val out = Rect(
                    (r[0] * w).toInt().coerceIn(0, w - 1), (r[1] * h).toInt().coerceIn(0, h - 1),
                    (r[2] * w).toInt().coerceIn(1, w), (r[3] * h).toInt().coerceIn(1, h)
                )
                if (out.width() < 32 || out.height() < 32) Rect(0, 0, w, h) else out
            }
            else -> Rect(0, 0, w, h)
        }
    }

    /** ตัดตามกรอบ (คืนภาพเดิมถ้าเป็นทั้งจอ) */
    fun apply(bmp: Bitmap, r: Rect): Bitmap {
        if (r.left <= 0 && r.top <= 0 && r.right >= bmp.width && r.bottom >= bmp.height) return bmp
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val rw = r.width().coerceIn(1, bmp.width - l)
        val rh = r.height().coerceIn(1, bmp.height - t)
        return Bitmap.createBitmap(bmp, l, t, rw, rh)
    }

    /** หาส่วนวิดีโออัตโนมัติ: ตัดแถบระบบ แล้วตัดแถว/คอลัมน์ที่ดำสนิททั้งแถวออก */
    fun autoRect(c: Context, bmp: Bitmap): Rect {
        val w = bmp.width; val h = bmp.height
        val scale = w.toFloat() / realScreenWidth(c).coerceAtLeast(1)
        var top = (sysDimen(c, "status_bar_height") * scale).toInt()
        var bottom = h - (sysDimen(c, "navigation_bar_height") * scale).toInt()
        var left = 0
        var right = w
        val maxV = (h * 0.35f).toInt()
        val maxH = (w * 0.35f).toInt()
        val startTop = top; val startBottom = bottom
        while (top < startTop + maxV && top < bottom - 32 && darkRow(bmp, top, left, right)) top += 2
        while (bottom > startBottom - maxV && bottom > top + 32 && darkRow(bmp, bottom - 1, left, right)) bottom -= 2
        while (left < maxH && left < right - 32 && darkCol(bmp, left, top, bottom)) left += 2
        while (right > w - maxH && right > left + 32 && darkCol(bmp, right - 1, top, bottom)) right -= 2
        return Rect(left, top.coerceAtLeast(0), right, bottom.coerceAtMost(h))
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private fun darkRow(b: Bitmap, y: Int, x0: Int, x1: Int): Boolean {
        if (y < 0 || y >= b.height) return false
        val n = 48
        for (i in 0 until n) {
            val x = (x0 + (x1 - x0 - 1) * i / (n - 1)).coerceIn(0, b.width - 1)
            if (lum(b.getPixel(x, y)) > 22) return false
        }
        return true
    }

    private fun darkCol(b: Bitmap, x: Int, y0: Int, y1: Int): Boolean {
        if (x < 0 || x >= b.width) return false
        val n = 64
        for (i in 0 until n) {
            val y = (y0 + (y1 - y0 - 1) * i / (n - 1)).coerceIn(0, b.height - 1)
            if (lum(b.getPixel(x, y)) > 22) return false
        }
        return true
    }
}
