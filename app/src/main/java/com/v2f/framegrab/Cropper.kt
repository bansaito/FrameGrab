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
                val tol = if (isPrimary(p.id)) 0.06f else 0.03f
                if (kotlin.math.abs(br - target) / target <= tol) base else fitRatio(base, p.rw, p.rh)
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
    /** สัดส่วนที่เจอบ่อยในสตอรี่/คลิป: จับแบบเผื่อมากกว่า (±6%) */
    val PRIMARY = listOf("9:16" to 9 / 16.0, "3:4" to 3 / 4.0)

    private fun primaryOf(r: Double): String? =
        PRIMARY.firstOrNull { kotlin.math.abs(it.second - r) / it.second <= 0.06 }?.first

    fun isPrimary(id: String) = PRIMARY.any { it.first == id }

    /** สัดส่วนตามจริง เช่น "9:15.7" หรือ "16.4:9" */
    fun precise(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "?"
        fun f(v: Double) = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
        return if (w <= h) "9:" + f(9.0 * h / w) else f(9.0 * w / h) + ":9"
    }

    /**
     * ชื่อสัดส่วน: ใกล้มาตรฐาน (±4%) = "9:16", ไกลกว่านั้น = "≈9:16"
     * (การวัดจากจอคลาดได้ไม่กี่พิกเซล จึงไม่แสดงทศนิยมแปลก ๆ อย่าง 9:15.7)
     */
    fun ratioName(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "?"
        val r = w / h.toDouble()
        val best = COMMON.minByOrNull { kotlin.math.abs(it.second - r) / it.second }!!
        return if (kotlin.math.abs(best.second - r) / best.second <= 0.04) best.first else "≈" + best.first
    }

    fun ratioTag(w: Int, h: Int): String = ratioName(w, h)

    /** ชื่อกรอบสำหรับแสดง: กรอบที่ลากเอง แสดงเป็นสัดส่วน (เช่น 3:4) แทน "กำหนดเอง" */
    fun presetLabel(c: Context, id: String): String {
        if (id != "custom" || !Prefs.hasCustomCrop(c)) return presetLabel(id)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") c.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        val f = Prefs.cropRect(c)
        return ratioTag(((f[2] - f[0]) * m.widthPixels).toInt(), ((f[3] - f[1]) * m.heightPixels).toInt())
    }

    /**
     * หน้าฟีด TikTok (สำหรับคุณ / กำลังติดตาม): มีปุ่ม + สีเทาอ่อน ขอบฟ้า/ชมพู กลางแถบเมนูล่าง
     * (ทดสอบกับภาพจริง: หน้าฟีด 2/2 ใช่, สตอรี่/หน้าโฮม/หน้าดูภาพ 7/7 ไม่ใช่)
     */
    fun isTikTokFeed(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        var grey = 0; var cyan = 0; var pink = 0; var n = 0
        val stepY = (h / 700).coerceAtLeast(1) * 3
        val stepX = (w / 360).coerceAtLeast(1) * 3
        var y = (h * 0.88f).toInt()
        while (y < (h * 0.995f).toInt()) {
            var x = (w * 0.38f).toInt()
            while (x < (w * 0.62f).toInt()) {
                val p = b.getPixel(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                n++
                if (r > 170 && g > 170 && bl > 170 && maxOf(r, g, bl) - minOf(r, g, bl) < 30) grey++
                else if (r < 90 && g > 120 && bl > 130 && kotlin.math.abs(g - bl) < 50) cyan++
                else if (r > 160 && g < 130 && bl in 81..179 && r - g > 60) pink++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return false
        return grey >= n * 0.08 && cyan >= n * 0.004 && pink >= n * 0.004
    }

    /**
     * แถบเมนูล่างของหน้าฟีด TikTok (หน้าหลัก · ร้านค้า · + · กล่องข้อความ · โปรไฟล์) บนพื้นดำ
     * = หน้า "สำหรับคุณ"/"กำลังติดตาม" (สตอรี่ไม่มีแถบนี้)
     * ตรวจ: ไอคอน/ตัวหนังสือสว่างที่ตำแหน่ง 10%, 30% (ร้านค้า), 50% (+) และช่องว่างดำสนิทที่ 20%, 40%
     * (ไอคอนอาจถูกหรี่ จึงนับสว่าง >70; ทดสอบ: ฟีด 4/4 ใช่ (≥0.088), สตอรี่/อื่นๆ 15/15 ไม่ใช่ (≤0.052))
     */
    fun hasFeedNav(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        val band = (h * 0.05f).toInt().coerceAtLeast(4)
        val sy = (h / 400).coerceAtLeast(1); val sx = (w / 180).coerceAtLeast(1)
        fun darkFrac(c: Float, y0: Int): Float {
            var d = 0; var n = 0
            val x0 = (w * (c - 0.015f)).toInt(); val x1 = (w * (c + 0.015f)).toInt()
            val stx = (sx / 2).coerceAtLeast(1)
            var y = y0
            while (y < y0 + band) { var x = x0; while (x <= x1) { n++; if (lum(b.getPixel(x, y)) < 30) d++; x += stx }; y += sy }
            return if (n == 0) 0f else d / n.toFloat()
        }
        fun brightFrac(c: Float, y0: Int): Float {
            var d = 0; var n = 0
            val x0 = (w * (c - 0.07f)).toInt().coerceAtLeast(0); val x1 = (w * (c + 0.07f)).toInt().coerceAtMost(w - 1)
            var y = y0
            while (y < y0 + band) { var x = x0; while (x < x1) { n++; if (lum(b.getPixel(x, y)) > 70) d++; x += sx }; y += sy }
            return if (n == 0) 0f else d / n.toFloat()
        }
        var y0 = (h * 0.80f).toInt()
        while (y0 < h - band) {
            if (darkFrac(0.20f, y0) >= 0.9f && darkFrac(0.40f, y0) >= 0.9f &&
                brightFrac(0.30f, y0) >= 0.07f && brightFrac(0.10f, y0) >= 0.07f && brightFrac(0.50f, y0) >= 0.07f) return true
            y0 += sy
        }
        return false
    }

    /**
     * หน้าสตอรี่ (TikTok / IG / FB): มีช่องพิมพ์ "ข้อความ…" สีเทาเข้มยาวด้านล่าง บนพื้นดำ
     * (ทดสอบกับภาพจริง: สตอรี่ 7/7 ใช่, หน้าฟีด/กำลังติดตาม/หน้าโฮม/หน้าดูภาพ 5/5 ไม่ใช่)
     */
    fun isStory(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        var best = 0f
        var y = (h * 0.80f).toInt()
        val step = (h / 1000).coerceAtLeast(1) * 2
        while (y < (h * 0.995f).toInt()) {
            if (lum(b.getPixel((w * 0.01f).toInt(), y)) <= 25) {
                var pill = 0
                for (i in 0 until 40) {
                    val p = b.getPixel((w * (0.08f + 0.50f * i / 39f)).toInt().coerceIn(0, w - 1), y)
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                    val l = lum(p)
                    if (l in 30..80 && maxOf(r, g, bl) - minOf(r, g, bl) < 18) pill++
                }
                best = maxOf(best, pill / 40f)
                if (best >= 0.75f) return true
            }
            y += step
        }
        return false
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
        // แถบสถานะ/แถบนำทางของระบบ (บางแอปเช่น Facebook ทำแถบนำทางเป็นสีขาว) = นับเป็นขอบ ถ้าสีเรียบทั้งแถว
        val scale = w.toFloat() / realScreenWidth(c).coerceAtLeast(1)
        val dens = c.resources.displayMetrics.density
        val navH = ((sysDimen(c, "navigation_bar_height").takeIf { it > 0 } ?: (48 * dens).toInt()) * scale * 1.15f).toInt()
        val statH = ((sysDimen(c, "status_bar_height").takeIf { it > 0 } ?: (30 * dens).toInt()) * scale * 1.15f).toInt()
        fun bar(y: Int) = darkRow(bmp, y, left, right) || ((y >= h - navH || y < statH) && uniformRow(bmp, y))
        // ด้านล่าง: ข้ามแถบปุ่มของแอปได้ไกลขึ้น (ช่องส่งข้อความ + อีโมจิของสตอรี่ FB) ถ้าขอบจอยังดำ
        val uiSkip = (h * 0.10f).toInt()
        top = scan(top, maxV, 2, skip) { bar(it) }
        bottom = scan(bottom, maxOf(h - maxV, top + 32), -2, skip, uiSkip, { edgeBlack(bmp, it - 1) }) { bar(it - 1) }
        left = scan(left, minOf(maxH, right - 32), 2, skip) { darkCol(bmp, it, top, bottom) }
        right = scan(right, maxOf(w - maxH, left + 32), -2, skip) { darkCol(bmp, it - 1, top, bottom) }
        return Rect(left, top.coerceAtLeast(0), right, bottom.coerceAtMost(h))
    }

    /**
     * ตัดขอบดำ/แถบ UI ที่อยู่ "ข้างใน" กรอบ r ออก (กรอบที่ผู้ใช้ลากมาคร่าว ๆ → พอดีภาพ)
     * ไล่จากแต่ละขอบเข้าไปตราบที่ยังเป็นแถว/คอลัมน์มืด ไม่เกิน 45% ของกรอบ
     */
    fun trimDark(bmp: Bitmap, r: Rect): Rect {
        val w = bmp.width; val h = bmp.height
        var left = r.left.coerceIn(0, w - 1); var right = r.right.coerceIn(left + 1, w)
        var top = r.top.coerceIn(0, h - 1); var bottom = r.bottom.coerceIn(top + 1, h)
        val skip = (w * 0.066f).toInt().coerceAtLeast(24)
        val rh = bottom - top; val rw = right - left
        top = scan(top, top + (rh * 0.45f).toInt(), 2, skip) { darkRow(bmp, it, left, right) }
        bottom = scan(bottom, maxOf(bottom - (rh * 0.45f).toInt(), top + 32), -2, skip) { darkRow(bmp, it - 1, left, right) }
        left = scan(left, left + (rw * 0.35f).toInt(), 2, skip) { darkCol(bmp, it, top, bottom) }
        right = scan(right, maxOf(right - (rw * 0.35f).toInt(), left + 32), -2, skip) { darkCol(bmp, it - 1, top, bottom) }
        return Rect(left, top, right, bottom)
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /**
     * เดินจาก start ไปทาง limit ตราบที่ยังเป็นขอบดำ
     * ข้ามเส้นบาง ๆ ได้ (เส้นกรอบ/ตัวหนังสือแถบสถานะ/ปุ่มลอยของเรา) ถ้าถัดไปยังดำอยู่
     */
    private inline fun scan(
        start: Int, limit: Int, step: Int, skip: Int,
        longSkip: Int = 0, crossinline longOk: (Int) -> Boolean = { false }, isDark: (Int) -> Boolean
    ): Int {
        var p = start
        val before = { q: Int -> if (step > 0) q < limit else q > limit }
        val maxSkip = maxOf(skip, longSkip)
        while (before(p)) {
            if (isDark(p)) { p += step; continue }
            var k = 2
            var thin = false
            while (k <= maxSkip) {
                val q = p + if (step > 0) k else -k
                // ข้ามไกลกว่าปกติได้เฉพาะแถวที่ขอบจอดำ (แถบปุ่มของแอป ไม่ใช่เนื้อวิดีโอ)
                if (k > skip && !longOk(q)) break
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

    /** สีเรียบทั้งแถว (แถบระบบสีขาว/เทา/ดำ) */
    private fun uniformRow(b: Bitmap, y: Int): Boolean {
        if (y < 0 || y >= b.height) return false
        var mn = 255; var mx = 0
        for (i in 0 until 48) {
            val l = lum(b.getPixel(((b.width - 1) * i / 47), y))
            if (l < mn) mn = l; if (l > mx) mx = l
        }
        return mx - mn <= 10
    }

    /** ขอบซ้ายหรือขวาของแถวดำสนิท */
    private fun edgeBlack(b: Bitmap, y: Int): Boolean {
        if (y < 0 || y >= b.height) return false
        val w = b.width
        fun edge(fr: FloatArray) = fr.count { f -> lum(b.getPixel((w * f).toInt().coerceIn(0, w - 1), y)) <= 22 } >= 2
        return edge(floatArrayOf(0.012f, 0.017f, 0.022f)) || edge(floatArrayOf(0.978f, 0.983f, 0.988f))
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
