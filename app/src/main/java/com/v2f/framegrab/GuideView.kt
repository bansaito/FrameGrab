package com.v2f.framegrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** กรอบที่แสดงบนจอ บอกว่าส่วนไหนจะถูกเก็บ (หน้าต่างนี้กดทะลุได้ และถูกซ่อนตอนจับภาพ) */
class GuideView(ctx: Context) : View(ctx) {
    /** กรอบในพิกัดจอจริง */
    var rect: RectF? = null
    var label: String = ""

    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x40000000 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND
    }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB34F46E5.toInt() }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; isFakeBoldText = true }
    private val loc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        val r = rect ?: return
        getLocationOnScreen(loc) // หน้าต่างอาจไม่ได้เริ่มที่มุมจอพอดี
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val big = 100000f
        canvas.drawRect(-big, -big, big, r.top, dim)
        canvas.drawRect(-big, r.bottom, big, big, dim)
        canvas.drawRect(-big, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, big, r.bottom, dim)
        canvas.drawRect(r, border)
        val L = minOf(22f * d, r.width() / 4, r.height() / 4)
        for ((x, sx) in listOf(r.left to 1f, r.right to -1f)) for ((y, sy) in listOf(r.top to 1f, r.bottom to -1f)) {
            canvas.drawLine(x, y, x + L * sx, y, corner)
            canvas.drawLine(x, y, x, y + L * sy, corner)
        }
        if (label.isNotEmpty()) {
            val pad = 6f * d
            val w = tagText.measureText(label) + pad * 2
            val h = tagText.textSize + pad * 1.2f
            val tx = r.left + 8f * d; val ty = r.top + 8f * d
            canvas.drawRoundRect(tx, ty, tx + w, ty + h, h / 2, h / 2, tagBg)
            canvas.drawText(label, tx + pad, ty + h / 2 + tagText.textSize * 0.35f, tagText)
        }
        canvas.restore()
    }
}
