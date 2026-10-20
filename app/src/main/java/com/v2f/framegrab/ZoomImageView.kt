package com.v2f.framegrab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * รูปที่ซูมด้วยนิ้วได้: ถ่างสองนิ้ว = ซูม, ลากตอนซูม = เลื่อน, แตะสองครั้ง = ซูม/ย่อกลับ
 * ตอนไม่ได้ซูม ปัดซ้าย/ขวา = เปลี่ยนภาพ (onSwipe), แตะครั้งเดียว = onTap
 */
@SuppressLint("ViewConstructor")
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null

    private val m = Matrix()
    private val base = Matrix()
    private var zoom = 1f
    private val maxZoom = 5f
    private var wasZoomed = false

    init {
        scaleType = ScaleType.MATRIX
    }

    val isZoomed: Boolean get() = zoom > 1.01f

    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        base.setRectToRect(
            RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        m.set(base)
        zoom = 1f
        imageMatrix = m
        notifyZoom()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    private fun zoomBy(f0: Float, fx: Float, fy: Float) {
        val nz = (zoom * f0).coerceIn(1f, maxZoom)
        val f = nz / zoom
        zoom = nz
        m.postScale(f, f, fx, fy)
        fix()
    }

    /** จัดให้ภาพไม่หลุดขอบ: ถ้าเล็กกว่าจอให้อยู่กลาง ถ้าใหญ่กว่าห้ามมีช่องว่างขอบ */
    private fun fix() {
        val d = drawable ?: return
        if (zoom <= 1.0001f) { m.set(base); zoom = 1f; imageMatrix = m; notifyZoom(); return }
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        val w = width.toFloat(); val h = height.toFloat()
        val dx = when {
            r.width() <= w -> (w - r.width()) / 2 - r.left
            r.left > 0 -> -r.left
            r.right < w -> w - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= h -> (h - r.height()) / 2 - r.top
            r.top > 0 -> -r.top
            r.bottom < h -> h - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        imageMatrix = m
        notifyZoom()
    }

    private fun notifyZoom() {
        if (isZoomed != wasZoomed) {
            wasZoomed = isZoomed
            onZoomChanged?.invoke(isZoomed)
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isZoomed) {
                m.postTranslate(-distanceX, -distanceY)
                fix()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (isZoomed || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > width * 0.12f && abs(dx) > abs(dy) * 1.3f) {
                onSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) resetZoom() else zoomBy(2.5f, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
