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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
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
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7
        private const val MAX_UNLIMITED_MS = 5 * 60 * 1000L
        @Volatile var running = false
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

    // ---------- ปุ่มลอย ----------
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var menu: LinearLayout
    private lateinit var btnInterval: TextView
    private lateinit var btnMax: TextView
    private lateinit var btnView: TextView
    private var menuOpen = false
    private var menuAbove = false
    private var anchorY = 0
    private var screenW = 0
    private var screenH = 0
    private val fadeRunnable = Runnable { if (!menuOpen && !recording) root?.alpha = 0.45f }

    // ---------- การอัดต่อเนื่อง ----------
    private val loop = LoopDetector()
    @Volatile private var loopFeeding = false
    @Volatile private var recording = false
    @Volatile private var recStart = 0L
    private var recCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
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
            ACTION_QUIT -> quit()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) return
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
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { quit() } }
        }, main)
        setupDisplay(p)
        addOverlay()
        running = true
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
            if (bmp != null) {
                val now = java.util.Calendar.getInstance()
                val msOfDay = ((now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)) * 60 +
                    now.get(java.util.Calendar.SECOND)) * 1000L + now.get(java.util.Calendar.MILLISECOND)
                try { FrameStore.save(this, bmp, 's', msOfDay); ok = true } catch (e: Exception) { }
                bmp.recycle()
            }
            main.post {
                showOverlay()
                flashBubble(if (ok) "✓" else "!")
                toast(if (ok) "📸 จับภาพแล้ว (${FrameStore.count(this)})" else "จับภาพไม่สำเร็จ")
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        closeMenu()
        FrameStore.clear(this) // อัดคลิปใหม่ ล้างภาพเก่าทันที
        recording = true
        recCount = 0
        val keepStop = Prefs.keepStopButton(this)
        if (keepStop) showStopButton() else hideOverlay()
        val intervalMs = (Prefs.interval(this) * 1000).roundToInt().toLong().coerceAtLeast(100)
        val maxSec = Prefs.maxSeconds(this)
        val maxMs = if (maxSec == 0) MAX_UNLIMITED_MS else maxSec * 1000L
        val autoStop = Prefs.autoStop(this)
        loop.reset()
        toast("⏺ เริ่มอัด ทุก ${Prefs.intervalLabel(Prefs.interval(this))} วิ · หยุดได้ที่แถบแจ้งเตือน")
        val hiddenAt = SystemClock.uptimeMillis()
        work.post {
            // รอให้ปุ่มลอยหายจากจอก่อนเริ่มนับเวลา
            val end = hiddenAt + 500
            while (latestAt <= hiddenAt + 60 && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
            recStart = SystemClock.uptimeMillis()
            loopFeeding = autoStop
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
                    val bmp = grabBitmap()
                    if (bmp != null) {
                        try { FrameStore.save(this@CaptureService, bmp, 'c', el, 90); recCount++ } catch (e: Exception) { }
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

    private fun stopRecording(dropLastMs: Long, cutAtMs: Long = -1, reason: String? = null) {
        if (!recording) return
        recording = false
        loopFeeding = false
        val el = SystemClock.uptimeMillis() - recStart
        work.post {
            if (dropLastMs > 0) FrameStore.dropAfter(this, el - dropLastMs)
            if (cutAtMs >= 0) FrameStore.dropAfter(this, cutAtMs - 1) // ตัดภาพที่ซ้ำรอบสอง
            val n = FrameStore.count(this)
            main.post {
                restoreBubble()
                showOverlay()
                updateNotification()
                toast((reason?.let { "$it · " } ?: "") + "ได้ $n ภาพ")
                if (n > 0) openViewer()
            }
        }
    }

    private fun openViewer() {
        closeMenu()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    // ---------- ปุ่มลอย ----------
    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun menuItem(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(14f), dp(10f), dp(18f), dp(10f))
        background = pill(Color.TRANSPARENT, dp(10f).toFloat())
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        bubble = TextView(this).apply {
            text = "🎞️"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE64F46E5.toInt()) }
            elevation = dp(6f).toFloat()
        }
        val size = dp(56f)
        r.addView(bubble, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })

        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xF2181A22.toInt(), dp(14f).toFloat())
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            elevation = dp(8f).toFloat()
        }
        btnInterval = menuItem("") { cycleInterval() }
        btnMax = menuItem("") { cycleMax() }
        btnView = menuItem("") { openViewer() }
        menu.addView(menuItem("📸  จับภาพนี้") { captureOne() })
        menu.addView(menuItem("⏺  อัดต่อเนื่อง") { startRecording() })
        menu.addView(btnInterval)
        menu.addView(btnMax)
        menu.addView(btnView)
        menu.addView(menuItem("✕  ปิดปุ่มลอย") { quit() })
        refreshMenuLabels()

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = Prefs.bubbleX(this@CaptureService)
            val sy = Prefs.bubbleY(this@CaptureService)
            x = if (sx >= 0) sx else screenW - size - dp(8f)
            y = if (sy >= 0) sy else screenH / 3
        }
        anchorY = lp.y

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = anchorY; moved = false
                    wake()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; closeMenu() }
                    if (moved) {
                        lp.x = (startX + dx).roundToInt().coerceIn(0, max(0, screenW - size))
                        anchorY = (startY + dy).roundToInt().coerceIn(0, max(0, screenH - size))
                        lp.y = anchorY
                        root?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // ชิดขอบซ้ายหรือขวา
                        lp.x = if (lp.x + size / 2 < screenW / 2) 0 else screenW - size - dp(8f)
                        root?.let { wm.updateViewLayout(it, lp) }
                        Prefs.setBubble(this, lp.x, anchorY)
                    } else if (recording) {
                        stopRecording(0)
                    } else {
                        toggleMenu()
                    }
                    scheduleFade()
                }
            }
            true
        }
        root = r
        wm.addView(r, lp)
        scheduleFade()
    }

    private fun refreshMenuLabels() {
        btnInterval.text = "⏱  ทุก ${Prefs.intervalLabel(Prefs.interval(this))} วิ"
        btnMax.text = "⌛  นานสุด ${Prefs.maxLabel(Prefs.maxSeconds(this))}"
        btnView.text = "🖼  ดูภาพ (${FrameStore.count(this)})"
    }

    private fun cycleInterval() {
        val cur = Prefs.interval(this)
        val i = Prefs.INTERVALS.indexOfFirst { it == cur }
        Prefs.setInterval(this, Prefs.INTERVALS[(i + 1) % Prefs.INTERVALS.size])
        refreshMenuLabels()
    }

    private fun cycleMax() {
        val cur = Prefs.maxSeconds(this)
        val i = Prefs.MAX_SECONDS.indexOfFirst { it == cur }
        Prefs.setMaxSeconds(this, Prefs.MAX_SECONDS[(i + 1) % Prefs.MAX_SECONDS.size])
        refreshMenuLabels()
    }

    private fun toggleMenu() = if (menuOpen) closeMenu() else openMenu()

    private fun openMenu() {
        val r = root ?: return
        refreshMenuLabels()
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bubbleH = bubble.height + dp(8f)
        menuAbove = anchorY + bubbleH + menu.measuredHeight > screenH - dp(24f)
        if (menuAbove) {
            r.addView(menu, 0)
            lp.y = max(0, anchorY - menu.measuredHeight)
        } else {
            r.addView(menu)
            lp.y = anchorY
        }
        // เมนูชิดฝั่งเดียวกับปุ่ม
        r.gravity = if (lp.x + dp(28f) > screenW / 2) Gravity.END else Gravity.START
        if (r.gravity == Gravity.END) lp.x = max(0, screenW - max(menu.measuredWidth, bubble.width + dp(8f)))
        menuOpen = true
        wake()
        wm.updateViewLayout(r, lp)
    }

    private fun closeMenu() {
        val r = root ?: return
        if (!menuOpen) return
        r.removeView(menu)
        menuOpen = false
        lp.y = anchorY
        val size = dp(56f)
        if (r.gravity == Gravity.END) lp.x = screenW - size - dp(8f)
        wm.updateViewLayout(r, lp)
        scheduleFade()
    }

    private fun wake() {
        main.removeCallbacks(fadeRunnable)
        root?.alpha = 1f
    }

    private fun scheduleFade() {
        main.removeCallbacks(fadeRunnable)
        main.postDelayed(fadeRunnable, 2500)
    }

    private fun hideOverlay() {
        val r = root ?: return
        r.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(r, lp)
    }

    private fun showOverlay() {
        val r = root ?: return
        r.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(r, lp)
        scheduleFade()
    }

    private fun showStopButton() {
        bubble.text = "■"
        bubble.setTextColor(Color.WHITE)
        (bubble.background as? GradientDrawable)?.setColor(0xE6DC2626.toInt())
        wake()
    }

    private fun restoreBubble() {
        bubble.text = "🎞️"
        (bubble.background as? GradientDrawable)?.setColor(0xE64F46E5.toInt())
    }

    private fun flashBubble(t: String) {
        bubble.text = t
        main.postDelayed({ if (!recording) bubble.text = "🎞️" }, 700)
    }

    // ---------- แจ้งเตือน ----------
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
                .setContentText("กดหยุดเมื่อคลิปจบ")
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "■ หยุดอัด", stop
                ).build())
        } else {
            val quit = PendingIntent.getService(
                this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentTitle("ปุ่มลอยจับเฟรมทำงานอยู่")
                .setContentText("แตะปุ่ม 🎞️ บนจอเพื่อจับภาพ")
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "ปิด", quit
                ).build())
        }
        return b.build()
    }

    private fun updateNotification() {
        if (!running && !recording) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- ปิด ----------
    private fun quit() {
        if (quitting) return
        quitting = true
        recording = false
        running = false
        main.removeCallbacks(fadeRunnable)
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        projection?.let { p -> projection = null; try { p.stop() } catch (e: Exception) { } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        quit()
        imgThread.quitSafely()
        workThread.quitSafely()
        super.onDestroy()
    }
}
