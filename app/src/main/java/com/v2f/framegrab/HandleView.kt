package com.v2f.framegrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** จุดจับเล็ก ๆ บนมุมกรอบ (move = ปุ่มย้ายกรอบ) ลากได้ทันที ส่วนอื่นของจอยังกดทะลุได้ */
class HandleView(ctx: Context, private val move: Boolean, private val edge: Int = 0) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (move) 0xE64F46E5.toInt() else Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (move) Color.WHITE else 0xFF4F46E5.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val pillRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F46E5.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (edge != 0) {
            // จุดจับกลางขอบ: ขีดเล็ก ๆ บอกว่าลากได้
            val horiz = edge == 2 || edge == 8
            val a = 16f * d; val b = 3.5f * d
            val r = if (horiz) android.graphics.RectF(cx - a, cy - b, cx + a, cy + b) else android.graphics.RectF(cx - b, cy - a, cx + b, cy + a)
            canvas.drawRoundRect(r, b, b, pill); canvas.drawRoundRect(r, b, b, pillRing)
            return
        }
        val r = (if (move) 13f else 9f) * d
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        if (move) { // ✥
            val a = 7f * d; val h = 2.5f * d
            canvas.drawLine(cx - a, cy, cx + a, cy, arrow)
            canvas.drawLine(cx, cy - a, cx, cy + a, arrow)
            for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val tx = cx + dx * a; val ty = cy + dy * a
                canvas.drawLine(tx, ty, tx - dx * h + dy * h, ty - dy * h + dx * h, arrow)
                canvas.drawLine(tx, ty, tx - dx * h - dy * h, ty - dy * h - dx * h, arrow)
            }
        }
    }
}
