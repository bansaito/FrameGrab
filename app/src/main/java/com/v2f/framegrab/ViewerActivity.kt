package com.v2f.framegrab

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** ดูภาพแบบสตอรี่: แถบความคืบหน้าด้านบน, ภาพเต็มจอ ซูมด้วยนิ้วได้, แถบภาพย่อด้านล่าง */
class ViewerActivity : Activity() {

    private lateinit var image: ZoomImageView
    private lateinit var bars: SegmentBar
    private lateinit var count: TextView
    private lateinit var time: TextView
    private lateinit var playBtn: TextView
    private lateinit var top: LinearLayout
    private lateinit var bottom: HorizontalScrollView
    private lateinit var strip: LinearLayout
    private lateinit var empty: TextView

    private var frames: List<FrameStore.Frame> = emptyList()
    private var idx = 0
    private var uiVisible = true
    private var playing = false
    private var loadToken = 0

    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { // ขนาดเป็นไบต์
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val slide = object : Runnable {
        override fun run() {
            if (!playing) return
            if (idx < frames.size - 1) { show(idx + 1); main.postDelayed(this, 1200) } else setPlaying(false)
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun iconBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF121212.toInt()) }

        image = ZoomImageView(this).apply {
            onSwipe = { d -> setPlaying(false); show(idx + d) }
            onTap = { setUiVisible(!uiVisible) }
            onZoomChanged = { z -> setUiVisible(!z); if (z) setPlaying(false) }
        }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- แถบบน ----------
        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(28f), dp(8f), dp(18f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000)
            )
        }
        bars = SegmentBar(this)
        top.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f)).apply { bottomMargin = dp(10f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        count = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; paint.isFakeBoldText = true }
        time = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        titles.addView(count); titles.addView(time)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playBtn = iconBtn("▶") { setPlaying(!playing) }
        row.addView(playBtn)
        row.addView(iconBtn("📤") { shareAll() })
        row.addView(iconBtn("↗") { openWith() })
        // แตะ = บันทึกภาพนี้, กดค้าง = บันทึกทั้งหมด
        row.addView(iconBtn("💾") { saveCurrent() }.apply {
            setOnLongClickListener { saveAll(); true }
        })
        // แตะ = ลบภาพนี้, กดค้าง = ล้างทั้งหมด
        row.addView(iconBtn("🗑") { deleteCurrent() }.apply {
            setOnLongClickListener {
                setPlaying(false)
                FrameStore.confirmClear(this@ViewerActivity) { thumbs.evictAll(); reload(keepIndex = false) }
                true
            }
        })
        row.addView(iconBtn("✕") { finish() })
        top.addView(row)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---------- แถบภาพย่อด้านล่าง (เห็นภาพถัดไป) ----------
        bottom = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8f), dp(10f), dp(8f), dp(20f))
            clipToPadding = false
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000)
            )
        }
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(strip)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        empty = TextView(this).apply {
            text = "ยังไม่มีภาพ\nเปิดปุ่มลอยแล้วแตะ 📸 หรือ ⏺ เพื่อจับภาพ"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        // ต้องเรียกหลัง setContentView ไม่งั้น Android 11 บางเครื่องแอปเด้ง
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        reload(keepIndex = false)
    }

    override fun onPause() {
        setPlaying(false)
        super.onPause()
    }

    override fun onDestroy() {
        exec.shutdownNow()
        super.onDestroy()
    }

    private fun reload(keepIndex: Boolean) {
        frames = FrameStore.list(this)
        if (!keepIndex) idx = 0
        empty.visibility = if (frames.isEmpty()) View.VISIBLE else View.GONE
        top.visibility = if (frames.isEmpty()) View.GONE else View.VISIBLE
        buildStrip()
        if (frames.isNotEmpty()) show(idx.coerceIn(0, frames.size - 1)) else image.setImageBitmap(null)
    }

    private fun buildStrip() {
        strip.removeAllViews()
        val w = dp(48f); val h = dp(85f)
        for ((i, f) in frames.withIndex()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0xFF222222.toInt()); cornerRadius = dp(6f).toFloat() }
                clipToOutline = true
                setOnClickListener { setPlaying(false); show(i) }
            }
            strip.addView(iv, LinearLayout.LayoutParams(w, h).apply { marginEnd = dp(6f) })
            val key = f.file.name
            val cached = thumbs.get(key)
            if (cached != null) iv.setImageBitmap(cached)
            else exec.execute {
                val b = BitmapFactory.decodeFile(f.file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                if (b != null) {
                    thumbs.put(key, b)
                    main.post { iv.setImageBitmap(b) }
                }
            }
        }
    }

    private fun show(i: Int) {
        if (frames.isEmpty() || i !in frames.indices) return
        idx = i
        val f = frames[i]
        count.text = "${i + 1} / ${frames.size}"
        time.text = f.label
        bars.set(frames.size, i)
        for (k in 0 until strip.childCount) {
            val v = strip.getChildAt(k)
            v.alpha = if (k == i) 1f else 0.55f
            v.scaleY = if (k == i) 1.08f else 1f
            v.scaleX = v.scaleY
        }
        strip.getChildAt(i)?.let { c ->
            bottom.post { bottom.smoothScrollTo((c.left - bottom.width / 2 + c.width / 2).coerceAtLeast(0), 0) }
        }
        val token = ++loadToken
        exec.execute {
            val b = BitmapFactory.decodeFile(f.file.path)
            main.post { if (token == loadToken && b != null) image.setImageBitmap(b) }
        }
    }

    private fun setUiVisible(v: Boolean) {
        uiVisible = v
        val a = if (v) 1f else 0f
        top.animate().alpha(a).setDuration(180).start()
        bottom.animate().alpha(a).setDuration(180).start()
        top.isClickable = v
        bottom.visibility = if (v) View.VISIBLE else View.INVISIBLE
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        playBtn.text = if (p) "⏸" else "▶"
        main.removeCallbacks(slide)
        if (p) {
            if (idx >= frames.size - 1) show(0)
            main.postDelayed(slide, 1200)
        }
    }

    private fun saveCurrent() {
        if (frames.isEmpty()) return
        val f = frames[idx]
        val n = idx + 1
        exec.execute {
            val ok = FrameStore.saveOneToGallery(this, f)
            main.post {
                Toast.makeText(
                    this,
                    if (ok) "💾 บันทึกภาพที่ $n ลงแกลเลอรีแล้ว (กดค้าง 💾 = บันทึกทั้งหมด)" else "บันทึกไม่สำเร็จ",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveAll() {
        if (frames.isEmpty()) return
        val list = frames
        Toast.makeText(this, "กำลังบันทึก ${list.size} ภาพ…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val n = FrameStore.saveAllToGallery(this, list)
            main.post {
                Toast.makeText(this, "บันทึก $n ภาพลงแกลเลอรีแล้ว (โฟลเดอร์ Pictures/FrameGrab)", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** เปิดภาพนี้ด้วยแอปอื่น เช่น Chrome, แกลเลอรี, Google Lens */
    private fun openWith() {
        if (frames.isEmpty()) return
        val uri = FrameProvider.uriFor(frames[idx].file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri("frame", uri)
        try {
            startActivity(Intent.createChooser(view, "เปิดภาพด้วย…"))
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่พบแอปที่เปิดภาพได้", Toast.LENGTH_SHORT).show()
        }
    }

    /** แชร์ทุกภาพไปแอปอื่น (ไลน์, ไดรฟ์, แกลเลอรี ฯลฯ) */
    private fun shareAll() {
        if (frames.isEmpty()) return
        val uris = ArrayList(frames.map { FrameProvider.uriFor(it.file) })
        val clip = ClipData.newRawUri("frames", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/jpeg")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = clip
        try {
            startActivity(Intent.createChooser(send, "แชร์ ${uris.size} ภาพ…"))
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่พบแอปที่รับภาพได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCurrent() {
        if (frames.isEmpty()) return
        frames[idx].file.delete()
        val keep = idx
        reload(keepIndex = true)
        if (frames.isNotEmpty()) show(keep.coerceAtMost(frames.size - 1))
    }

    /** แถบความคืบหน้าแบบสตอรี่ (แยกเป็นขีด ถ้าภาพไม่เกิน 60 ภาพ) */
    private class SegmentBar(ctx: Context) : View(ctx) {
        private var n = 0
        private var cur = 0
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val r = RectF()

        fun set(total: Int, current: Int) { n = total; cur = current; invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (n <= 0) return
            val h = height.toFloat(); val rad = h / 2
            if (n > 60) {
                r.set(0f, 0f, width.toFloat(), h); canvas.drawRoundRect(r, rad, rad, off)
                r.set(0f, 0f, width * (cur + 1f) / n, h); canvas.drawRoundRect(r, rad, rad, on)
                return
            }
            val gap = h
            val segW = (width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val x = i * (segW + gap)
                r.set(x, 0f, x + segW, h)
                canvas.drawRoundRect(r, rad, rad, if (i <= cur) on else off)
            }
        }
    }
}
