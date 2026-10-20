package com.v2f.framegrab

import android.media.Image
import kotlin.math.abs

/**
 * แถบความคืบหน้าด้านบนของสตอรี่ (TikTok / IG / FB): แบ่งเป็นช่อง ๆ ช่องที่ดูแล้วเป็นสีขาว
 * ตรวจว่า "ช่องปัจจุบันเต็ม แล้วช่องถัดไปเริ่มขึ้น" = จบสตอรี่นี้ ไปเรื่องถัดไปของคนเดิม
 * (ใช้คู่กับ SeamDetector ที่จับตอนเลื่อนไปสตอรี่ของคนถัดไป)
 */
class StoryBar {
    companion object { private const val N = 180 }

    private var y = -1
    private var prev: List<IntArray>? = null
    private var pendingAt = -1L
    private var pendingSize = -1
    /** เวลาที่ขึ้นช่องถัดไป (ms นับจากเริ่มอัด) หรือ -1 */
    var advancedAtMs = -1L
        private set

    @Synchronized fun reset() { lastLocateAt = -1000L; y = -1; prev = null; pendingAt = -1L; pendingSize = -1; advancedAtMs = -1L }

    /** อ่านพิกเซลจาก Image (RGBA) ตรง ๆ — ตรวจได้ทุกเฟรมที่จอแสดง */
    private class Px(img: Image) {
        val width = img.width; val height = img.height
        private val pl = img.planes[0]; private val buf = pl.buffer
        private val rs = pl.rowStride; private val ps = pl.pixelStride; private val lim = buf.limit()
        fun rgb(x: Int, y: Int): Int {
            val off = y * rs + x * ps
            if (off + 2 >= lim) return 0
            return ((buf.get(off).toInt() and 0xFF) shl 16) or ((buf.get(off + 1).toInt() and 0xFF) shl 8) or (buf.get(off + 2).toInt() and 0xFF)
        }
    }

    private fun whiteMask(b: Px, yy: Int): BooleanArray {
        val l = IntArray(N); val c = IntArray(N)
        for (i in 0 until N) {
            val p = b.rgb((b.width - 1) * i / (N - 1), yy)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            l[i] = (r * 299 + g * 587 + bl * 114) / 1000; c[i] = maxOf(r, g, bl) - minOf(r, g, bl)
        }
        val mx = l.sorted()[(N * 0.97f).toInt()]
        val thr = maxOf(150, mx - 30)
        return BooleanArray(N) { l[it] >= thr && c[it] <= 25 }
    }

    private fun runs(m: BooleanArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < N) {
            if (!m[i]) { i++; continue }
            var j = i
            while (j < N && m[j]) j++
            if (j - i >= 2) {
                val last = out.lastOrNull()
                if (last != null && i - last[1] <= 1) last[1] = j else out.add(intArrayOf(i, j))
            }
            i = j
        }
        return out
    }

    /** หาแถวของแถบ (ด้านบน 12% ของจอ, เส้นบาง, เริ่มจากซ้าย, ช่องห่างกันนิดเดียว) */
    private fun locate(b: Px): Boolean {
        val h = b.height
        val d = maxOf((h * 0.012f).toInt(), 3)
        for (yy in (h * 0.005f).toInt() until (h * 0.12f).toInt()) {
            val m = whiteMask(b, yy)
            val rs = runs(m)
            if (rs.isEmpty() || rs[0][0] > N * 0.08f) continue
            val cnt = m.count { it }
            if (cnt < N * 0.03f) continue
            if ((0 until rs.size - 1).any { rs[it + 1][0] - rs[it][1] > N * 0.05f }) continue
            if (whiteMask(b, maxOf(yy - d, 0)).count { it } > cnt * 0.4f) continue
            if (whiteMask(b, minOf(yy + d, h - 1)).count { it } > cnt * 0.4f) continue
            y = yy
            return true
        }
        return false
    }

    private var lastLocateAt = -1000L

    /** ส่งทุกเฟรมที่จอแสดงระหว่างอัด */
    @Synchronized fun feed(tMs: Long, img: Image) {
        if (advancedAtMs >= 0 || tMs < 0) return
        val b = Px(img)
        if (y < 0) {
            // หาแถบไม่เกินทุก 250 ms ในช่วง 3 วิแรก (หาเจอแล้วใช้แถวเดิมตลอด)
            if (tMs > 3000 || tMs - lastLocateAt < 250) return
            lastLocateAt = tMs
            if (!locate(b)) return
        }
        val r = runs(whiteMask(b, y))
        val p = prev
        prev = r
        if (p == null || r.isEmpty()) return
        // ยืนยันเฟรมถัด ๆ มา (≥60 ms): ยังมีจำนวนช่องเท่าเดิม (หรือมากกว่า) = ขึ้นช่องใหม่จริง
        if (pendingAt >= 0) {
            // 2 วิแรกของการอัดไม่นับ (กันพลาดตอนเพิ่งเริ่ม)
            if (r.size >= pendingSize) {
                if (tMs - pendingAt < 60) return
                if (pendingAt >= 2000) { advancedAtMs = pendingAt; return }
            }
            pendingAt = -1L
        }
        // ช่องใหม่โผล่ต่อจากช่องเดิมพอดี (ห่างแค่ช่องว่างระหว่างช่อง) และยังสั้น (เพิ่งเริ่ม)
        if (r.size == p.size + 1 && p.isNotEmpty()) {
            val same = (0 until p.size - 1).all { abs(r[it][0] - p[it][0]) <= 1 }
            val nl = r[r.size - 1]; val before = r[r.size - 2]
            val gap = nl[0] - before[1]
            if (same && gap in 1..5 && nl[1] - nl[0] <= 8) { pendingAt = tMs; pendingSize = r.size }
        }
    }
}
