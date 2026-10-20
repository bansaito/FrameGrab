package com.v2f.framegrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

/**
 * เลือกส่วนของจอที่จะเก็บ: ทั้งจอ / อัตโนมัติ (ตัดแถบระบบ+ขอบดำ) / สัดส่วนคงที่ (9:16, 3:4 …
 * วางกลางพื้นที่วิดีโอ) / กรอบที่ผู้ใช้ลากเอง (เก็บเป็นสัดส่วน 0–1 ของจอ)
 * ปุ่ม/ตัวหนังสือที่แอปวาดทับบนวิดีโอตัดไม่ได้ เพราะอยู่ในภาพเดียวกัน
 */
object Cropper {
    class Preset(val id: String, val label: String, val rw: Int, val rh: Int)

    val PRESETS = listOf(
        Preset("full", "ทั้งจอ", 0, 0),
        Preset("auto", "อัตโนมัติ (ตัดขอบ)", 0, 0),
        Preset("9:16", "9:16", 9, 16),
        Preset("3:4", "3:4", 3, 4),
        Preset("1:1", "1:1", 1, 1),
        Preset("4:3", "4:3", 4, 3),
        Preset("16:9", "16:9", 16, 9),
        Preset("2:3", "2:3", 2, 3),
        Preset("3:2", "3:2", 3, 2),
        Preset("custom", "กำหนดเอง", 0, 0),
    )

    fun presetLabel(id: String): String = PRESETS.firstOrNull { it.id == id }?.label ?: id

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
        val id = Prefs.cropPreset(c)
        val p = PRESETS.firstOrNull { it.id == id }
        return when {
            id == "auto" -> autoRect(c, bmp)
            p != null && p.rw > 0 -> {
                // วิดีโอสัดส่วนใกล้กรอบมาก (≤3%) → ชิดขอบวิดีโอพอดี ไม่ต้องบีบให้เป๊ะจนเหลือช่องว่างข้าง ๆ
                val base = autoRect(c, bmp)
                val target = p.rw / p.rh.toFloat()
                val br = base.width() / base.height().coerceAtLeast(1).toFloat()
                if (kotlin.math.abs(br - target) / target <= 0.03f) base else fitRatio(base, p.rw, p.rh)
            }
            id == "custom" -> {
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

    private val COMMON = listOf(
        "9:20" to 9 / 20.0, "9:19.5" to 9 / 19.5, "9:16" to 9 / 16.0, "2:3" to 2 / 3.0, "3:4" to 3 / 4.0,
        "4:5" to 4 / 5.0, "1:1" to 1.0, "5:4" to 5 / 4.0, "4:3" to 4 / 3.0, "3:2" to 3 / 2.0,
        "16:9" to 16 / 9.0, "2:1" to 2.0, "21:9" to 21 / 9.0,
    )

    /** ชื่อสัดส่วนที่ใกล้ที่สุด เช่น "16:9" (ถ้าไม่ใกล้อันไหนเลย บอกเป็นตัวเลข) */
    fun ratioName(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "?"
        val r = w / h.toDouble()
        val best = COMMON.minByOrNull { kotlin.math.abs(it.second - r) / it.second }!!
        return if (kotlin.math.abs(best.second - r) / best.second < 0.04) best.first
        else if (r < 1) String.format(java.util.Locale.US, "1:%.2f", 1 / r)
        else String.format(java.util.Locale.US, "%.2f:1", r)
    }

    /** ชื่อสัดส่วนมาตรฐานที่ใกล้ที่สุด: ตรง (±1.5%) = "3:4", ไม่ตรง = "≈3:4" */
    fun ratioTag(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "?"
        val r = w / h.toDouble()
        val best = COMMON.minByOrNull { kotlin.math.abs(it.second - r) / it.second }!!
        return if (kotlin.math.abs(best.second - r) / best.second < 0.015) best.first else "≈" + best.first
    }

    /** ชื่อกรอบสำหรับแสดง: กรอบที่ลากเอง แสดงเป็นสัดส่วน (เช่น 3:4) แทน "กำหนดเอง" */
    fun presetLabel(c: Context, id: String): String {
        if (id != "custom" || !Prefs.hasCustomCrop(c)) return presetLabel(id)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") c.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        val f = Prefs.cropRect(c)
        return ratioTag(((f[2] - f[0]) * m.widthPixels).toInt(), ((f[3] - f[1]) * m.heightPixels).toInt())
    }

    /** สัดส่วนของวิดีโอที่กำลังเล่น วัดจากขอบดำรอบวิดีโอ */
    fun detectVideoRatio(c: Context, bmp: Bitmap): String {
        val r = autoRect(c, bmp)
        val scale = bmp.width.toFloat() / realScreenWidth(c).coerceAtLeast(1)
        val usableH = bmp.height - ((sysDimen(c, "status_bar_height") + sysDimen(c, "navigation_bar_height")) * scale).toInt()
        val fills = r.width() >= bmp.width - 4 && r.height() >= usableH - 4
        return ratioName(r.width(), r.height()) + if (fills) " (เต็มจอ)" else ""
    }

    /** กรอบสัดส่วน rw:rh ที่ใหญ่ที่สุดซึ่งอยู่กลางพื้นที่ base */
    fun fitRatio(base: Rect, rw: Int, rh: Int): Rect {
        val bw = base.width(); val bh = base.height()
        return if (bw.toLong() * rh > bh.toLong() * rw) {
            val w = (bh.toLong() * rw / rh).toInt()
            val x = base.left + (bw - w) / 2
            Rect(x, base.top, x + w, base.bottom)
        } else {
            val h = (bw.toLong() * rh / rw).toInt()
            val y = base.top + (bh - h) / 2
            Rect(base.left, y, base.right, y + h)
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
        // เริ่มจากขอบจอเลย: แถบสถานะ/แถบนำทาง/แถบพิมพ์ข้อความของแอป จะถูกตัดออกด้วยกฎแถวมืด
        var top = 0
        var bottom = h
        var left = 0
        var right = w
        val maxV = (h * 0.45f).toInt()
        val maxH = (w * 0.35f).toInt()
        val skip = (w * 0.066f).toInt().coerceAtLeast(24)
        top = scan(top, maxV, 2, skip) { darkRow(bmp, it, left, right) }
        bottom = scan(bottom, maxOf(h - maxV, top + 32), -2, skip) { darkRow(bmp, it - 1, left, right) }
        left = scan(left, minOf(maxH, right - 32), 2, skip) { darkCol(bmp, it, top, bottom) }
        right = scan(right, maxOf(w - maxH, left + 32), -2, skip) { darkCol(bmp, it - 1, top, bottom) }
        return Rect(left, top.coerceAtLeast(0), right, bottom.coerceAtMost(h))
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /**
     * เดินจาก start ไปทาง limit ตราบที่ยังเป็นขอบดำ
     * ข้ามเส้นบาง ๆ ได้ (เส้นกรอบ/ตัวหนังสือแถบสถานะ/ปุ่มลอยของเรา) ถ้าถัดไปยังดำอยู่
     */
    private inline fun scan(start: Int, limit: Int, step: Int, skip: Int, isDark: (Int) -> Boolean): Int {
        var p = start
        val before = { q: Int -> if (step > 0) q < limit else q > limit }
        while (before(p)) {
            if (isDark(p)) { p += step; continue }
            var k = 2
            var thin = false
            while (k <= skip) {
                val q = p + if (step > 0) k else -k
                if (before(q) && isDark(q)) { thin = true; break }
                k += 2
            }
            if (thin) p += if (step > 0) k else -k else break
        }
        return p
    }

    /**
     * แถวนี้อยู่นอกวิดีโอไหม:
     *  - ดำเกือบทั้งแถว (≥82% เผื่อปุ่มลอยบัง) หรือ
     *  - เป็นแถบ UI ของแอป (เช่นช่อง "ข้อความ…" + อีโมจิใต้สตอรี่ IG/FB):
     *    ขอบซ้าย/ขวาดำสนิท และส่วนใหญ่ของแถวเป็นสีมืด
     */
    private fun darkRow(b: Bitmap, y: Int, x0: Int, x1: Int): Boolean {
        if (y < 0 || y >= b.height) return false
        val n = 48
        var dark = 0
        var darkish = 0
        for (i in 0 until n) {
            val x = (x0 + (x1 - x0 - 1) * i / (n - 1)).coerceIn(0, b.width - 1)
            val l = lum(b.getPixel(x, y))
            if (l <= 22) dark++
            if (l <= 60) darkish++
        }
        if (dark >= n * 0.82) return true
        val w = b.width
        fun edge(fr: FloatArray) = fr.count { f -> lum(b.getPixel((w * f).toInt().coerceIn(0, w - 1), y)) <= 22 } >= 2
        val l = edge(floatArrayOf(0.012f, 0.017f, 0.022f))
        val r = edge(floatArrayOf(0.978f, 0.983f, 0.988f))
        return (l || r) && darkish >= n * 0.6 && ((l && r) || darkish >= n * 0.75)
    }

    private fun darkCol(b: Bitmap, x: Int, y0: Int, y1: Int): Boolean {
        if (x < 0 || x >= b.width) return false
        val n = 64
        var dark = 0
        for (i in 0 until n) {
            val y = (y0 + (y1 - y0 - 1) * i / (n - 1)).coerceIn(0, b.height - 1)
            if (lum(b.getPixel(x, y)) <= 22) dark++
        }
        return dark >= n * 0.82
    }
}
