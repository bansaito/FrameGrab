package com.v2f.framegrab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/** ลากกรอบบนภาพหน้าจอจริง เพื่อกำหนดส่วนที่จะเก็บ */
class CropActivity : Activity() {

    private lateinit var crop: CropView

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12f).toFloat() }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        val ref = Cropper.refFile(this)
        val bmp: Bitmap? = if (ref.isFile) BitmapFactory.decodeFile(ref.path) else null
        if (bmp == null) {
            Toast.makeText(
                this, "ยังไม่มีภาพหน้าจอให้ตั้งกรอบ: เปิดแอปวิดีโอ แตะปุ่มลอย 🎞️ → ✂️ กรอบ → กำหนดเอง",
                Toast.LENGTH_LONG
            ).show()
            finish(); return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12141B.toInt())
            setPadding(dp(12f), dp(28f), dp(12f), dp(16f))
        }
        col.addView(TextView(this).apply {
            text = "ลากมุมหรือขอบกรอบให้พอดีกับวิดีโอ ลากตรงกลางเพื่อย้ายกรอบ"
            setTextColor(0xFFB8BCCB.toInt()); textSize = 14f
            setPadding(dp(4f), 0, dp(4f), dp(10f))
        })

        crop = CropView(this).apply { bitmap = bmp }
        // เริ่มจากกรอบที่ตั้งไว้ ถ้าไม่เคยตั้ง ใช้กรอบที่หาได้อัตโนมัติ
        if (Prefs.hasCustomCrop(this)) {
            val r = Prefs.cropRect(this); crop.frac.set(r[0], r[1], r[2], r[3])
        } else {
            val a = Cropper.autoRect(this, bmp)
            crop.frac.set(
                a.left / bmp.width.toFloat(), a.top / bmp.height.toFloat(),
                a.right / bmp.width.toFloat(), a.bottom / bmp.height.toFloat()
            )
        }
        col.addView(crop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f); marginEnd = dp(4f) } }
        row.addView(button("✨ อัตโนมัติ", 0xFF2A2D3A.toInt()) {
            val a = Cropper.autoRect(this, bmp)
            crop.frac.set(a.left / bmp.width.toFloat(), a.top / bmp.height.toFloat(), a.right / bmp.width.toFloat(), a.bottom / bmp.height.toFloat())
            crop.invalidate()
        }, lp())
        row.addView(button("↺ ทั้งจอ", 0xFF2A2D3A.toInt()) { crop.frac.set(0f, 0f, 1f, 1f); crop.invalidate() }, lp())
        row.addView(button("✔ ใช้กรอบนี้", 0xFF4F46E5.toInt()) {
            val f = crop.frac
            Prefs.setCropRect(this, f.left, f.top, f.right, f.bottom)
            Prefs.setCropPreset(this, "custom")
            Toast.makeText(this, "ตั้งกรอบแล้ว ภาพต่อไปจะเก็บเฉพาะในกรอบนี้", Toast.LENGTH_SHORT).show()
            finish()
        }, lp())
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        setContentView(col)
    }

    /** แสดงภาพ + กรอบที่ลากได้ */
    class CropView(ctx: Context) : View(ctx) {
        var bitmap: Bitmap? = null
        val frac = RectF(0f, 0f, 1f, 1f)
        private val img = RectF()
        private val dens = ctx.resources.displayMetrics.density
        private val dim = Paint().apply { color = 0xA6000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * dens }
        private val thin = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1 * dens }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var mode = 0 // บิต: 1=ซ้าย 2=บน 4=ขวา 8=ล่าง, 16=ย้ายทั้งกรอบ
        private var lastX = 0f
        private var lastY = 0f

        private fun cropPx(): RectF = RectF(
            img.left + frac.left * img.width(), img.top + frac.top * img.height(),
            img.left + frac.right * img.width(), img.top + frac.bottom * img.height()
        )

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
            val dw = b.width * s; val dh = b.height * s
            img.set((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2)
            canvas.drawBitmap(b, null, img, bmpPaint)
            val r = cropPx()
            canvas.drawRect(img.left, img.top, img.right, r.top, dim)
            canvas.drawRect(img.left, r.bottom, img.right, img.bottom, dim)
            canvas.drawRect(img.left, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, img.right, r.bottom, dim)
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3; val y = r.top + r.height() * i / 3
                canvas.drawLine(x, r.top, x, r.bottom, thin)
                canvas.drawLine(r.left, y, r.right, y, thin)
            }
            canvas.drawRect(r, line)
            val hs = 7 * dens
            for (x in floatArrayOf(r.left, r.right)) for (y in floatArrayOf(r.top, r.bottom)) canvas.drawCircle(x, y, hs, handle)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val r = cropPx()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val tol = 28 * dens
                    mode = 0
                    if (abs(e.x - r.left) < tol && e.y in r.top - tol..r.bottom + tol) mode = mode or 1
                    if (abs(e.x - r.right) < tol && e.y in r.top - tol..r.bottom + tol) mode = mode or 4
                    if (abs(e.y - r.top) < tol && e.x in r.left - tol..r.right + tol) mode = mode or 2
                    if (abs(e.y - r.bottom) < tol && e.x in r.left - tol..r.right + tol) mode = mode or 8
                    if (mode == 0 && r.contains(e.x, e.y)) mode = 16
                    lastX = e.x; lastY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (img.width() <= 0) return true
                    val dx = (e.x - lastX) / img.width()
                    val dy = (e.y - lastY) / img.height()
                    lastX = e.x; lastY = e.y
                    val min = 0.08f
                    if (mode == 16) {
                        val mx = dx.coerceIn(-frac.left, 1f - frac.right)
                        val my = dy.coerceIn(-frac.top, 1f - frac.bottom)
                        frac.offset(mx, my)
                    } else {
                        if (mode and 1 != 0) frac.left = (frac.left + dx).coerceIn(0f, frac.right - min)
                        if (mode and 4 != 0) frac.right = (frac.right + dx).coerceIn(frac.left + min, 1f)
                        if (mode and 2 != 0) frac.top = (frac.top + dy).coerceIn(0f, frac.bottom - min)
                        if (mode and 8 != 0) frac.bottom = (frac.bottom + dy).coerceIn(frac.top + min, 1f)
                    }
                    invalidate()
                }
            }
            return true
        }
    }
}
