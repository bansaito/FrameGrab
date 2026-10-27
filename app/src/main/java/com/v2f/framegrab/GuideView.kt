package com.v2f.framegrab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * กรอบที่แสดงบนจอ บอกว่าส่วนไหนจะถูกเก็บ
 * ปกติกดทะลุได้ และถูกซ่อนตอนจับภาพ
 * โหมดแก้ไข (editing): ลากกลางกรอบ = ย้าย, ลากมุม/ขอบ = ปรับขนาด, ล็อกสัดส่วนได้
 */
class GuideView(ctx: Context) : View(ctx) {
    /** กรอบในพิกัดจอจริง */
    var rect: RectF? = null
        set(v) { field = v; onRectChanged?.invoke() }
    /** เรียกทุกครั้งที่กรอบเปลี่ยน (ใช้ขยับจุดจับตาม) */
    var onRectChanged: (() -> Unit)? = null
    var label: String = ""
    var editing = false
        set(v) { field = v; invalidate() }
    /** ล็อกสัดส่วนกว้าง/สูง (null = อิสระ) */
    var lockRatio: Float? = null
    /** ขอบเขตที่กรอบขยับได้ (พิกัดจอ) */
    val bounds = RectF()

    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x40000000 }
    private val dimEdit = Paint().apply { color = 0x80000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4F46E5.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val grid = Paint().apply { color = 0x59FFFFFF; strokeWidth = 1f * d }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB34F46E5.toInt() }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; isFakeBoldText = true }
    private val loc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        val r = rect ?: return
        getLocationOnScreen(loc) // หน้าต่างอาจไม่ได้เริ่มที่มุมจอพอดี
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val big = 100000f
        val dp = if (editing) dimEdit else dim
        canvas.drawRect(-big, -big, big, r.top, dp)
        canvas.drawRect(-big, r.bottom, big, big, dp)
        canvas.drawRect(-big, r.top, r.left, r.bottom, dp)
        canvas.drawRect(r.right, r.top, big, r.bottom, dp)
        canvas.drawRect(r, border)
        if (editing) {
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3; val y = r.top + r.height() * i / 3
                canvas.drawLine(x, r.top, x, r.bottom, grid)
                canvas.drawLine(r.left, y, r.right, y, grid)
            }
            val hs = 9f * d
            val pts = listOf(
                r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom,
                r.centerX() to r.top, r.centerX() to r.bottom, r.left to r.centerY(), r.right to r.centerY()
            )
            for ((x, y) in pts) { canvas.drawCircle(x, y, hs, handle); canvas.drawCircle(x, y, hs, handleRing) }
        } else {
            // กำลังลาก: มุมใหญ่และหนาขึ้น ให้เห็นชัดว่าจับอยู่
            val L = minOf((if (bigCorners) 36f else 22f) * d, r.width() / 4, r.height() / 4)
            corner.strokeWidth = (if (bigCorners) 5f else 3f) * d
            for ((x, sx) in listOf(r.left to 1f, r.right to -1f)) for ((y, sy) in listOf(r.top to 1f, r.bottom to -1f)) {
                canvas.drawLine(x, y, x + L * sx, y, corner)
                canvas.drawLine(x, y, x, y + L * sy, corner)
            }
        }
        // ป้ายสัดส่วนแสดงเฉพาะตอนลาก/แก้กรอบ (ปกติดูที่ปุ่มลอย)
        // ตอนลากด้วยจุดมุม สัดส่วนไปแสดงที่ปุ่มลอยแทน (ป้ายบนกรอบมักถูกนิ้วบัง)
        val tag = if (editing) Cropper.ratioTag(r.width().toInt(), r.height().toInt()) else ""
        if (tag.isNotEmpty()) {
            val pad = 6f * d
            val w = tagText.measureText(tag) + pad * 2
            val h = tagText.textSize + pad * 1.2f
            val tx = r.left + 8f * d; val ty = r.top + 8f * d
            canvas.drawRoundRect(tx, ty, tx + w, ty + h, h / 2, h / 2, tagBg)
            canvas.drawText(tag, tx + pad, ty + h / 2 + tagText.textSize * 0.35f, tagText)
        }
        canvas.restore()
    }

    // ---------- ลากปรับกรอบ ----------
    private var mode = 0 // บิต: 1=ซ้าย 2=บน 4=ขวา 8=ล่าง, 16=ย้าย
    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return false
        val r = rect ?: return false
        val x = e.rawX; val y = e.rawY // พิกัดจอ
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 30 * d
                mode = 0
                val inY = y in r.top - tol..r.bottom + tol
                val inX = x in r.left - tol..r.right + tol
                if (abs(x - r.left) < tol && inY) mode = mode or 1
                if (abs(x - r.right) < tol && inY) mode = mode or 4
                if (abs(y - r.top) < tol && inX) mode = mode or 2
                if (abs(y - r.bottom) < tol && inX) mode = mode or 8
                if (mode == 0 && r.contains(x, y)) mode = 16
                beginDrag(mode, x, y)
            }
            MotionEvent.ACTION_MOVE -> dragTo(x, y)
        }
        return true
    }

    /** เริ่มลากจากจุดจับภายนอก (mode เป็นบิตแบบเดียวกับข้างบน) */
    // ---------- ดูดเข้าสัดส่วนมาตรฐาน ----------
    private val SNAPS = floatArrayOf(9 / 16f, 2 / 3f, 3 / 4f, 4 / 5f, 1f, 5 / 4f, 4 / 3f, 3 / 2f, 16 / 9f)
    private var raw: RectF? = null      // กรอบตามนิ้วจริง (ก่อนดูด)
    private var snapped: Float? = null  // สัดส่วนที่ดูดอยู่
    /** แสดงสัดส่วนบนป้ายแทนชื่อกรอบ */
    var showRatio = false
    /** กำลังลากด้วยจุดมุม: วาดมุมใหญ่ขึ้น */
    var bigCorners = false
        set(v) { field = v; invalidate() }

    private fun inBounds(n: RectF) = n.left >= bounds.left - 0.5f && n.top >= bounds.top - 0.5f &&
        n.right <= bounds.right + 0.5f && n.bottom <= bounds.bottom + 0.5f

    /** เข้าใกล้สัดส่วนมาตรฐาน (≤4%) = ดูดเข้า, ดูดแล้วต้องลากเกิน 8% ถึงจะหลุด */
    private fun snap(n: RectF): RectF {
        if (lockRatio != null || n.height() <= 0f) { snapped = null; return n }
        val r = n.width() / n.height()
        val cur = snapped
        // ดูดเบา ๆ: เข้าใกล้ ≤3% ดูด, ลากเกิน 5% หลุด (9:16 / 3:4 ได้สิทธิ์ก่อนถ้าใกล้พอ ๆ กัน)
        fun primary(t: Float) = abs(t - 9 / 16f) < 0.001f || abs(t - 3 / 4f) < 0.001f
        // 9:16 / 3:4 ดูดแรงกว่า: เข้าใกล้ ≤7% ดูด, ลากเกิน 9% หลุด (อื่น ๆ 3% / 5%)
        val target = if (cur != null && abs(r - cur) / cur < (if (primary(cur)) 0.09f else 0.05f)) cur
            else SNAPS.filter { abs(r - it) / it < (if (primary(it)) 0.07f else 0.03f) }
                .minByOrNull { abs(r - it) / it * (if (primary(it)) 0.5f else 1f) }
        snapped = target
        if (target == null) return n
        val out = RectF(n)
        if (mode and 10 != 0 || mode == 16) { // ลากขอบบน/ล่าง/มุม/ย้าย: ปรับความสูง
            val h = out.width() / target
            if (mode and 2 != 0) out.top = out.bottom - h else out.bottom = out.top + h
        } else { // ลากขอบซ้าย/ขวา: ปรับความกว้าง
            val w = out.height() * target
            if (mode and 1 != 0) out.left = out.right - w else out.right = out.left + w
        }
        if (!inBounds(out)) { // ชนขอบจอ ลองปรับอีกด้านแทน
            val alt = RectF(n)
            val w = alt.height() * target
            val cx = alt.centerX()
            alt.left = cx - w / 2; alt.right = cx + w / 2
            if (inBounds(alt)) return alt
            snapped = null
            return n
        }
        return out
    }

    fun beginDrag(m: Int, x: Float, y: Float) {
        mode = m; lastX = x; lastY = y
        raw = rect?.let { RectF(it) }
        val r = rect
        snapped = if (r != null && r.height() > 0) SNAPS.firstOrNull { abs(r.width() / r.height() - it) / it < 0.005f } else null
        showRatio = true
    }

    /** ลากไปที่ (x, y) ในพิกัดจอ */
    fun dragTo(x: Float, y: Float) {
        val r = raw ?: rect ?: return
        run {
                val dx = x - lastX; val dy = y - lastY
                lastX = x; lastY = y
                val n = RectF(r)
                val minS = 60 * d
                val lock = lockRatio
                when {
                    mode == 16 -> {
                        val mx = dx.coerceIn(bounds.left - r.left, bounds.right - r.right)
                        val my = dy.coerceIn(bounds.top - r.top, bounds.bottom - r.bottom)
                        n.offset(mx, my)
                    }
                    mode == 0 -> return
                    lock == null -> {
                        if (mode and 1 != 0) n.left = (r.left + dx).coerceIn(bounds.left, r.right - minS)
                        if (mode and 4 != 0) n.right = (r.right + dx).coerceIn(r.left + minS, bounds.right)
                        if (mode and 2 != 0) n.top = (r.top + dy).coerceIn(bounds.top, r.bottom - minS)
                        if (mode and 8 != 0) n.bottom = (r.bottom + dy).coerceIn(r.top + minS, bounds.bottom)
                    }
                    (mode and 5 != 0) && (mode and 10 != 0) -> { // มุม: ยึดมุมตรงข้าม คงสัดส่วน
                        val ax = if (mode and 1 != 0) r.right else r.left
                        val ay = if (mode and 2 != 0) r.bottom else r.top
                        val mxp = (if (mode and 1 != 0) r.left else r.right) + dx
                        val w = abs(mxp - ax).coerceAtLeast(minS)
                        val h = w / lock
                        n.left = if (mode and 1 != 0) ax - w else ax
                        n.right = n.left + w
                        n.top = if (mode and 2 != 0) ay - h else ay
                        n.bottom = n.top + h
                    }
                    mode and 10 != 0 -> { // ขอบบน/ล่าง: เปลี่ยนสูง กว้างตาม คงกึ่งกลางแนวนอน
                        val h = (if (mode and 2 != 0) r.bottom - (r.top + dy) else (r.bottom + dy) - r.top).coerceAtLeast(minS)
                        val w = h * lock
                        n.left = r.centerX() - w / 2; n.right = n.left + w
                        if (mode and 2 != 0) { n.bottom = r.bottom; n.top = r.bottom - h } else { n.top = r.top; n.bottom = r.top + h }
                    }
                    else -> { // ขอบซ้าย/ขวา: เปลี่ยนกว้าง สูงตาม คงกึ่งกลางแนวตั้ง
                        val w = (if (mode and 1 != 0) r.right - (r.left + dx) else (r.right + dx) - r.left).coerceAtLeast(minS)
                        val h = w / lock
                        n.top = r.centerY() - h / 2; n.bottom = n.top + h
                        if (mode and 1 != 0) { n.right = r.right; n.left = r.right - w } else { n.left = r.left; n.right = r.left + w }
                    }
                }
                // ไม่ให้หลุดขอบจอ (ถ้าเกิน ไม่ขยับ)
                if (n.left >= bounds.left - 0.5f && n.top >= bounds.top - 0.5f &&
                    n.right <= bounds.right + 0.5f && n.bottom <= bounds.bottom + 0.5f &&
                    n.width() >= minS - 0.5f && n.height() >= minS - 0.5f
                ) {
                    raw = n
                    rect = snap(n)
                    invalidate()
                }
        }
    }
}
