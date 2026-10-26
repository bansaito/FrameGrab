package com.v2f.ankirounds

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** พื้นปุ่มลอยทรงกลม + วงแหวนความคืบหน้าของรอบ (ไม่มีตัวเลข) */
class RingDrawable(private val strokePx: Float) : Drawable() {
    var fillColor: Int = 0xE62563EB.toInt()
        set(v) { field = v; invalidateSelf() }

    /** 0..1 · ค่าติดลบ = ไม่แสดงวงแหวน */
    var progress: Float = -1f
        set(v) { field = v; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x55FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        fill.color = fillColor
        c.drawOval(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fill)
        if (progress < 0f) return
        track.strokeWidth = strokePx; arc.strokeWidth = strokePx
        val inset = strokePx * 1.2f
        oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        c.drawOval(oval, track)
        c.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
