package com.v2f.framegrab

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * บริการเบื้องหน้า: จับภาพหน้าจอ (MediaProjection) + ปุ่มลอยเหนือทุกแอป
 * - แตะปุ่มลอย = เปิดเมนู: จับภาพ / อัดต่อเนื่อง / ตั้งค่า / ดูภาพ / ปิด
 * - ก่อนจับภาพจะซ่อนปุ่มลอยเสมอ เพื่อไม่ให้ติดในภาพ
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.v2f.framegrab.START"
        const val ACTION_STOP_REC = "com.v2f.framegrab.STOP_REC"
        const val ACTION_QUIT = "com.v2f.framegrab.QUIT"
        /** ซ่อนปุ่มลอย/กรอบ แต่ยังจับหน้าจอค้างไว้ (เปิดใหม่ไม่ต้องกดยืนยัน) */
        const val ACTION_PARK = "com.v2f.framegrab.PARK"
        const val ACTION_UNPARK = "com.v2f.framegrab.UNPARK"
        const val EXTRA_EXIT_APP = "exitApp"
        /** หน้าดูภาพ/หน้าลากกรอบของแอปเปิดอยู่ → ซ่อนปุ่มลอยและกรอบ */
        const val ACTION_OWN_UI = "com.v2f.framegrab.OWN_UI"
        const val EXTRA_HIDE = "hide"

        /** เรียกจากหน้าจอของแอป (ปลอดภัยแม้ปุ่มลอยไม่ได้เปิดอยู่) */
        fun setOwnUiHidden(c: android.content.Context, hide: Boolean) {
            if (!running) return
            try {
                c.startService(Intent(c, CaptureService::class.java).setAction(ACTION_OWN_UI).putExtra(EXTRA_HIDE, hide))
            } catch (e: Exception) { }
        }
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7
        private const val MAX_UNLIMITED_MS = 5 * 60 * 1000L
        @Volatile var running = false
        /** ซ่อนปุ่มลอยอยู่ (การจับหน้าจอยังค้างไว้) */
        @Volatile var parked = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val imgThread = HandlerThread("fg-img").apply { start() }
    private val workThread = HandlerThread("fg-work").apply { start() }
    private val imgHandler = Handler(imgThread.looper)
    private val work = Handler(workThread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val lock = Any()
    private var latest: Image? = null
    @Volatile private var latestAt = 0L
    private var quitting = false

    // ---------- ปุ่มลอย / เมนู / กรอบบนจอ ----------
    private var root: FrameLayout? = null          // หน้าต่างปุ่มลอย
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private var menuWin: ScrollView? = null        // หน้าต่างเมนู (แยกจากปุ่ม ขนาดพอดีเนื้อหา)
    private lateinit var menuLp: WindowManager.LayoutParams
    private lateinit var menuBox: LinearLayout
    private var menuPage = 0                       // 0 = เมนูหลัก, 1 = เลือกกรอบ
    private var guide: GuideView? = null           // กรอบที่แสดงบนจอ (กดทะลุได้)
    private lateinit var guideLp: WindowManager.LayoutParams
    private var editBar: LinearLayout? = null      // แถบปุ่มตอนปรับกรอบด้วยมือ
    @Volatile private var guideEditing = false
    private var editBackup: android.graphics.RectF? = null
    // จุดจับบนกรอบ: มุมซ้ายบน, ขวาบน, ซ้ายล่าง, ขวาล่าง, ปุ่มย้าย
    private val handles = ArrayList<Pair<HandleView, WindowManager.LayoutParams>>()
    private val handleModes = intArrayOf(1 or 2, 4 or 2, 1 or 8, 4 or 8, 16, 2, 8, 1, 4)  // มุม 4, ย้าย, ขอบ บน/ล่าง/ซ้าย/ขวา
    @Volatile private var draggingGuide = false
    @Volatile private var appHidden = false     // ไม่ได้อยู่ใน TikTok/IG/FB → ซ่อนทุกอย่าง
    @Volatile private var holdHidden = false    // กำลังซ่อนเพื่อจับภาพ ห้ามโผล่กลับเอง
    @Volatile private var inOwnApp = false      // กำลังเปิดหน้าแอป FrameGrab เอง
    @Volatile private var ownUiHidden = false   // หน้าดูภาพ/หน้าลากกรอบเปิดอยู่
    // ลากกรอบชั่วคราว (กรอบแบบอัตโนมัติ/สัดส่วน): ใช้กับวิดีโอนี้ พอเปลี่ยนวิดีโอจะกลับไปตามวิดีโอเอง
    @Volatile private var overrideRect: android.graphics.RectF? = null   // พิกัดจอ
    @Volatile private var lastDetected: android.graphics.RectF? = null   // กรอบที่แสดงล่าสุด (พิกัดจอ)
    // ตรวจกรอบแค่ 3 ครั้งต่อวิดีโอ แล้วล็อกไว้ (กันกรอบกระตุกรัวๆ) จนกว่าภาพจะเปลี่ยนเป็นวิดีโออื่น
    private val samples = ArrayList<android.graphics.RectF?>()
    @Volatile private var locked = false
    @Volatile private var lockedRect: android.graphics.RectF? = null
    private var prevThumb: IntArray? = null        // ภาพย่อรอบก่อน ใช้ดูว่าภาพเปลี่ยนกะทันหัน (= สตอรี่ใหม่)
    @Volatile private var overrideAt = 0L           // เวลาที่ลากกรอบเอง
    @Volatile private var jumpAfterDrag = false     // ลากแล้วเจอภาพเปลี่ยนกะทันหันหรือยัง
    @Volatile private var feedHidden = false    // ไม่ได้อยู่หน้าสตอรี่ และตั้งให้แสดงเฉพาะสตอรี่
    private var storyMiss = 0                   // นับครั้งที่ไม่เจอหน้าสตอรี่ติดกัน (กันกะพริบ)
    @Volatile private var capturedAppVisible = true // Android 14+ อัด "แอปเดียว": แอปนั้นอยู่บนจอไหม
    @Volatile private var fgLabel: String? = null   // แอปที่ตรวจเจอ (แสดงในแจ้งเตือน)
    private var lastBase: android.graphics.RectF? = null // พื้นที่วิดีโอล่าสุด (พิกัดจอ)
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("cropPreset", "showGuide", "cropL", "cropT", "cropR", "cropB")) { work.post { resetLock() }; main.post { applyGuidePrefs() } }
    }
    private val locBuf = IntArray(2)
    private var menuOpen = false
    private var lastOutsideClose = 0L
    private var screenW = 0
    private var screenH = 0
    private val bubbleSize: Int get() = dp(56f)
    @Volatile private var videoRatio: String? = null      // สัดส่วนวิดีโอที่ตรวจได้ล่าสุด
    private val fadeRunnable = Runnable { if (!menuOpen && !recording) root?.alpha = 0.45f }

    // ---------- การอัดต่อเนื่อง ----------
    private val loop = LoopDetector()
    @Volatile private var loopFeeding = false
    @Volatile private var seamFeeding = false
    private val seam = SeamDetector()
    private val storyBar = StoryBar()
    private var recRect: android.graphics.Rect? = null
    private var recBmpW = 0
    private var recBmpH = 0
    private var recentlyRecorded = false
    @Volatile private var stopOverlaps = false          // ปุ่ม ■ อยู่ในกรอบ (กรอบเต็มจอ)
    private var savedBubblePos: Pair<Int, Int>? = null
    @Volatile private var recording = false
    @Volatile private var recStart = 0L
    private var recCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        Prefs.migrateCustomOnce(this)
        super.onCreate()
        Crash.install(this)
        wm = getSystemService(WindowManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ปุ่มลอยจับเฟรม", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP_REC -> stopRecording(dropLastMs = 1500) // ตัดภาพตอนดึงแถบแจ้งเตือนลงมา
            ACTION_OWN_UI -> {
                ownUiHidden = intent.getBooleanExtra(EXTRA_HIDE, false)
                main.removeCallbacks(appTick)
                main.post(appTick)
            }
            ACTION_QUIT -> quit(exitApp = intent.getBooleanExtra(EXTRA_EXIT_APP, true))
            ACTION_PARK -> park(exitApp = intent.getBooleanExtra(EXTRA_EXIT_APP, false))
            ACTION_UNPARK -> unpark()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) { swapProjection(intent); return }
        // Android 14+: ต้องเริ่มบริการเบื้องหน้าแบบ mediaProjection ก่อนขอ MediaProjection
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) { quit(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) { toast("เริ่มจับภาพหน้าจอไม่ได้"); quit(); return }
        attach(p)
        AppWatch.reset()
        addOverlay()
        parked = false
        running = true
    }

    /** ผูก MediaProjection ใหม่: ตั้ง callback + เริ่มรับภาพ */
    private fun attach(p: MediaProjection) {
        projection = p
        capturedAppVisible = true
        p.registerCallback(object : MediaProjection.Callback() {
            // ถูกหยุดจากระบบ (ไม่ใช่ตอนเราเปลี่ยนแอปที่แชร์) → ปิดทุกอย่าง
            override fun onStop() { main.post { if (projection === p) quit() } }
            // Android 14+: อนุญาตแบบ "แอปเดียว" แล้วออกจากแอปนั้น → ซ่อนปุ่มลอยเอง (ไม่ต้องใช้สิทธิ์เพิ่ม)
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                capturedAppVisible = isVisible
                main.removeCallbacks(appTick)
                main.post(appTick)
            }
        }, main)
        setupDisplay(p)
    }

    /** เปลี่ยนแอปที่แชร์: ได้สิทธิ์ใหม่มาแล้ว → ปิดของเดิม ใช้ของใหม่ (ปุ่มลอย/กรอบยังอยู่) */
    private fun swapProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        // เรียกผ่าน startForegroundService + สิทธิ์ใหม่ → ต้องยืนยันบริการเบื้องหน้าซ้ำ
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (data == null) { unpark(); return }
        if (recording) stopRecording(dropLastMs = 0)
        val old = projection
        projection = null                       // กัน onStop ของอันเก่าสั่งปิดทุกอย่าง
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        try { old?.stop() } catch (e: Exception) { }
        val p = try { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) { toast("เปลี่ยนแอปที่แชร์ไม่ได้"); quit(false); return }
        attach(p)
        parked = false
        resetLock()
        main.removeCallbacks(appTick); main.post(appTick)
        updateNotification()
        toast("เปลี่ยนแอปที่แชร์แล้ว")
    }

    // ---------- ซ่อนปุ่มลอยแต่จับหน้าจอค้างไว้ ----------
    /** ✕ = ซ่อนปุ่มลอย กรอบ และแอป แต่ไม่ปิดการจับหน้าจอ เปิดใหม่ได้ทันทีไม่ต้องกดยืนยัน */
    private fun park(exitApp: Boolean) {
        if (projection == null) { quit(exitApp); return }
        if (recording) stopRecording(dropLastMs = 0)
        if (guideEditing) finishGuideEdit(false)
        closeMenu()
        parked = true
        display?.surface = null   // หยุดส่งภาพระหว่างซ่อน (ประหยัดแบต)
        main.removeCallbacks(appTick); main.post(appTick)
        updateNotification()
        if (exitApp) {
            try {
                getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
            } catch (e: Exception) { }
        }
    }

    private fun unpark() {
        if (!parked) { main.removeCallbacks(appTick); main.post(appTick); return }
        parked = false
        display?.surface = reader?.surface
        main.removeCallbacks(appTick); main.post(appTick)
        updateNotification()
    }

    // ---------- จับภาพหน้าจอ ----------
    private fun setupDisplay(p: MediaProjection) {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        // จำกัดด้านกว้างไม่เกิน 1080 เพื่อให้บันทึกภาพได้เร็วพอสำหรับทุก 0.25 วินาที
        val scale = if (m.widthPixels > 1080) 1080f / m.widthPixels else 1f
        val w = (m.widthPixels * scale).roundToInt() / 2 * 2
        val h = (m.heightPixels * scale).roundToInt() / 2 * 2
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                // ตรวจการวนคลิปจากทุกเฟรม (อ่านแค่ 576 จุด เร็วมาก)
                if (loopFeeding) {
                    try { loop.feed(SystemClock.uptimeMillis() - recStart, LoopDetector.signature(img)) } catch (e: Exception) { }
                }
                // จับจังหวะเลื่อนสตอรี่ (ทุกเฟรม เฉพาะในกรอบที่อัด)
                val rr = recRect
                if (seamFeeding && rr != null) {
                    val t = SystemClock.uptimeMillis() - recStart
                    // จับจังหวะเลื่อนสตอรี่ (SeamDetector) ปิดไว้ก่อน ใช้แค่แถบด้านบน
                    try { storyBar.feed(t, img) } catch (e: Exception) { }
                    // เจอแล้วหยุดทันที ไม่ต้องรอรอบจับภาพถัดไป
                    val sAt = seam.seamAtMs; val bAt = storyBar.advancedAtMs
                    if (sAt >= 0 || bAt >= 0) {
                        seamFeeding = false
                        val cut = if (sAt >= 0) (sAt - 100).coerceAtLeast(0) else (bAt - 150).coerceAtLeast(0)
                        val why = if (sAt >= 0) "เลื่อนไปสตอรี่ใหม่ หยุดอัดอัตโนมัติ" else "จบสตอรี่ หยุดอัดอัตโนมัติ"
                        main.post { stopRecording(0, cutAtMs = cut, reason = why) }
                    }
                }
                // คลิปวนกลับ: หยุดทันทีเช่นกัน
                if (loopFeeding) {
                    val cut = loop.loopAtMs
                    if (cut >= 0) { loopFeeding = false; main.post { stopRecording(0, cutAtMs = cut, reason = "คลิปจบแล้ว หยุดอัดอัตโนมัติ") } }
                }
                synchronized(lock) {
                    latest?.close()
                    latest = img
                    latestAt = SystemClock.uptimeMillis()
                }
            }
        }, imgHandler)
        reader = r
        display = p.createVirtualDisplay(
            "FrameGrab", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    /** แปลงภาพล่าสุดเป็น Bitmap (เรียกจากเธรดทำงานเท่านั้น) */
    private fun grabBitmap(): Bitmap? = synchronized(lock) {
        val img = latest ?: return null
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rowPad = plane.rowStride - ps * img.width
            val full = Bitmap.createBitmap(img.width + rowPad / ps, img.height, Bitmap.Config.ARGB_8888)
            buf.rewind()
            full.copyPixelsFromBuffer(buf)
            if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } catch (e: Exception) {
            null
        }
    }

    /** รอภาพใหม่ที่มาหลังเวลา after (ใช้หลังซ่อนปุ่มลอย) แล้วแปลงเป็น Bitmap */
    private fun grabFresh(after: Long, timeoutMs: Long = 500): Bitmap? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (latestAt <= after && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
        return grabBitmap()
    }

    private fun captureOne() {
        if (recording) return
        closeMenu()
        hideOverlay()
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            val bmp = grabFresh(hiddenAt + 60)
            var ok = false
            var saved = false
            if (bmp != null) {
                val now = java.util.Calendar.getInstance()
                val msOfDay = ((now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)) * 60 +
                    now.get(java.util.Calendar.SECOND)) * 1000L + now.get(java.util.Calendar.MILLISECOND)
                updateGuideFrom(bmp)
                val out = Cropper.apply(bmp, cropRectFor(bmp))
                try {
                    val f = FrameStore.save(this, out, 's', msOfDay)
                    ok = true
                    // บันทึกลงแกลเลอรีอัตโนมัติ (ปิดได้ในแอป)
                    if (Prefs.autoSaveSingle(this)) saved = FrameStore.saveFileToGallery(this, f, FrameStore.count(this))
                } catch (e: Exception) { }
                if (out !== bmp) out.recycle()
                bmp.recycle()
            }
            main.post {
                showOverlay()
                flashBubble(if (ok) "✓" else "!")
                toast(when {
                    !ok -> "จับภาพไม่สำเร็จ"
                    saved -> "📸 บันทึกลงแกลเลอรีแล้ว (Pictures/FrameGrab)"
                    else -> "📸 จับภาพแล้ว (${FrameStore.count(this)})"
                })
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        closeMenu()
        FrameStore.clear(this) // อัดคลิปใหม่ ล้างภาพเก่าทันที
        recording = true
        recCount = 0
        recRect = null
        stopOverlaps = false
        val showStop = Prefs.keepStopButton(this)
        hideOverlay() // ซ่อนก่อน เพื่อวัดกรอบจากภาพที่ไม่มีปุ่มของเรา
        val intervalMs = (Prefs.interval(this) * 1000).roundToInt().toLong().coerceAtLeast(100)
        val maxSec = Prefs.maxSeconds(this)
        val maxMs = if (maxSec == 0) MAX_UNLIMITED_MS else maxSec * 1000L
        val autoStop = Prefs.autoStop(this)
        val stopOnStory = Prefs.stopOnNewStory(this)
        loop.reset()
        toast("⏺ เริ่มอัด ทุก ${Prefs.intervalLabel(Prefs.interval(this))} วิ · " +
            if (showStop) "แตะ ■ เพื่อหยุด" else "หยุดได้ที่แถบแจ้งเตือน")
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            // รอให้ปุ่มลอยหายจากจอก่อนเริ่มนับเวลา
            val end = hiddenAt + 500
            while (latestAt <= hiddenAt + 60 && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
            // วัดกรอบจากภาพแรก (ใช้ทั้งคลิป) แล้ววางปุ่ม ■ ไว้นอกกรอบถ้าทำได้
            grabBitmap()?.let { b ->
                recRect = cropRectFor(b); recBmpW = b.width; recBmpH = b.height
                b.recycle()
            }
            if (showStop) {
                val latch = java.util.concurrent.CountDownLatch(1)
                main.post { placeStopButton(); showStopButton(); latch.countDown() }
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            recStart = SystemClock.uptimeMillis()
            loopFeeding = autoStop
            seam.reset()
            storyBar.reset()
            seamFeeding = stopOnStory
            work.post(object : Runnable {
                override fun run() {
                    if (!recording) return
                    val el = SystemClock.uptimeMillis() - recStart
                    if (el >= maxMs) { main.post { stopRecording(0, reason = "ครบเวลาที่ตั้งไว้") }; return }
                    // คลิปวนกลับมาจุดเริ่มอัดแล้ว (= เล่นครบหนึ่งรอบ)
                    val cut = loop.loopAtMs
                    if (autoStop && cut >= 0) {
                        main.post { stopRecording(0, cutAtMs = cut, reason = "คลิปจบแล้ว หยุดอัดอัตโนมัติ") }
                        return
                    }
                    // เลื่อนไปสตอรี่ถัดไป → หยุด ตัดภาพตั้งแต่เริ่มเลื่อนทิ้ง
                    val sAt = seam.seamAtMs
                    if (stopOnStory && sAt >= 0) {
                        main.post { stopRecording(0, cutAtMs = (sAt - 100).coerceAtLeast(0), reason = "เลื่อนไปสตอรี่ใหม่ หยุดอัดอัตโนมัติ") }
                        return
                    }
                    val bmp = grabBitmap()
                    if (bmp != null) {
                        // ใช้กรอบเดียวกันทั้งคลิป (คำนวณจากภาพแรก) ภาพจะได้ขนาดเท่ากันทุกภาพ
                        val rect = recRect ?: cropRectFor(bmp).also { recRect = it }
                        // ขึ้นสตอรี่ใหม่ (ภาพในกรอบเปลี่ยนทั้งหมดกะทันหัน) → หยุด ไม่เก็บภาพของสตอรี่ถัดไป
                        // (ทดสอบ: ในคลิปเดียวกันต่างกัน ≤14, คนละภาพ ≥44)
                        val out = Cropper.apply(bmp, rect)
                        try { FrameStore.save(this@CaptureService, out, 'c', el, 90); recCount++ } catch (e: Exception) { }
                        if (out !== bmp) out.recycle()
                        bmp.recycle()
                    }
                    if (recCount % 4 == 0) main.post { updateNotification() }
                    // ข้ามช่องที่ช้าเกินไป แทนการวิ่งไล่ตาม
                    val slot = (SystemClock.uptimeMillis() - recStart) / intervalMs + 1
                    work.postAtTime(this, recStart + slot * intervalMs)
                }
            })
        }
        updateNotification()
    }

    /** ภาพย่อ 12x12 (ความสว่าง) ภายในกรอบที่อัด */
    private fun thumbIn(b: Bitmap, r: android.graphics.Rect): IntArray = IntArray(144) { k ->
        val i = k % 12; val j = k / 12
        val x = (r.left + r.width() * (0.05f + 0.9f * i / 11)).toInt().coerceIn(0, b.width - 1)
        val y = (r.top + r.height() * (0.05f + 0.9f * j / 11)).toInt().coerceIn(0, b.height - 1)
        val p = b.getPixel(x, y)
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    private fun stopRecording(dropLastMs: Long, cutAtMs: Long = -1, reason: String? = null) {
        if (!recording) return
        recording = false
        loopFeeding = false
        seamFeeding = false
        val el = SystemClock.uptimeMillis() - recStart
        // คืนปุ่มลอยทันที ไม่ต้องรองานบันทึกไฟล์ที่ค้างอยู่
        restoreBubble()
        stopOverlaps = false
        savedBubblePos?.let { (x, y) -> lp.x = x; lp.y = y }
        savedBubblePos = null
        showOverlay()
        updateNotification()
        work.post {
            if (dropLastMs > 0) FrameStore.dropAfter(this, el - dropLastMs)
            if (cutAtMs >= 0) FrameStore.dropAfter(this, cutAtMs - 1) // ตัดภาพที่เกินจุดจบ
            val n = FrameStore.count(this)
            main.post {
                toast((reason?.let { "$it · " } ?: "") + "ได้ $n ภาพ")
                if (n > 0) { recentlyRecorded = true; openViewer(); recentlyRecorded = false }
            }
        }
    }

    private fun openViewer() {
        closeMenu()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ViewerActivity.EXTRA_FROM_START, recentlyRecorded)
        )
    }

    // ---------- ปุ่มลอย / เมนู / กรอบบนจอ ----------
    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun overlayParams(w: Int, h: Int, extra: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extra,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        // กรอบบนจอ: เต็มจอ กดทะลุได้ อยู่ใต้ปุ่มลอย
        val g = GuideView(this)
        val glp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        g.visibility = View.INVISIBLE
        syncHandles()
        wm.addView(g, glp)
        guide = g
        guideLp = glp
        addHandles()

        // ปุ่มลอย
        val r = FrameLayout(this)
        bubble = TextView(this).apply {
            text = "🎞️"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE64F46E5.toInt()) }
            elevation = dp(6f).toFloat()
        }
        r.addView(bubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER))
        val win = bubbleSize + dp(8f)
        lp = overlayParams(win, win).apply {
            val sx = Prefs.bubbleX(this@CaptureService)
            val sy = Prefs.bubbleY(this@CaptureService)
            x = if (sx >= 0) sx.coerceIn(0, max(0, screenW - win)) else screenW - win
            y = if (sy >= 0) sy.coerceIn(0, max(0, screenH - win)) else screenH / 3
        }

        // เมนู (หน้าต่างแยก กำหนดขนาดเองให้พอดีตัวหนังสือ ไม่ถูกบีบ)
        menuBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        menuWin = ScrollView(this).apply {
            background = pill(0xF2181A22.toInt(), dp(14f).toFloat())
            isVerticalScrollBarEnabled = false
            addView(menuBox)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    lastOutsideClose = SystemClock.uptimeMillis(); closeMenu(); true
                } else false
            }
        }
        menuLp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var longFired = false
        // กดค้างปุ่มลอย = เริ่มอัดทันที
        val longPress = Runnable {
            if (!moved && !recording) {
                longFired = true
                bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                closeMenu()
                startRecording()
            }
        }
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    longFired = false
                    wake()
                    if (!recording) main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && !longFired && (abs(dx) > slop || abs(dy) > slop)) { moved = true; main.removeCallbacks(longPress); closeMenu() }
                    if (moved) {
                        lp.x = (startX + dx).roundToInt().coerceIn(0, max(0, screenW - win))
                        lp.y = (startY + dy).roundToInt().coerceIn(0, max(0, screenH - win))
                        root?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (longFired) {
                        // เพิ่งเริ่มอัดจากการกดค้าง: ปล่อยนิ้วไม่ต้องทำอะไร
                    } else if (moved) {
                        lp.x = if (lp.x + win / 2 < screenW / 2) 0 else screenW - win // ชิดขอบ
                        root?.let { wm.updateViewLayout(it, lp) }
                        Prefs.setBubble(this, lp.x, lp.y)
                    } else if (recording) {
                        stopRecording(0)
                    } else if (SystemClock.uptimeMillis() - lastOutsideClose > 300) {
                        if (menuOpen) closeMenu() else openMenu()
                    }
                    scheduleFade()
                }
            }
            true
        }
        root = r
        wm.addView(r, lp)
        bubble.setLineSpacing(0f, 0.85f)
        scheduleFade()
        refreshGuide()
        main.postDelayed(liveTick, 1000)
        main.post(appTick)
        getSharedPreferences("framegrab", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun removeOverlays() {
        getSharedPreferences("framegrab", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        main.removeCallbacks(liveTick)
        main.removeCallbacks(appTick)
        for ((v, _) in handles) try { wm.removeView(v) } catch (e: Exception) { }
        handles.clear()
        editBar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        editBar = null
        guideEditing = false
        closeMenu()
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
        guide?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        guide = null
    }

    // ---------- เมนู ----------
    private fun menuRow(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        isSingleLine = true
        setPadding(dp(14f), dp(11f), dp(18f), dp(11f))
        setOnClickListener { onClick() }
    }

    /** รูปตัวอย่างสัดส่วนกรอบ (สี่เหลี่ยมมุมมนสีเทา แบบเมนูของ Grok) */
    private fun shapeIcon(rw: Int, rh: Int): View {
        val box = FrameLayout(this)
        val s = dp(22f)
        val w = if (rw >= rh) s else (s * rw / rh.toFloat()).roundToInt()
        val h = if (rh >= rw) s else (s * rh / rw.toFloat()).roundToInt()
        box.addView(View(this).apply { background = pill(0xFF9E9E9E.toInt(), dp(3f).toFloat()) },
            FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        return box
    }

    private fun textIcon(t: String) = TextView(this).apply { text = t; textSize = 16f; gravity = Gravity.CENTER }

    private fun presetRow(icon: View, label: String, checked: Boolean, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(9f), dp(16f), dp(9f))
            addView(icon, LinearLayout.LayoutParams(dp(28f), dp(28f)))
            addView(TextView(this@CaptureService).apply {
                text = label; setTextColor(Color.WHITE); textSize = 15f; isSingleLine = true
                minWidth = dp(120f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
            addView(TextView(this@CaptureService).apply {
                text = if (checked) "✓" else ""; setTextColor(Color.WHITE); textSize = 16f; minWidth = dp(20f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12f) })
            setOnClickListener { onClick() }
        }

    private fun buildMenu() {
        menuBox.removeAllViews()
        val preset = Prefs.cropPreset(this)
        if (menuPage == 0) {
            videoRatio?.let { r ->
                menuBox.addView(TextView(this).apply {
                    text = "📐  วิดีโอตอนนี้: $r"
                    setTextColor(0xFFB8BCCB.toInt()); textSize = 13f; isSingleLine = true
                    setPadding(dp(14f), dp(8f), dp(18f), dp(6f))
                })
            }
            if (Prefs.onlyInVideoApps(this) && !AppWatch.hasPermission(this)) {
                menuBox.addView(menuRow("🔓  ให้ซ่อนเองนอก TikTok/IG/FB") {
                    closeMenu()
                    toast("เลือก FrameGrab แล้วเปิด \"อนุญาตการเข้าถึงข้อมูลการใช้งาน\"")
                    try { startActivity(AppWatch.permissionIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.apply { setTextColor(0xFFFFC266.toInt()) })
            }
            menuBox.addView(menuRow("📸  จับภาพนี้") { captureOne() })
            menuBox.addView(menuRow("⏺  อัดต่อเนื่อง  (กดค้างปุ่มลอยก็ได้)") { startRecording() })
            menuBox.addView(menuRow("✂️  กรอบ: ${Cropper.presetLabel(this, preset)}  ›") { menuPage = 1; buildMenu(); layoutMenu() })
            menuBox.addView(menuRow("✋  ปรับกรอบด้วยมือ") { startGuideEdit() })
            menuBox.addView(menuRow("🔲  แสดงกรอบบนจอ: ${if (Prefs.showGuide(this)) "เปิด" else "ปิด"}") {
                Prefs.setShowGuide(this, !Prefs.showGuide(this))
                closeMenu()
                refreshGuide()
            })
            menuBox.addView(menuRow("⏱  ทุก ${Prefs.intervalLabel(Prefs.interval(this))} วิ") { cycleInterval() })
            menuBox.addView(menuRow("⌛  นานสุด ${Prefs.maxLabel(Prefs.maxSeconds(this))}") { cycleMax() })
            menuBox.addView(menuRow("🖼  ดูภาพ (${FrameStore.count(this)})") { openViewer() })
            menuBox.addView(menuRow("⚙️  ตั้งค่า / เปิดแอป") { openApp() })
            menuBox.addView(menuRow("✕  ปิดปุ่มลอยและแอป") { park(exitApp = true) })
        } else {
            menuBox.addView(menuRow("‹  เลือกกรอบ") { menuPage = 0; buildMenu(); layoutMenu() })
            for (p in Cropper.PRESETS) {
                val icon = when {
                    p.rw > 0 -> shapeIcon(p.rw, p.rh)
                    p.id == "full" -> shapeIcon(screenW.coerceAtLeast(1), screenH.coerceAtLeast(1))
                    p.id == "auto" -> textIcon("✨")
                    else -> textIcon("✏️")
                }
                val lbl = when {
                    p.id == "custom" && Prefs.hasCustomCrop(this) -> "กรอบที่ลากไว้ · ${Cropper.presetLabel(this, "custom")}"
                    Cropper.isPrimary(p.id) -> "${p.label}  ★ ใช้บ่อย"
                    else -> p.label
                }
                menuBox.addView(presetRow(icon, lbl, p.id == preset) { choosePreset(p.id) })
            }
            menuBox.addView(menuRow("✋  ปรับกรอบด้วยมือบนจอ") { startGuideEdit() })
            menuBox.addView(menuRow("🖼️  ลากกรอบบนภาพหน้าจอ") { captureForCrop() })
        }
    }

    private fun choosePreset(id: String) {
        if (id == "custom" && !Prefs.hasCustomCrop(this)) { startGuideEdit(); return }
        Prefs.setCropPreset(this, id)
        menuPage = 0
        closeMenu()
        if (id != "full" && !Prefs.showGuide(this)) Prefs.setShowGuide(this, true)
        refreshGuide()
        toast("✂️ กรอบ: ${Cropper.presetLabel(this, id)}")
    }

    /** วัดขนาดเมนูแล้ววางข้างปุ่มลอย ไม่ให้ล้นจอ */
    private fun layoutMenu() {
        val mw = menuWin ?: return
        val maxW = screenW - dp(16f)
        val maxH = (screenH * 0.8f).toInt()
        mw.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val w = mw.measuredWidth.coerceAtMost(maxW)
        val h = mw.measuredHeight.coerceAtMost(maxH)
        menuLp.width = w
        menuLp.height = h
        val onRight = lp.x + bubbleSize / 2 > screenW / 2
        menuLp.x = if (onRight) screenW - w - dp(8f) else dp(8f)
        val below = lp.y + bubbleSize + dp(14f)
        menuLp.y = if (below + h < screenH - dp(24f)) below else lp.y - h - dp(6f)
        menuLp.y = menuLp.y.coerceIn(dp(24f), max(dp(24f), screenH - h - dp(24f)))
        if (menuOpen) wm.updateViewLayout(mw, menuLp)
    }

    private fun openMenu() {
        if (menuOpen || menuWin == null) return
        work.post {
            grabBitmap()?.let { noteRatio(it); it.recycle() }
            main.post { showMenuNow() }
        }
    }

    private fun showMenuNow() {
        val mw = menuWin ?: return
        if (menuOpen || root?.visibility != View.VISIBLE) return
        menuPage = 0
        buildMenu()
        layoutMenu()
        wm.addView(mw, menuLp)
        menuOpen = true
        wake()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuWin?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        menuOpen = false
        scheduleFade()
    }

    private fun cycleInterval() {
        val cur = Prefs.interval(this)
        val i = Prefs.INTERVALS.indexOfFirst { it == cur }
        Prefs.setInterval(this, Prefs.INTERVALS[(i + 1) % Prefs.INTERVALS.size])
        buildMenu(); layoutMenu()
    }

    private fun cycleMax() {
        val cur = Prefs.maxSeconds(this)
        val i = Prefs.MAX_SECONDS.indexOfFirst { it == cur }
        Prefs.setMaxSeconds(this, Prefs.MAX_SECONDS[(i + 1) % Prefs.MAX_SECONDS.size])
        buildMenu(); layoutMenu()
    }

    private fun openApp() {
        closeMenu()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ถ่ายจอปัจจุบัน (ไม่มีปุ่มลอย) แล้วเปิดหน้าลากกรอบ */
    private fun captureForCrop() {
        if (recording) return
        menuPage = 0
        closeMenu()
        hideOverlay()
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            val bmp = grabFresh(hiddenAt + 60)
            var ok = false
            if (bmp != null) {
                try {
                    java.io.FileOutputStream(Cropper.refFile(this)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    ok = true
                } catch (e: Exception) { }
                bmp.recycle()
            }
            main.post {
                showOverlay()
                if (ok) startActivity(Intent(this, CropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else toast("จับภาพหน้าจอไม่สำเร็จ")
            }
        }
    }

    // ---------- กรอบบนจอ ----------
    /** คำนวณกรอบจากภาพที่ไม่มีปุ่มของเรา (เรียกจากเธรดทำงาน) */
    /** ภาพย่อ 12x12 (ความสว่าง) ของกลางจอ ไว้ดูว่าเปลี่ยนวิดีโอหรือยัง */
    private fun thumb(b: Bitmap): IntArray = IntArray(144) { k ->
        val i = k % 12; val j = k / 12
        val p = b.getPixel((b.width * (0.05f + 0.9f * i / 11)).toInt().coerceIn(0, b.width - 1),
            (b.height * (0.15f + 0.65f * j / 11)).toInt().coerceIn(0, b.height - 1))
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /** เริ่มตรวจกรอบใหม่ (เปลี่ยนวิดีโอ/เปลี่ยนตั้งค่า/กลับมาจากหน้าฟีด) — เรียกบนเธรดทำงาน */
    private fun resetLock() {
        samples.clear(); fixSamples.clear(); locked = false; lockedRect = null; lastDetected = null
    }

    /** ค่ากลางของแต่ละขอบ (ถ้าส่วนใหญ่ไม่เจอกรอบ = ทั้งจอ) */
    private fun median(list: List<android.graphics.RectF?>): android.graphics.RectF? {
        val rs = list.filterNotNull()
        if (rs.size * 2 <= list.size) return null
        fun m(f: (android.graphics.RectF) -> Float) = rs.map(f).sorted()[rs.size / 2]
        return android.graphics.RectF(m { it.left }, m { it.top }, m { it.right }, m { it.bottom })
    }

    private fun updateGuideFrom(bmp: Bitmap) {
        // ภาพเปลี่ยนกะทันหันจากรอบก่อน = ขึ้นสตอรี่/วิดีโอใหม่
        // (ทดสอบ: ในวิดีโอเดียวกันต่างกัน ≤14, คนละหน้าจอ ≥44)
        val t = thumb(bmp); val o = prevThumb; prevThumb = t
        val jump = o != null && t.indices.sumOf { kotlin.math.abs(t[it] - o[it]) } / t.size >= 30
        if (overrideRect != null) {
            // ลากกรอบเองอยู่: หยุดจับแบบเรียลไทม์ จนกว่าจะขึ้นสตอรี่ใหม่ (และผ่านไปอย่างน้อย 2.5 วิ)
            if (jump) jumpAfterDrag = true
            if (!jumpAfterDrag || SystemClock.uptimeMillis() - overrideAt < 2500) return
            overrideRect = null; resetLock()
        } else if (locked) {
            // ล็อกแล้ว: ไม่ขยับตามทุกรอบ แต่ถ้าตรวจได้ตรงกันอีก 3 รอบว่ากรอบผิด
            // (เช่นกรอบกินแถบชื่อ/พื้นดำที่สีต่างจากวิดีโอ) → ย้ายกรอบไปหาวิดีโอ
            if (!jump) { recheckLocked(bmp); return }
            resetLock()
        }
        noteRatio(bmp)
        if (!inOwnApp) Cropper.autoRect(this, bmp).let { a ->
            val sx = screenW / bmp.width.toFloat(); val sy = screenH / bmp.height.toFloat()
            val base = android.graphics.RectF(a.left * sx, a.top * sy, a.right * sx, a.bottom * sy)
            main.post { lastBase = base }
        }
        val preset = Prefs.cropPreset(this)
        val detected = detectRect(bmp, preset)
        samples.add(detected)
        var show = detected
        if (samples.size >= 3) {
            show = median(samples)
            lockedRect = show; locked = true
        }
        // ขยับนิดเดียว (≤3% ของจอ) ไม่ต้องย้ายกรอบ
        if (!changed(show, lastDetected) && !(locked && samples.size == 3)) return
        lastDetected = show
        postGuide(overrideRect ?: show, preset)
    }

    /** กรอบวิดีโอที่ตรวจได้จากภาพนี้ (พิกัดจอ) หรือ null = ทั้งจอ */
    private fun detectRect(bmp: Bitmap, preset: String): android.graphics.RectF? {
        val r = Cropper.rectFor(this, bmp)
        val sx = screenW / bmp.width.toFloat()
        val sy = screenH / bmp.height.toFloat()
        val full = r.left <= 0 && r.top <= 0 && r.right >= bmp.width && r.bottom >= bmp.height
        return if (full || preset == "full") null
            else android.graphics.RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
    }

    private val fixSamples = ArrayList<android.graphics.RectF?>()

    /** ตรวจกรอบที่ล็อกไว้ซ้ำ: ผิดตรงกัน 3 รอบติด → ย้ายกรอบ (ไม่กระตุกเพราะต้องตรงกันทุกรอบ) */
    private fun recheckLocked(bmp: Bitmap) {
        val preset = Prefs.cropPreset(this)
        val d = detectRect(bmp, preset)
        if (!changed(d, lockedRect)) { fixSamples.clear(); return }
        if (fixSamples.isNotEmpty() && changed(d, fixSamples.last())) fixSamples.clear()
        fixSamples.add(d)
        if (fixSamples.size < 3) return
        val m = median(fixSamples)
        fixSamples.clear()
        lockedRect = m; lastDetected = m
        noteRatio(bmp)
        postGuide(m, preset)
    }

    private fun postGuide(rect: android.graphics.RectF?, preset: String) {
        main.post {
            if (draggingGuide) return@post
            wakeGuide()   // กรอบเพิ่งย้าย: ชัดขึ้นให้เห็นแป๊บหนึ่งแล้วจางลง
            guide?.let { g ->
                g.rect = rect
                g.label = rect?.let { Cropper.ratioName(it.width().roundToInt(), it.height().roundToInt()) }
                    ?: Cropper.presetLabel(this, preset)
                g.invalidate()
                g.visibility = if (shouldShowGuide()) View.VISIBLE else View.INVISIBLE
                syncHandles()
            }
        }
    }

    /** กรอบที่จะใช้ตัดภาพ: ถ้าลากปรับชั่วคราวไว้ ใช้อันนั้น ไม่งั้นคำนวณตามตั้งค่า */
    private fun cropRectFor(bmp: Bitmap): android.graphics.Rect {
        val o = overrideRect
        if (o != null && screenW > 0 && screenH > 0) {
            val sx = bmp.width / screenW.toFloat(); val sy = bmp.height / screenH.toFloat()
            return android.graphics.Rect(
                (o.left * sx).roundToInt().coerceIn(0, bmp.width - 1), (o.top * sy).roundToInt().coerceIn(0, bmp.height - 1),
                (o.right * sx).roundToInt().coerceIn(1, bmp.width), (o.bottom * sy).roundToInt().coerceIn(1, bmp.height)
            )
        }
        val l = lockedRect
        if (locked && l != null && screenW > 0 && screenH > 0) {
            val sx = bmp.width / screenW.toFloat(); val sy = bmp.height / screenH.toFloat()
            return android.graphics.Rect(
                (l.left * sx).roundToInt().coerceIn(0, bmp.width - 1), (l.top * sy).roundToInt().coerceIn(0, bmp.height - 1),
                (l.right * sx).roundToInt().coerceIn(1, bmp.width), (l.bottom * sy).roundToInt().coerceIn(1, bmp.height)
            )
        }
        return Cropper.rectFor(this, bmp)
    }

    /** กรอบสองอันต่างกันพอจะนับว่า "เปลี่ยนวิดีโอ" ไหม (ขอบขยับเกิน 3% ของจอ) */
    private fun changed(a: android.graphics.RectF?, b: android.graphics.RectF?): Boolean {
        if (a == null || b == null) return a != b
        val tx = screenW * 0.03f; val ty = screenH * 0.03f
        return abs(a.left - b.left) > tx || abs(a.right - b.right) > tx || abs(a.top - b.top) > ty || abs(a.bottom - b.bottom) > ty
    }

    /** วัดสัดส่วนวิดีโอจากภาพ แล้วอัปเดตปุ่มกลม/แจ้งเตือนถ้าเปลี่ยน (เรียกจากเธรดทำงาน) */
    private fun noteRatio(bmp: Bitmap) {
        val r = try { Cropper.detectVideoRatio(this, bmp) } catch (e: Exception) { return }
        if (r != videoRatio) {
            videoRatio = r
            main.post {
                if (!recording) bubble.text = bubbleLabel()
                updateNotification()
            }
        }
    }

    /** ปุ่มกลม: ไอคอน + สัดส่วนวิดีโอตัวเล็กด้านล่าง */
    private fun bubbleLabel(): CharSequence {
        val head = "🎞️"
        val r = videoRatio?.substringBefore(" (") ?: return head
        val sb = android.text.SpannableStringBuilder("$head\n$r")
        sb.setSpan(android.text.style.AbsoluteSizeSpan(17, true), 0, head.length, 0)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(10, true), head.length + 1, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), head.length + 1, sb.length, 0)
        return sb
    }

    /** ดูว่าแอปไหนเปิดอยู่ แสดงปุ่มลอยเฉพาะใน TikTok / Instagram / Facebook */
    private val appTick = object : Runnable {
        override fun run() {
            if (root == null) return
            val svc = this@CaptureService
            val enabled = Prefs.onlyInVideoApps(svc) && AppWatch.hasPermission(svc)
            val fg = if (enabled) AppWatch.foreground(svc) else null
            inOwnApp = fg == packageName || ownUiHidden
            val newLabel = when {
                !Prefs.onlyInVideoApps(svc) -> null
                !AppWatch.hasPermission(svc) -> "⚠️ ยังไม่ได้อนุญาต \"การเข้าถึงข้อมูลการใช้งาน\" (แสดงทุกแอป)"
                fg == null -> "กำลังตรวจแอปที่เปิดอยู่…"
                else -> "แอปตอนนี้: ${AppWatch.label(svc, fg)}"
            }
            if (newLabel != fgLabel) { fgLabel = newLabel; updateNotification() }
            // หน้าตั้งค่าของ FrameGrab แสดงได้ (จะได้เห็นกรอบ) แต่หน้าดูภาพ/ลากกรอบ ซ่อน
            val hide = parked || ownUiHidden || !capturedAppVisible || feedHidden ||
                (enabled && fg != null && fg !in AppWatch.VIDEO_APPS && fg != packageName)
            if (hide != appHidden) {
                appHidden = hide
                if (hide) {
                    if (guideEditing) finishGuideEdit(false)
                    closeMenu()
                    if (!recording && !holdHidden) showOverlay() // showOverlay จะซ่อนให้เพราะ appHidden
                } else if (!recording && !holdHidden) {
                    showOverlay()
                }
            }
            main.postDelayed(this, 700)
        }
    }

    /** วัดสัดส่วนวิดีโอทุก ~1 วินาที และให้กรอบบนจอตามวิดีโอแบบเรียลไทม์ */
    private val liveTick = object : Runnable {
        override fun run() {
            if (root == null) return
            val svc = this@CaptureService
            val visibleOrFeedHidden = root?.visibility == View.VISIBLE || (feedHidden && !holdHidden)
            if (!parked && !recording && !menuOpen && !guideEditing && !draggingGuide && !inOwnApp && visibleOrFeedHidden) {
                val preset = Prefs.cropPreset(svc)
                val guideLive = Prefs.showGuide(svc) && preset != "full" && preset != "custom"
                val storyOnly = Prefs.storyOnly(svc)
                work.post {
                    val b = grabBitmap() ?: return@post
                    // แสดงเฉพาะตอนดูสตอรี่: เจอสตอรี่ = แสดงทันที, ไม่เจอ 2 ครั้งติด = ซ่อน
                    val story = !storyOnly || !Cropper.hasFeedNav(b)
                    storyMiss = if (story) 0 else storyMiss + 1
                    val hide = !story && storyMiss >= 2
                    if (story && feedHidden || hide && !feedHidden) {
                        feedHidden = !story
                        resetLock()
                        main.post { main.removeCallbacks(appTick); main.post(appTick) }
                    }
                    if (story) { if (guideLive) updateGuideFrom(b) else noteRatio(b) }
                    b.recycle()
                }
            } else if (feedHidden && !Prefs.storyOnly(svc)) {
                feedHidden = false
                main.removeCallbacks(appTick); main.post(appTick)
            }
            main.postDelayed(this, if (Prefs.storyOnly(this@CaptureService)) 600 else 1000)
        }
    }

    /** ตั้งค่ากรอบเปลี่ยน (เช่นจากหน้าแอปเต็ม) → อัปเดตกรอบบนจอทันที */
    private fun applyGuidePrefs() {
        val g = guide ?: return
        if (guideEditing || draggingGuide || recording) return
        val preset = Prefs.cropPreset(this)
        val p = Cropper.PRESETS.firstOrNull { it.id == preset }
        val base = lastBase ?: android.graphics.RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
        when {
            !Prefs.showGuide(this) || preset == "full" -> g.rect = null
            preset == "custom" -> {
                val f = Prefs.cropRect(this)
                g.rect = android.graphics.RectF(f[0] * screenW, f[1] * screenH, f[2] * screenW, f[3] * screenH)
            }
            inOwnApp -> { // อยู่ในหน้าแอปเรา วัดจอไม่ได้ ใช้พื้นที่วิดีโอล่าสุดแทน
                g.rect = if (p != null && p.rw > 0) {
                    val r = Cropper.fitRatio(
                        android.graphics.Rect(base.left.toInt(), base.top.toInt(), base.right.toInt(), base.bottom.toInt()),
                        p.rw, p.rh
                    )
                    android.graphics.RectF(r)
                } else android.graphics.RectF(base)
            }
            else -> { refreshGuide(); return }
        }
        g.label = Cropper.presetLabel(this, preset)
        g.invalidate()
        g.visibility = if (shouldShowGuide()) View.VISIBLE else View.INVISIBLE
        syncHandles()
        updateNotification()
    }

    /** ซ่อนปุ่มแป๊บหนึ่ง ถ่ายจอ แล้วคำนวณกรอบใหม่ */
    private fun refreshGuide() {
        if (recording || root == null) return
        if (!Prefs.showGuide(this) || Prefs.cropPreset(this) == "full") {
            guide?.visibility = View.INVISIBLE
            syncHandles()
            return
        }
        hideOverlay()
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            val bmp = grabFresh(hiddenAt + 60)
            if (bmp != null) { updateGuideFrom(bmp); bmp.recycle() }
            main.post { showOverlay() }
        }
    }

    private fun shouldShowGuide(): Boolean =
        guideEditing || (!appHidden && Prefs.showGuide(this) && !recording && root?.visibility == View.VISIBLE && guide?.rect != null)

    // ---------- จุดจับบนกรอบ (ลากได้ทันที ไม่ต้องเข้าเมนู) ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun addHandles() {
        val size = dp(44f)
        val made = arrayOfNulls<Pair<HandleView, WindowManager.LayoutParams>>(9)
        // ใส่แถบขอบก่อน ให้จุดมุมและปุ่มย้ายอยู่ชั้นบนกว่า
        for (i in listOf(5, 6, 7, 8, 0, 1, 2, 3, 4)) {
            val v = HandleView(this, move = i == 4, edge = if (i >= 5) handleModes[i] else 0)
            val hl = overlayParams(size, size, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            v.visibility = View.GONE
            v.setOnTouchListener { _, e -> onHandleTouch(i, e); true }
            wm.addView(v, hl)
            made[i] = v to hl
        }
        for (h in made) handles.add(h!!)
    }

    private fun onHandleTouch(i: Int, e: MotionEvent) {
        val g = guide ?: return
        if (g.rect == null || recording) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingGuide = true
                wakeGuide(stay = true)
                closeMenu()
                g.bounds.set(0f, 0f, screenW.toFloat(), screenH.toFloat())
                // กรอบแบบสัดส่วน: ลากแล้วยังคงสัดส่วนเดิม
                g.lockRatio = ratioOf(Prefs.cropPreset(this))
                g.beginDrag(handleModes[i], e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                g.dragTo(e.rawX, e.rawY)
                syncHandles()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingGuide = false
                wakeGuide()
                val r = g.rect ?: return
                val preset = Prefs.cropPreset(this)
                if (preset == "custom" || preset == "full") {
                    // กรอบที่ลากไว้: บันทึกถาวร
                    Prefs.setCropRect(this, r.left / screenW, r.top / screenH, r.right / screenW, r.bottom / screenH)
                    Prefs.setCropPreset(this, "custom")
                } else {
                    // อัตโนมัติ/สัดส่วน: ปรับชั่วคราวเฉพาะวิดีโอนี้ เลื่อนไปวิดีโอใหม่แล้วกรอบจะตามวิดีโอเอง
                    overrideAt = SystemClock.uptimeMillis(); jumpAfterDrag = false
                    overrideRect = android.graphics.RectF(r)
                }
                g.showRatio = false
                g.label = Cropper.ratioTag(r.width().toInt(), r.height().toInt())
                g.invalidate()
                updateNotification()
            }
        }
    }

    // ---------- กรอบจางเมื่อไม่ได้แตะ ----------
    private val fadeGuide = Runnable { setGuideAlpha(0.3f) }
    private fun setGuideAlpha(a: Float) {
        guide?.animate()?.alpha(a)?.setDuration(250)?.start()
    }
    /** ทำให้กรอบชัดขึ้นชั่วคราว แล้วค่อยจางลงหลัง 2 วิ */
    private fun wakeGuide(stay: Boolean = false) {
        main.removeCallbacks(fadeGuide)
        guide?.animate()?.cancel(); guide?.alpha = 1f
        if (!stay) main.postDelayed(fadeGuide, 2000)
    }

    /** วางจุดจับให้ตรงมุมกรอบ และซ่อน/แสดงตามกรอบ */
    private fun syncHandles() {
        val g = guide
        val r = g?.rect
        val show = g != null && r != null && g.visibility == View.VISIBLE && !guideEditing && !recording
        var offX = 0; var offY = 0
        if (show) { g!!.getLocationOnScreen(locBuf); offX = locBuf[0]; offY = locBuf[1] }
        for ((i, h) in handles.withIndex()) {
            val (v, hl) = h
            if (!show) { if (v.visibility != View.GONE) v.visibility = View.GONE; continue }
            // เหลือแค่มุม 4 จุด (ปุ่มย้าย ✥ และขีดกลางขอบเอาออก ลดความรก)
            if (i >= 4) { if (v.visibility != View.GONE) v.visibility = View.GONE; continue }
            if (i >= 5) {
                // จุดจับกลางขอบ: เล็ก ๆ แค่ตรงกลางขอบ (ไม่ทั้งแนว จะได้ไม่แย่งที่แตะดูสตอรี่ถัดไป)
                // ซ้าย/ขวาเลี่ยงขอบจอ (ปัดย้อนกลับของระบบ)
                val t = dp(28f); val len = dp(52f); val sysEdge = dp(24f).toFloat()
                val horiz = i == 5 || i == 6
                if ((if (horiz) r!!.width() else r!!.height()) < len + dp(90f)) { if (v.visibility != View.GONE) v.visibility = View.GONE; continue }
                if (horiz) {
                    val ey = if (i == 5) r.top else r.bottom
                    hl.width = len; hl.height = t
                    hl.x = (r.centerX() - len / 2f - offX).roundToInt(); hl.y = (ey - offY - t / 2f).roundToInt()
                    // ขอบบน: เลี่ยงปุ่มย้าย ✥ ที่อยู่กลางขอบบน
                    if (i == 5) hl.x += dp(56f)
                } else {
                    val ex = if (i == 7) r.left else r.right
                    val x = (ex - t / 2f).coerceIn(sysEdge, screenW - sysEdge - t)
                    hl.width = t; hl.height = len
                    hl.x = (x - offX).roundToInt(); hl.y = (r.centerY() - len / 2f - offY).roundToInt()
                }
                v.visibility = View.VISIBLE
                try { wm.updateViewLayout(v, hl) } catch (e: Exception) { }
                continue
            }
            val px = when (i) { 0, 2 -> r!!.left; 1, 3 -> r!!.right; else -> r!!.centerX() }
            val py = when (i) { 0, 1, 4 -> r.top; else -> r.bottom }
            // ปุ่มย้ายอยู่เหนือขอบบนเล็กน้อย ไม่บังวิดีโอ
            val dy = if (i == 4) -dp(4f) else 0
            hl.x = (px - offX - hl.width / 2f).roundToInt()
            hl.y = (py - offY - hl.height / 2f).roundToInt() + dy
            v.visibility = View.VISIBLE
            try { wm.updateViewLayout(v, hl) } catch (e: Exception) { }
        }
    }

    // ---------- ปรับกรอบด้วยมือบนจอ ----------
    private fun ratioOf(preset: String): Float? =
        Cropper.PRESETS.firstOrNull { it.id == preset && it.rw > 0 }?.let { it.rw / it.rh.toFloat() }

    private fun startGuideEdit() {
        val g = guide ?: return
        if (recording || guideEditing) return
        menuPage = 0
        closeMenu()
        val preset = Prefs.cropPreset(this)
        g.bounds.set(0f, 0f, screenW.toFloat(), screenH.toFloat())
        editBackup = g.rect?.let { android.graphics.RectF(it) }
        if (g.rect == null) {
            // ยังไม่มีกรอบ: เริ่มจากกรอบที่เคยตั้งเอง หรือกลางจอ 80%
            g.rect = if (Prefs.hasCustomCrop(this)) {
                val f = Prefs.cropRect(this)
                android.graphics.RectF(f[0] * screenW, f[1] * screenH, f[2] * screenW, f[3] * screenH)
            } else android.graphics.RectF(screenW * 0.1f, screenH * 0.15f, screenW * 0.9f, screenH * 0.85f)
        }
        g.lockRatio = ratioOf(preset)
        g.label = "ลากเพื่อปรับ"
        guideEditing = true; wakeGuide(stay = true)
        g.editing = true
        g.visibility = View.VISIBLE
        syncHandles()
        guideLp.flags = guideLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(g, guideLp)
        root?.let { // ซ่อนปุ่มลอยไม่ให้บังการลาก
            it.visibility = View.INVISIBLE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(it, lp)
        }
        showEditBar()
    }

    private fun showEditBar() {
        editBar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        val g = guide ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xF2181A22.toInt(), dp(22f).toFloat())
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }
        fun barBtn(t: String, on: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { on() }
        }
        val lockText = { g.lockRatio?.let { "🔒 ${Cropper.ratioName((it * 1000).roundToInt(), 1000)}" } ?: "🔓 อิสระ" }
        val lockBtn = barBtn(lockText()) { }
        lockBtn.setOnClickListener {
            val r = g.rect
            g.lockRatio = if (g.lockRatio != null || r == null) null else r.width() / r.height()
            lockBtn.text = lockText()
        }
        bar.addView(lockBtn)
        bar.addView(barBtn("✨ พอดีวิดีโอ") { fitGuideToVideo() })
        bar.addView(barBtn("✕") { finishGuideEdit(false) })
        bar.addView(barBtn("✔ ใช้กรอบนี้") { finishGuideEdit(true) }.apply {
            background = pill(0xFF4F46E5.toInt(), dp(18f).toFloat())
        })
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - dp(16f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(80f), View.MeasureSpec.AT_MOST)
        )
        val blp = overlayParams(bar.measuredWidth, bar.measuredHeight).apply {
            x = (screenW - bar.measuredWidth) / 2
            y = dp(48f)
        }
        wm.addView(bar, blp)
        editBar = bar
    }

    /** ให้กรอบพอดีกับวิดีโอที่เล่นอยู่ (ซ่อนกรอบแป๊บหนึ่งเพื่อถ่ายจอ) */
    private fun fitGuideToVideo() {
        val g = guide ?: return
        g.visibility = View.INVISIBLE
        syncHandles()
        editBar?.visibility = View.INVISIBLE
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            val bmp = grabFresh(hiddenAt + 60)
            val r = bmp?.let {
                val a = Cropper.autoRect(this, it)
                val lock = g.lockRatio
                val rr = if (lock != null) Cropper.fitRatio(a, (lock * 1000).roundToInt(), 1000) else a
                val sx = screenW / it.width.toFloat(); val sy = screenH / it.height.toFloat()
                android.graphics.RectF(rr.left * sx, rr.top * sy, rr.right * sx, rr.bottom * sy).also { _ -> it.recycle() }
            }
            main.post {
                if (r != null) g.rect = r
                g.visibility = View.VISIBLE
                syncHandles()
                editBar?.visibility = View.VISIBLE
                g.invalidate()
            }
        }
    }

    private fun finishGuideEdit(apply: Boolean) {
        val g = guide ?: return
        if (!guideEditing) return
        editBar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        editBar = null
        guideEditing = false; wakeGuide()
        g.editing = false
        guideLp.flags = guideLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(g, guideLp)
        val r = g.rect
        if (apply && r != null && screenW > 0 && screenH > 0) {
            Prefs.setCropRect(this, r.left / screenW, r.top / screenH, r.right / screenW, r.bottom / screenH)
            Prefs.setCropPreset(this, "custom")
            Prefs.setShowGuide(this, true)
            g.label = Cropper.presetLabel(this, "custom")
            toast("✔ ใช้กรอบนี้แล้ว (${Cropper.ratioName(r.width().toInt(), r.height().toInt())})")
        } else {
            g.rect = editBackup
            g.label = Cropper.presetLabel(this, Prefs.cropPreset(this))
        }
        g.invalidate()
        root?.visibility = View.VISIBLE
        showOverlay()
        updateNotification()
    }

    private fun wake() {
        main.removeCallbacks(fadeRunnable)
        root?.alpha = 1f
    }

    private fun scheduleFade() {
        main.removeCallbacks(fadeRunnable)
        main.postDelayed(fadeRunnable, 2500)
    }

    /** ซ่อนทุกอย่างของเราก่อนจับภาพ */
    private fun hideOverlay() {
        holdHidden = true
        if (guideEditing) finishGuideEdit(false)
        closeMenu()
        guide?.visibility = View.INVISIBLE
        syncHandles()
        val r = root ?: return
        r.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(r, lp)
    }

    private fun showOverlay() {
        val r = root ?: return
        holdHidden = false
        if (appHidden && !recording) { // ไม่ได้อยู่ในแอปวิดีโอ: ซ่อนไว้
            r.visibility = View.INVISIBLE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(r, lp)
            guide?.visibility = View.INVISIBLE
            syncHandles()
            return
        }
        r.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(r, lp)
        guide?.visibility = if (shouldShowGuide()) View.VISIBLE else View.INVISIBLE
        syncHandles()
        scheduleFade()
    }

    private fun sysDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** ย้ายปุ่ม ■ ไปมุมจอที่อยู่นอกกรอบที่เก็บ (ถ้าไม่มีที่ว่าง ปุ่มอาจติดในภาพ) */
    private fun placeStopButton() {
        val r = root ?: return
        savedBubblePos = lp.x to lp.y
        r.getLocationOnScreen(locBuf)
        val offX = locBuf[0] - lp.x; val offY = locBuf[1] - lp.y
        val rect = recRect
        val sr = if (rect != null && recBmpW > 0 && recBmpH > 0) android.graphics.RectF(
            rect.left * screenW / recBmpW.toFloat(), rect.top * screenH / recBmpH.toFloat(),
            rect.right * screenW / recBmpW.toFloat(), rect.bottom * screenH / recBmpH.toFloat()
        ) else null
        val full = sr == null || (sr.width() >= screenW - 4 && sr.height() >= screenH - 4)
        val win = lp.width
        val m = dp(8f)
        val top = sysDimen("status_bar_height") + m
        val bottom = screenH - sysDimen("navigation_bar_height") - win - m
        val cands = listOf(
            (lp.x + offX) to (lp.y + offY),
            (screenW - win - m) to top, m to top,
            (screenW - win - m) to bottom, m to bottom,
        )
        val pick = if (full) null else cands.firstOrNull { (x, y) ->
            !android.graphics.RectF.intersects(
                android.graphics.RectF(x - 4f, y - 4f, x + win + 4f, y + win + 4f), sr!!
            )
        }
        stopOverlaps = pick == null
        if (pick != null) { lp.x = pick.first - offX; lp.y = pick.second - offY }
    }

    /** ปุ่มตอนอัด: ■ สีแดง + เวลาที่อัดไปแล้ว + วงขอบขาว */
    private fun recLabel(): CharSequence {
        val sec = if (recStart > 0) ((SystemClock.uptimeMillis() - recStart) / 1000).coerceAtLeast(0) else 0
        val t = String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
        val head = "■"
        val sb = android.text.SpannableStringBuilder("$head\n$t")
        sb.setSpan(android.text.style.AbsoluteSizeSpan(18, true), 0, head.length, 0)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(11, true), head.length + 1, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), head.length + 1, sb.length, 0)
        return sb
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            bubble.text = recLabel()
            main.postDelayed(this, 500)
        }
    }

    private fun showStopButton() {
        guide?.visibility = View.INVISIBLE
        (bubble.background as? GradientDrawable)?.setStroke(dp(3f), 0xE6FFFFFF.toInt())
        main.removeCallbacks(recTick)
        main.post(recTick)
        if (stopOverlaps) toast("กรอบเต็มจอ ปุ่ม ■ อาจติดในภาพ (เลือกกรอบอัตโนมัติ/9:16 เพื่อเลี่ยง)")
        root?.let {
            holdHidden = false
            it.visibility = View.VISIBLE
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            wm.updateViewLayout(it, lp)
        }
        syncHandles()
        bubble.text = recLabel()
        bubble.setTextColor(Color.WHITE)
        (bubble.background as? GradientDrawable)?.setColor(0xE6DC2626.toInt())
        wake()
    }

    private fun restoreBubble() {
        main.removeCallbacks(recTick)
        (bubble.background as? GradientDrawable)?.setStroke(0, 0)
        bubble.text = bubbleLabel()
        (bubble.background as? GradientDrawable)?.setColor(0xE64F46E5.toInt())
    }

    private fun flashBubble(t: String) {
        bubble.text = t
        main.postDelayed({ if (!recording) bubble.text = bubbleLabel() }, 700)
    }

    // ---------- แจ้งเตือน ----------
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(open)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP_REC),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentTitle("⏺ กำลังอัด… $recCount ภาพ")
                .setContentText("แตะ ■ บนจอ หรือปุ่มด้านล่างเพื่อหยุด")
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "■ หยุดอัด", stop
                ).build())
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_preferences), "⚙️ เปิดแอป", openApp
                ).build())
        } else if (parked) {
            val show = PendingIntent.getService(
                this, 4, Intent(this, CaptureService::class.java).setAction(ACTION_UNPARK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val quit = PendingIntent.getService(
                this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE
            )
            val reshare = reshareIntent()
            b.setContentTitle("ปุ่มลอยซ่อนอยู่")
                .setContentText("แตะ ▶ เพื่อเปิดปุ่มลอย (ไม่ต้องกดยืนยันใหม่)")
                .setContentIntent(show)
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_play), "▶ เปิดปุ่มลอย", show
                ).build())
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_rotate), "🔄 เปลี่ยนแอป", reshare
                ).build())
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "■ หยุดจับหน้าจอ", quit
                ).build())
        } else {
            val quit = PendingIntent.getService(
                this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentTitle(videoRatio?.let { "📐 วิดีโอที่เล่นอยู่: $it" } ?: "ปุ่มลอยจับเฟรมทำงานอยู่")
                .setContentText("กรอบ: ${Cropper.presetLabel(this, Prefs.cropPreset(this))}" + (fgLabel?.let { " · $it" } ?: ""))
                .setStyle(Notification.BigTextStyle().bigText(
                    "กรอบ: ${Cropper.presetLabel(this, Prefs.cropPreset(this))}\n" + (fgLabel ?: "แตะปุ่ม 🎞️ บนจอเพื่อจับภาพ")
                ))
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_preferences), "⚙️ เปิดแอป", openApp
                ).build())
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_rotate), "🔄 เปลี่ยนแอป", reshareIntent()
                ).build())
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "■ หยุดจับหน้าจอ", quit
                ).build())
        }
        return b.build()
    }

    /** เปิดหน้าแอปแล้วขอเลือกแอปที่จะแชร์ใหม่ */
    private fun reshareIntent(): PendingIntent = PendingIntent.getActivity(
        this, 5, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_RESHARE, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun updateNotification() {
        if (!running && !recording) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ยกเลิกอันเก่าก่อน จะได้ขึ้นทันทีไม่ต้องรอคิว (กด 📸 รัว ๆ)
    private var lastToast: Toast? = null
    private fun toast(s: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, s, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ---------- ปิด ----------
    /**
     * ปิดทุกอย่าง: ปุ่มลอย, เมนู, กรอบ, จุดจับ, การจับภาพหน้าจอ
     * exitApp = ปิดหน้าจอของแอปด้วย (ปิดจากปุ่มลอย/แจ้งเตือน)
     */
    private fun quit(exitApp: Boolean = true) {
        if (quitting) return
        quitting = true
        recording = false
        running = false
        parked = false
        main.removeCallbacks(fadeRunnable)
        removeOverlays()
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        projection?.let { p -> projection = null; try { p.stop() } catch (e: Exception) { } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (exitApp) {
            // ปิดหน้าจอของแอปที่เปิดค้างอยู่ทั้งหมด
            try {
                getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        quit()
        imgThread.quitSafely()
        workThread.quitSafely()
        super.onDestroy()
    }
}
