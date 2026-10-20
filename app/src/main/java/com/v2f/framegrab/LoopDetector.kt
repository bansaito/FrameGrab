package com.v2f.framegrab

import android.media.Image
import kotlin.math.abs

/**
 * ตรวจว่าคลิปเล่นครบหนึ่งรอบ: TikTok / Reels / IG วนกลับไปต้นคลิปเมื่อจบ
 * ตรวจ "ทุกเฟรม" ที่จอแสดง (ไม่ใช่แค่ทุก 0.25 วิ) จึงเจอเฟรมเดียวกับตอนเริ่มอัดแบบตรงเป๊ะ
 * แล้วยืนยันว่า 1 วินาทีถัดมาก็ตรงกับ 1 วินาทีแรกของการอัดด้วย
 * เกณฑ์เทียบกับความต่างปกติของคลิปนั้นเอง คลิปภาพนิ่งหรือคลิปที่ไม่วนจึงไม่ถูกหยุดผิด ๆ
 */
class LoopDetector {
    companion object {
        private const val W = 18
        private const val H = 32
        private const val REF_MS = 1500L      // เก็บ 1.5 วิแรกไว้เทียบ
        private const val MIN_LOOP_MS = 3000L // คลิปสั้นกว่านี้ไม่ตรวจ
        private const val VERIFY_MS = 600L

        /** ภาพย่อขาวดำ 18×32 จุดจากส่วนกลางจอ อ่านจากบัฟเฟอร์ตรง ๆ ไม่ต้องแปลงทั้งภาพ */
        fun signature(img: Image): IntArray {
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            val x0 = img.width * 0.15f
            val y0 = img.height * 0.15f
            val cw = img.width * 0.6f
            val ch = img.height * 0.6f
            val out = IntArray(W * H)
            val lim = buf.limit()
            for (j in 0 until H) {
                val cy = (y0 + (j + 0.5f) * ch / H).toInt()
                for (i in 0 until W) {
                    val cx = (x0 + (i + 0.5f) * cw / W).toInt()
                    var sum = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val off = (cy + dy * 2) * rs + (cx + dx * 2) * ps
                        if (off + 2 < lim) {
                            val r = buf.get(off).toInt() and 0xFF
                            val g = buf.get(off + 1).toInt() and 0xFF
                            val b = buf.get(off + 2).toInt() and 0xFF
                            sum += (r * 299 + g * 587 + b * 114) / 1000
                        }
                    }
                    out[j * W + i] = sum / 4
                }
            }
            return out
        }

        private fun diff(a: IntArray, b: IntArray): Double {
            var s = 0L
            for (i in a.indices) s += abs(a[i] - b[i])
            return s.toDouble() / a.size
        }
    }

    private class Cand(var start: Long, var d0: Double) { var sum = 0.0; var n = 0 }

    private val refT = ArrayList<Long>()
    private val refS = ArrayList<IntArray>()
    private val hist = ArrayList<Double>()
    private val cands = ArrayList<Cand>()

    @Volatile var loopAtMs = -1L
        private set

    @Synchronized fun reset() {
        refT.clear(); refS.clear(); hist.clear(); cands.clear(); loopAtMs = -1L
    }

    private fun median(): Double {
        val s = hist.sorted()
        return s[s.size / 2]
    }

    private fun nearestRef(dt: Long): Int {
        var lo = 0; var hi = refT.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (refT[mid] < dt) lo = mid + 1 else hi = mid }
        return if (lo > 0 && abs(refT[lo - 1] - dt) < abs(refT[lo] - dt)) lo - 1 else lo
    }

    /** ส่งทุกเฟรมเข้ามาพร้อมเวลา (มิลลิวินาทีนับจากเริ่มอัด) */
    @Synchronized fun feed(tMs: Long, sig: IntArray) {
        if (loopAtMs >= 0 || tMs < 0) return
        if (tMs < REF_MS) { refT.add(tMs); refS.add(sig); return }
        if (refS.isEmpty()) return
        val d0 = diff(sig, refS[0])
        val base = if (hist.size >= 20) median() else -1.0

        // ตรวจผู้สมัครที่รอยืนยัน
        val it = cands.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val dt = tMs - c.start
            if (dt <= VERIFY_MS) {
                val k = nearestRef(dt)
                if (abs(refT[k] - dt) <= 50) { c.sum += diff(sig, refS[k]); c.n++ }
            } else {
                val m = c.sum / maxOf(c.n, 1)
                if (c.n >= 5 && base > 0 && m < minOf(0.2 * base, 2.5)) { loopAtMs = c.start; return }
                it.remove()
            }
        }

        // เฟรมนี้เหมือนเฟรมแรกมาก = อาจเป็นจุดวนกลับ
        if (tMs >= MIN_LOOP_MS && base > 0 && d0 < minOf(0.25 * base, 4.0)) {
            val near = cands.lastOrNull { tMs - it.start <= 300 }
            if (near != null) {
                if (d0 < near.d0) { near.start = tMs; near.d0 = d0; near.sum = 0.0; near.n = 0 }
            } else if (cands.size < 6) {
                cands.add(Cand(tMs, d0))
            }
            return
        }
        hist.add(d0)
        if (hist.size > 300) hist.removeAt(0)
    }
}
