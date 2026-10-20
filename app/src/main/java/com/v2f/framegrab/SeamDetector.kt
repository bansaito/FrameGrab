package com.v2f.framegrab

import android.graphics.Rect
import android.media.Image
import kotlin.math.abs

/**
 * จับจังหวะ "เลื่อนสตอรี่" (สไลด์/ลูกบาศก์ของ TikTok / IG / FB):
 * ระหว่างเลื่อนจะมีขอบแนวตั้งตรงยาวเกือบเต็มความสูง (รอยต่อระหว่างสตอรี่เก่ากับใหม่) และขอบนั้นเคลื่อนที่
 * คลิปที่ตัดต่อเปลี่ยนฉากเฉย ๆ ไม่มีรอยต่อแบบนี้ จึงไม่หยุดผิด
 * ตรวจทุกเฟรมที่จอแสดง (รอยต่อโผล่แค่เสี้ยววินาที)
 */
class SeamDetector {
    @Volatile var seamAtMs = -1L
        private set
    private var lastT = -1L
    private var lastX = -1
    private var firstT = -1L

    @Synchronized fun reset() { seamAtMs = -1L; lastT = -1L; lastX = -1; firstT = -1L }

    @Synchronized fun feed(tMs: Long, img: Image, r: Rect) {
        if (seamAtMs >= 0 || tMs < 0) return
        val w = img.width; val h = img.height
        val left = r.left.coerceIn(0, w - 1); val right = r.right.coerceIn(left + 1, w)
        val top = r.top.coerceIn(0, h - 1); val bottom = r.bottom.coerceIn(top + 1, h)
        val rw = right - left; val rh = bottom - top
        if (rw < 60 || rh < 60) return
        val p = img.planes[0]; val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride; val lim = buf.limit()
        fun lum(x: Int, y: Int): Int {
            val off = y * rs + x * ps
            if (off + 2 >= lim) return 0
            return ((buf.get(off).toInt() and 0xFF) * 299 + (buf.get(off + 1).toInt() and 0xFF) * 587 +
                (buf.get(off + 2).toInt() and 0xFF) * 114) / 1000
        }
        val sx = (rw / 180).coerceAtLeast(1)
        val rows = IntArray(24) { j -> top + (rh * (0.2f + 0.6f * j / 23)).toInt() }
        var best = 0; var bestX = -1
        var x = left + (rw * 0.05f).toInt()
        val xEnd = left + (rw * 0.95f).toInt()
        while (x < xEnd) {
            var pos = 0; var neg = 0
            for (y in rows) {
                val d = lum(minOf(x + sx, w - 1), y) - lum(maxOf(x - sx, 0), y)
                if (d >= 25) pos++ else if (d <= -25) neg++
            }
            val m = maxOf(pos, neg)
            if (m > best) { best = m; bestX = x }
            x += sx
        }
        if (best >= 22) {
            // รอยต่อเจอติดกัน (ห่าง ≤150 ms) และขยับไปอย่างน้อย 1% ของกรอบ = กำลังเลื่อนสตอรี่
            if (lastT >= 0 && tMs - lastT <= 150 && abs(bestX - lastX) >= rw * 0.01f) {
                seamAtMs = firstT
                return
            }
            if (lastT < 0 || tMs - lastT > 150) firstT = tMs
            lastT = tMs; lastX = bestX
        } else if (lastT >= 0 && tMs - lastT > 150) {
            lastT = -1L; lastX = -1; firstT = -1L
        }
    }
}
