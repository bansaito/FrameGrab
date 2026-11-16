package com.v2f.framegrab

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * แตกเฟรมจากไฟล์วิดีโอในเครื่อง (ไม่ต้องแคปหน้าจอ → ภาพเต็มความละเอียดของไฟล์ ตรงเฟรม)
 * เข้าได้ 2 ทาง: แชร์วิดีโอจากแกลเลอรีมาที่ FrameGrab / ปุ่ม "แตกเฟรมจากวิดีโอ" ในหน้าแอป (เลือกไฟล์)
 *  - เลื่อนแถบ = ดูภาพคร่าว ๆ (เฟรมหลัก เร็ว) ปล่อยนิ้ว = ภาพตรงเฟรม
 *  - ‹ › = ถอย/ไปทีละเฟรม, "จับเฟรมนี้" = เก็บ 1 ภาพ, "แตกทั้งคลิป" = เก็บทุก X วินาที
 * ภาพเก็บรวมกับภาพที่จับไว้ (ดู/บันทึกลงแกลเลอรีได้ในหน้าดูภาพเดิม) ตัดขอบดำของวิดีโอออกให้
 * ชื่อบัญชี: ถ้าเป็นวิดีโอที่ FrameGrab บันทึกเอง (ชื่อ-วว-ดด-ปปปป-0001.mp4) ใช้ชื่อนั้นต่อ
 */
class VideoFramesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("vframes").apply { start() }
    private val work = Handler(thread.looper)
    private var mmr: MediaMetadataRetriever? = null

    private var durMs = 0L
    private var frameCount = 0          // 0 = ไฟล์ไม่บอกจำนวนเฟรม → ใช้เวลาแทน
    private var fps = 30.0
    private var cur = 0                 // เฟรมที่แสดงอยู่ (index)
    private var crop: Rect? = null      // ตัดขอบดำ (หาจากหลายเฟรม)
    private var owner: String? = null
    private var shown: Bitmap? = null
    @Volatile private var busy = false  // กำลังแตกทั้งคลิป
    @Volatile private var cancel = false
    @Volatile private var want = -1     // เฟรมล่าสุดที่ขอให้แสดง (ขอซ้อนกัน = เอาอันล่าสุด)
    private var intervalIdx = 1

    private lateinit var img: ZoomImageView
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private lateinit var grabBtn: TextView
    private lateinit var allBtn: TextView
    private lateinit var everyBtn: TextView

    companion object {
        private const val REQ_PICK = 7
        private val INTERVALS = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()
    private fun btn(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12f).toFloat() }
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        intervalIdx = INTERVALS.indexOfFirst { it == Prefs.extractEvery(this) }.coerceAtLeast(0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        info = TextView(this).apply {
            setTextColor(0xFFB8BCCB.toInt()); textSize = 13f; setPadding(dp(14f), dp(12f), dp(14f), dp(6f))
            text = "กำลังเปิดวิดีโอ…"
        }
        root.addView(info)
        img = ZoomImageView(this)
        root.addView(FrameLayout(this).apply { addView(img, FrameLayout.LayoutParams(-1, -1)) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        seek = SeekBar(this).apply {
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { cur = p; show(p, rough = true) } }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { cur = s.progress; show(cur, rough = false) }
            })
        }
        root.addView(seek)
        fun row(vararg v: Pair<TextView, Float>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(10f), 0, dp(10f), dp(8f))
            v.forEachIndexed { i, (x, w) -> addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { if (i > 0) marginStart = dp(8f) }) }
        }
        val prev = btn("‹ เฟรมก่อน", 0xFF2A2D3A.toInt()) { step(-1) }
        val next = btn("เฟรมถัดไป ›", 0xFF2A2D3A.toInt()) { step(1) }
        grabBtn = btn("จับเฟรมนี้", 0xFF4F46E5.toInt()) { grabOne() }
        root.addView(row(prev to 1f, grabBtn to 1.4f, next to 1f))
        everyBtn = btn("", 0xFF2A2D3A.toInt()) {
            if (busy) return@btn
            intervalIdx = (intervalIdx + 1) % INTERVALS.size
            Prefs.setExtractEvery(this, INTERVALS[intervalIdx]); updateEvery()
        }
        allBtn = btn("แตกทั้งคลิป", 0xFF1F3A2E.toInt()) { if (busy) cancel = true else askExtractAll() }
        root.addView(row(everyBtn to 1f, allBtn to 1f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
        setContentView(root)
        updateEvery()
        setEnabledUi(false)

        val uri = incomingUri(intent)
        if (uri != null) open(uri) else pick()
    }

    private fun incomingUri(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> i.data
        else -> null
    }

    private fun pick() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
        try { startActivityForResult(i, REQ_PICK) } catch (e: Exception) { toast("เปิดหน้าเลือกวิดีโอไม่ได้"); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_PICK) return
        val u = data?.data
        if (res != RESULT_OK || u == null) { finish(); return }
        open(u)
    }

    private var uri: Uri? = null
    private fun open(uri: Uri) {
        this.uri = uri
        owner = ownerFromName(uri)
        work.post {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                if (durMs <= 0) throw IllegalStateException("ไม่ใช่ไฟล์วิดีโอ")
                if (frameCount > 0) fps = frameCount * 1000.0 / durMs
                mmr = r
                crop = findCrop(r)
                main.post {
                    seek.max = maxOf(0, lastIndex())
                    setEnabledUi(true)
                    show(0, rough = false)
                }
            } catch (e: Exception) {
                main.post { toast("เปิดวิดีโอนี้ไม่ได้: ${e.message ?: ""}"); finish() }
            }
        }
    }

    // ชื่อวิดีโอที่ FrameGrab ตั้งให้: ชื่อบัญชี-วว-ดด-ปปปป-0001.mp4 → ใช้ชื่อบัญชีเดิมต่อ
    private fun ownerFromName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getString(0)
        }?.let { n -> Regex("""^(.+?)-\d{2}-\d{2}-\d{4}-\d{4}\.\w+$""").matchEntire(n)?.groupValues?.get(1) }
            ?.takeIf { !it.equals("FrameGrab", ignoreCase = true) }
    } catch (e: Exception) { null }

    private fun lastIndex() = if (frameCount > 0) frameCount - 1 else (durMs / 33).toInt()
    private fun timeOf(i: Int): Long = if (frameCount > 0) (i * 1000.0 / fps).toLong() else i * 33L

    /** ขอบดำของวิดีโอ (เช่น วิดีโอแนวนอนที่ใส่ขอบดำบน-ล่าง) — รวมพื้นที่ภาพจาก 3 จุดของคลิป กันฉากมืดถูกตัดผิด */
    private fun findCrop(r: MediaMetadataRetriever): Rect? {
        var out: Rect? = null
        for (f in doubleArrayOf(0.25, 0.5, 0.75)) {
            val b = r.getFrameAtTime((durMs * f * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
            val t = Cropper.trimDark(b, Rect(0, 0, b.width, b.height))
            if (out == null) out = Rect(t) else out.union(t)
            b.recycle()
        }
        return out
    }

    /** ภาพของเฟรม i: rough = เฟรมหลักใกล้ ๆ (เร็ว ใช้ตอนเลื่อนแถบ), ไม่ rough = ตรงเฟรม */
    private fun frameAt(i: Int, rough: Boolean): Bitmap? {
        val r = mmr ?: return null
        val us = timeOf(i) * 1000
        return try {
            when {
                rough -> r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frameCount > 0 -> try { r.getFrameAtIndex(i) } catch (e: Exception) { null }
                    ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                else -> r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } catch (e: Exception) { null }
    }

    private fun cropped(b: Bitmap): Bitmap {
        val c = crop ?: return b
        if (c.left == 0 && c.top == 0 && c.right == b.width && c.bottom == b.height) return b
        if (c.right > b.width || c.bottom > b.height || c.width() < 16 || c.height() < 16) return b
        val o = Cropper.apply(b, c)
        if (o !== b) b.recycle()
        return o
    }

    private fun show(i: Int, rough: Boolean) {
        want = i
        updateInfo(i)
        work.post {
            if (want != i || busy) return@post
            val b = frameAt(i, rough)?.let { cropped(it) } ?: return@post
            main.post {
                if (isDestroyed) { b.recycle(); return@post }
                val old = shown
                shown = b
                img.setImageBitmap(b)
                if (old != null && old !== b) old.recycle()
            }
        }
    }

    private fun step(d: Int) {
        if (busy || mmr == null) return
        cur = (cur + d).coerceIn(0, lastIndex())
        seek.progress = cur
        show(cur, rough = false)
    }

    private fun fmt(ms: Long) = String.format(Locale.US, "%02d:%06.3f", ms / 60000, (ms % 60000) / 1000.0)
    private fun updateInfo(i: Int) {
        info.text = "${fmt(timeOf(i))} / ${fmt(durMs)}" + (if (frameCount > 0) " · เฟรม ${i + 1}/$frameCount" else "") +
            (owner?.let { " · $it" } ?: "")
    }
    private fun updateEvery() {
        val s = INTERVALS[intervalIdx]
        everyBtn.text = "ทุก ${if (s < 1) s.toString().removePrefix("0") else s.toInt().toString()} วิ" +
            (if (durMs > 0) " (~${(durMs / 1000.0 / s).toInt() + 1} ภาพ)" else "")
    }

    private fun setEnabledUi(on: Boolean) {
        listOf(grabBtn, everyBtn).forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.45f }
        seek.isEnabled = on
        allBtn.isEnabled = on || busy
        allBtn.alpha = if (on || busy) 1f else 0.45f
    }

    private fun saveFrame(b: Bitmap, i: Int): java.io.File = saveFrameMs(b, timeOf(i))
    private fun saveFrameMs(b: Bitmap, ms: Long): java.io.File {
        val f = FrameStore.save(this, b, 'c', ms)
        // ไม่มีชื่อ: ใส่ "-" ไว้ ไม่ให้ไปใช้ชื่อของชุดอัดต่อเนื่องก่อนหน้า (ภาพ 'c' ใช้ชื่อชุดเป็นค่าตั้งต้น)
        FrameStore.setOwner(this, f, owner ?: "-")
        return f
    }

    private fun grabOne() {
        if (busy || mmr == null) return
        val i = cur
        work.post {
            val b = frameAt(i, rough = false)?.let { cropped(it) }
            if (b == null) { main.post { toast("ดึงเฟรมนี้ไม่ได้") }; return@post }
            val f = try { saveFrame(b, i) } catch (e: Exception) { null } finally { b.recycle() }
            val saved = f != null && Prefs.autoSaveSingle(this) && FrameStore.saveFileToGallery(this, f, FrameStore.count(this))
            main.post {
                toast(if (f == null) "เก็บภาพไม่สำเร็จ" else if (saved) "บันทึกลงแกลเลอรีแล้ว" else "เก็บแล้ว (${FrameStore.count(this)} ภาพ)")
            }
        }
    }

    private fun askExtractAll() {
        if (mmr == null) return
        val n = FrameStore.count(this)
        if (n == 0) { extractAll(); return }
        AlertDialog.Builder(this)
            .setMessage("มีภาพที่จับไว้แล้ว $n ภาพ ล้างก่อนแตกคลิปนี้ไหม")
            .setPositiveButton("ล้างก่อน") { _, _ -> FrameStore.clear(this); extractAll() }
            .setNeutralButton("เก็บไว้ด้วย") { _, _ -> extractAll() }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun extractAll() {
        val every = INTERVALS[intervalIdx]
        val idxs = ArrayList<Int>()
        var t = 0.0
        while (t * 1000 <= durMs) {
            val i = if (frameCount > 0) (t * fps).roundToInt() else (t * 1000 / 33).roundToInt()
            if (i <= lastIndex() && (idxs.isEmpty() || idxs.last() != i)) idxs.add(i)
            t += every
        }
        busy = true; cancel = false
        setEnabledUi(false)
        allBtn.text = "หยุด"
        val targets = idxs.map { timeOf(it) }
        work.post {
            val doneN = java.util.concurrent.atomic.AtomicInteger(0)
            var first: String? = null
            val start = System.currentTimeMillis()
            fun progress(b: Bitmap?) {
                val done = doneN.get()
                val sec = (System.currentTimeMillis() - start) / 1000.0
                main.post {
                    allBtn.text = "หยุด ($done/${idxs.size})"
                    info.text = "กำลังแตกเฟรม $done/${idxs.size} · ${String.format(Locale.US, "%.1f", sec)} วิ"
                    if (b != null) { val old = shown; shown = b; img.setImageBitmap(b); if (old != null && old !== b) old.recycle() }
                }
            }
            fun keep(raw: Bitmap, ms: Long) {
                val b = cropped(raw)
                try { val f = saveFrameMs(b, ms); if (first == null) first = f.name; doneN.incrementAndGet() } catch (e: Exception) { }
                // แสดงภาพล่าสุดเป็นระยะ (ไม่ทุกภาพ จะได้ไม่ช้า)
                if (doneN.get() % 5 == 1) progress(b) else { b.recycle(); progress(null) }
            }
            // 1) แบบเร็ว: ถอดรหัสต่อเนื่องครั้งเดียว + บันทึก JPEG อีกเธรดพร้อมกัน
            var fastOk = false
            val u = uri
            if (u != null) {
                val q = java.util.concurrent.ArrayBlockingQueue<Pair<Bitmap, Long>>(3)
                val end = Pair(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), -1L)
                val saver = Thread {
                    while (true) {
                        val (b, ms) = q.take()
                        if (ms < 0) break
                        if (cancel) { b.recycle(); continue }
                        keep(b, ms)
                    }
                }.apply { start() }
                try {
                    FastExtractor(this, u).run(targets) { b, ms ->
                        if (cancel) { b.recycle(); false } else { q.put(b to ms); true }
                    }
                    fastOk = true
                } catch (e: Throwable) { }
                q.put(end); saver.join(); end.first.recycle()
            }
            // 2) แบบเดิม (ทีละเฟรม) ถ้าแบบเร็วใช้กับไฟล์นี้ไม่ได้
            if (!fastOk && doneN.get() == 0) {
                for (i in idxs) {
                    if (cancel) break
                    val b = frameAt(i, rough = false) ?: continue
                    keep(b, timeOf(i))
                }
            }
            val done = doneN.get()
            main.post {
                busy = false
                allBtn.text = "แตกทั้งคลิป"
                setEnabledUi(true)
                updateInfo(cur)
                if (done == 0) { toast("ดึงเฟรมไม่ได้"); return@post }
                toast("ได้ $done ภาพ" + if (cancel) " (หยุดกลางคัน)" else "")
                // เปิดหน้าดูภาพ ที่ภาพแรกของคลิปนี้
                Prefs.setViewerLast(this, first)
                startActivity(Intent(this, ViewerActivity::class.java))
            }
        }
    }

    override fun onResume() { super.onResume(); CaptureService.setOwnUiHidden(this, true) }   // ไม่ให้ปุ่มลอยทับหน้านี้
    override fun onPause() { CaptureService.setOwnUiHidden(this, false); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        cancel = true
        work.post { try { mmr?.release() } catch (e: Exception) { }; mmr = null }
        thread.quitSafely()
        super.onDestroy()
    }
}
