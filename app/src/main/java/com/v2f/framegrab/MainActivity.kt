package com.v2f.framegrab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** หน้าแรก: ขอสิทธิ์, เปิด/ปิดปุ่มลอย, ตั้งค่า, ดูภาพ */
class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 10
        private const val REQ_NOTIF = 11
        private const val REQ_VIDEO = 12
        /** เปิดมาจากแจ้งเตือน "🔄 เปลี่ยนแอป" */
        const val EXTRA_RESHARE = "reshare"
    }

    private lateinit var status: TextView
    private lateinit var startBtn: TextView
    private lateinit var reshareBtn: TextView
    private lateinit var replayBtn: TextView
    private var resharing = false
    private lateinit var intervalBtn: TextView
    private lateinit var maxBtn: TextView
    private lateinit var crashBox: TextView
    private lateinit var nameNote: TextView
    private lateinit var ocrNote: TextView
    private lateinit var shotStrip: LinearLayout
    private lateinit var videoGrid: LinearLayout
    private lateinit var vidStrip: LinearLayout
    private lateinit var videoPermBtn: TextView
    private lateinit var vidLabel: TextView
    private val thumbPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val everyBtns = ArrayList<Pair<Double, TextView>>()
    private lateinit var cropBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var appsPermBtn: TextView
    private lateinit var appsNote: TextView

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14f).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 14f, color: Int = 0xFFB8BCCB.toInt()) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OwnActivities.add(this)
        Crash.install(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(36f), dp(20f), dp(28f))
        }
        val gap = { h: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(h) } }

        col.addView(label("🎞️ FrameGrab", 26f, Color.WHITE))
        col.addView(label("ปุ่มลอยจับเฟรมจากวิดีโอ ใช้ได้เหนือทุกแอป เช่น TikTok, Instagram, Facebook, YouTube"), gap(4f))

        status = label("", 14f)
        col.addView(status, gap(18f))

        // ถ้าครั้งก่อนแอปเด้ง แสดงสาเหตุให้คัดลอกส่งต่อได้
        crashBox = label("", 12f, Color.WHITE).apply {
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply { setColor(0xFF7F1D1D.toInt()); cornerRadius = dp(10f).toFloat() }
            setOnClickListener {
                val txt = Crash.read(this@MainActivity) ?: return@setOnClickListener
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("FrameGrab crash", txt))
                Toast.makeText(this@MainActivity, "คัดลอกแล้ว วางส่งให้ Claude ได้เลย", Toast.LENGTH_LONG).show()
                Crash.clear(this@MainActivity)
                refresh()
            }
        }
        col.addView(crashBox, gap(10f))

        // ===== โหมด 1: จับจากหน้าจอ (ปุ่มลอย) =====
        val shot = section(col, "จับจากหน้าจอ", "ปุ่มลอยเหนือ TikTok / IG / Facebook")
        startBtn = button("", 0xFF4F46E5.toInt()) { onStartClicked() }
        shot.addView(startBtn, gap(10f))
        reshareBtn = button("🔄 เปลี่ยนแอปที่แชร์ (แอปเดียว / ทั้งหน้าจอ)", 0xFF2A2D3A.toInt()) { reshare() }
        shot.addView(reshareBtn, gap(8f))
        // ภาพที่จับล่าสุด (แตะ = เปิดดูที่ภาพนั้น)
        shotStrip = strip()
        shot.addView(shotStrip.parent as android.view.View, gap(10f))
        clearBtn = button("", 0xFF3A2026.toInt()) { FrameStore.confirmClear(this) { refresh() } }
        shot.addView(clearBtn, gap(8f))
        // โหมดธนาคาร: ย้ายไปไว้ที่ปุ่มในแผงควบคุม (ดึงแถบแจ้งเตือนลง) — เอาออกจากหน้านี้ให้ไม่รก

        // ===== โหมด 2: แตกเฟรมจากไฟล์วิดีโอในเครื่อง =====
        val vid = section(col, "แตกเฟรมจากวิดีโอ", "เลือกคลิป → แตกทั้งคลิปทันที (หรือแชร์วิดีโอจากแกลเลอรีมาที่ FrameGrab)")
        // คลิปล่าสุดในเครื่อง (แตะ = แตกเลย) — ต้องอนุญาตให้อ่านวิดีโอก่อน
        videoPermBtn = button("แสดงคลิปล่าสุดในเครื่อง", 0xFF2A2D3A.toInt()) { askVideoPermission() }
        vid.addView(videoPermBtn, gap(10f))
        videoGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vid.addView(videoGrid, gap(10f))
        vid.addView(button("เลือกคลิปจากทั้งหมด", 0xFF2A2D3A.toInt()) { startActivity(Intent(this, VideoFramesActivity::class.java)) }, gap(8f))
        // แตกทุกกี่วินาที
        val everyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        everyRow.addView(label("แตกทุก", 15f, Color.WHITE), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12f) })
        for (sec in doubleArrayOf(0.1, 0.2)) {
            val b = button("$sec วิ", 0xFF2A2D3A.toInt()) { Prefs.setExtractEvery(this, sec); refresh() }
            b.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            everyBtns.add(sec to b)
            everyRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
        }
        vid.addView(everyRow, gap(10f))
        // ภาพจากวิดีโอล่าสุด (เก็บแยกจากภาพที่จับจากจอ)
        vidLabel = label("", 13f)
        vid.addView(vidLabel, gap(12f))
        vidStrip = strip()
        vid.addView(vidStrip.parent as android.view.View, gap(6f))

        col.addView(CheckBox(this).apply {
            text = "📸 จับภาพนี้ แล้วบันทึกลงแกลเลอรีอัตโนมัติ"
            setTextColor(Color.WHITE)
            isChecked = Prefs.autoSaveSingle(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoSaveSingle(this@MainActivity, v) }
        }, gap(14f))

        col.addView(CheckBox(this).apply {
            text = "แสดงปุ่มลอยเฉพาะใน TikTok, Instagram, Facebook"
            setTextColor(Color.WHITE)
            isChecked = Prefs.onlyInVideoApps(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setOnlyInVideoApps(this@MainActivity, v); refresh() }
        }, gap(10f))
        col.addView(CheckBox(this).apply {
            text = "แสดงเฉพาะตอนดูสตอรี่ (ซ่อนในหน้าฟีด / กำลังติดตาม / สำหรับคุณ)"
            setTextColor(Color.WHITE)
            isChecked = Prefs.storyOnly(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setStoryOnly(this@MainActivity, v) }
        }, gap(4f))
        appsPermBtn = button("🔓 อนุญาต \"การเข้าถึงข้อมูลการใช้งาน\" ให้ FrameGrab", 0xFF8A5A00.toInt()) {
            Toast.makeText(this, "เลือก FrameGrab แล้วเปิด \"อนุญาตการเข้าถึงข้อมูลการใช้งาน\"", Toast.LENGTH_LONG).show()
            try { startActivity(AppWatch.permissionIntent(this)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        col.addView(appsPermBtn, gap(6f))
        appsNote = label("", 12.5f)
        col.addView(appsNote, gap(2f))

        col.addView(label("ส่วนที่จะเก็บ", 16f, Color.WHITE), gap(26f))
        cropBtn = button("", 0xFF2A2D3A.toInt()) { choosePreset() }
        col.addView(cropBtn, gap(8f))
        col.addView(CheckBox(this).apply {
            text = "แสดงกรอบบนจอ (ซ่อนเองตอนจับภาพ ไม่ติดในภาพ)"
            setTextColor(Color.WHITE)
            isChecked = Prefs.showGuide(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setShowGuide(this@MainActivity, v) }
        }, gap(4f))
        col.addView(button("✏️ ตั้งกรอบเอง (ลากกรอบบนภาพหน้าจอ)", 0xFF2A2D3A.toInt()) {
            if (Cropper.refFile(this).isFile) startActivity(Intent(this, CropActivity::class.java))
            else Toast.makeText(this, "เปิดแอปวิดีโอก่อน แล้วแตะปุ่มลอย 🎞️ → ✂️ กรอบ → กำหนดเอง", Toast.LENGTH_LONG).show()
        }, gap(8f))
        col.addView(label(
            "ตัดได้เฉพาะส่วนที่อยู่นอกวิดีโอ (แถบสถานะ, แถบเมนูล่าง, ขอบดำ) ปุ่มหรือตัวหนังสือที่แอปวาดทับบนวิดีโอตัดออกไม่ได้", 12.5f
        ), gap(2f))

        col.addView(label("ตั้งค่าการอัดต่อเนื่อง", 16f, Color.WHITE), gap(26f))
        intervalBtn = button("", 0xFF2A2D3A.toInt()) {
            val cur = Prefs.interval(this)
            val i = Prefs.INTERVALS.indexOfFirst { it == cur }
            Prefs.setInterval(this, Prefs.INTERVALS[(i + 1) % Prefs.INTERVALS.size]); refresh()
        }
        col.addView(intervalBtn, gap(8f))
        maxBtn = button("", 0xFF2A2D3A.toInt()) {
            val cur = Prefs.maxSeconds(this)
            val i = Prefs.MAX_SECONDS.indexOfFirst { it == cur }
            Prefs.setMaxSeconds(this, Prefs.MAX_SECONDS[(i + 1) % Prefs.MAX_SECONDS.size]); refresh()
        }
        col.addView(maxBtn, gap(8f))
        val auto = CheckBox(this).apply {
            text = "หยุดอัดเองเมื่อคลิปจบ (วนกลับมาต้น)"
            setTextColor(Color.WHITE)
            isChecked = Prefs.autoStop(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoStop(this@MainActivity, v) }
        }
        col.addView(auto, gap(8f))
        val story = CheckBox(this).apply {
            text = "หยุดอัดเองเมื่อเลื่อนไปสตอรี่ถัดไป (ไม่เก็บภาพของเรื่องถัดไป)"
            setTextColor(Color.WHITE)
            isChecked = Prefs.stopOnNewStory(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setStopOnNewStory(this@MainActivity, v) }
        }
        col.addView(story, gap(8f))
        val dup = CheckBox(this).apply {
            text = "ข้ามภาพซ้ำ (ตอนวิดีโอค้าง หรือสตอรี่ที่เป็นรูปนิ่ง)"
            setTextColor(Color.WHITE)
            isChecked = Prefs.skipDuplicates(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setSkipDuplicates(this@MainActivity, v) }
        }
        col.addView(dup, gap(8f))
        fun box(t: String, get: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            text = t; setTextColor(Color.WHITE); isChecked = get
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        col.addView(box("แสดง ✓ ภาพที่บันทึกแล้ว และเตือนก่อนบันทึกซ้ำ", Prefs.saveMarks(this)) { Prefs.setSaveMarks(this, it) }, gap(8f))
        col.addView(box("สั่นตอนบันทึก (สำเร็จ = สั้นครั้งเดียว, ซ้ำ/พลาด = สองครั้ง)", Prefs.saveHaptic(this)) { Prefs.setSaveHaptic(this, it) }, gap(8f))
        col.addView(box("แอนิเมชันตอนบันทึก (ภาพวาบ + ป้าย ✓ เด้ง)", Prefs.saveAnim(this)) { Prefs.setSaveAnim(this, it) }, gap(8f))
        col.addView(box("🎥 หยุดอัดวิดีโอเองเมื่อภาพนิ่ง 10 วิ (ไลฟ์จบ)", Prefs.videoAutoStop(this)) { Prefs.setVideoAutoStop(this, it) }, gap(8f))
        replayBtn = button("", 0xFF2A2D3A.toInt()) {
            val cur = Prefs.replaySec(this); val arr = Prefs.REPLAY_SECS
            Prefs.setReplaySec(this, arr[(arr.indexOf(cur) + 1).let { if (it <= 0 || it >= arr.size) 0 else it }]); refresh()
        }
        col.addView(replayBtn, gap(8f))
        col.addView(box("ใส่ชื่อบัญชีจากหน้าจอในชื่อไฟล์ (ปิด = FrameGrab-วันที่-เลขรัน)", Prefs.nameFromScreen(this)) {
            Prefs.setNameFromScreen(this, it)
            if (it && HoldService.instance == null) askHoldPermission()
            refresh()
        }, gap(8f))
        // อ่านชื่อบัญชีต้องใช้สิทธิ์การช่วยเหลือพิเศษ — ไม่ได้เปิด/ระบบปิดไป = ชื่อไฟล์จะเป็น FrameGrab-...
        nameNote = TextView(this).apply {
            setTextColor(0xFFFFB74D.toInt()); textSize = 13f
            setPadding(dp(32f), 0, 0, dp(4f))
            setOnClickListener { askHoldPermission() }
        }
        col.addView(nameNote)
        col.addView(box("อ่านชื่อจากภาพแทน เมื่อการช่วยเหลือพิเศษไม่ทำงาน (ไทย/อังกฤษ)", Prefs.nameOcr(this)) {
            Prefs.setNameOcr(this, it); refresh()
        }, gap(4f))
        ocrNote = TextView(this).apply { setTextColor(0xFF9AA0A6.toInt()); textSize = 12f; setPadding(dp(32f), 0, 0, dp(4f)) }
        col.addView(ocrNote)
        col.addView(box("📋 เมนูคัดลอกข้อความบนจอ (แคปชัน/คอมเมนต์ที่กดคัดลอกไม่ได้)", Prefs.copyText(this)) {
            Prefs.setCopyText(this, it)
            if (it && !HoldService.isEnabled(this)) askHoldPermission()
        }, gap(8f))
        fun holdBox(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            text = t
            setTextColor(Color.WHITE)
            isChecked = get()
            setOnCheckedChangeListener { _, v ->
                set(v)
                if (v && !HoldService.isEnabled(this@MainActivity)) askHoldPermission()
            }
        }
        col.addView(holdBox("IG: กดค้างหน้าจอให้เองตอน 📸 จับภาพ (ไม่ติดชื่อโปรไฟล์)",
            { Prefs.holdShot(this) }, { Prefs.setHoldShot(this, it) }), gap(8f))
        val keep = CheckBox(this).apply {
            text = "แสดงปุ่ม ■ หยุด ระหว่างอัด"
            setTextColor(Color.WHITE)
            isChecked = Prefs.keepStopButton(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setKeepStopButton(this@MainActivity, v) }
        }
        col.addView(keep, gap(8f))
        col.addView(label(
            "ระหว่างอัด ปุ่มกลมจะเป็น ■ สีแดงพร้อมเวลาที่อัด แตะเพื่อหยุด ปุ่มจะย้ายไปนอกกรอบที่เก็บเอง " +
            "(ถ้ากรอบเป็นทั้งจอ ปุ่มอาจติดในภาพ)\n" +
            "ปิดตัวเลือกนี้ = ซ่อนปุ่มตลอดการอัด หยุดได้ที่แถบแจ้งเตือน", 12.5f
        ), gap(2f))

        col.addView(label("วิธีใช้", 16f, Color.WHITE), gap(26f))
        col.addView(label(
            "1. กด \"เปิดปุ่มลอย\" แล้วอนุญาต \"แสดงทับแอปอื่น\"\n" +
            "2. กดอนุญาตการจับภาพหน้าจอ\n" +
            "3. เปิด TikTok / IG / Facebook แล้วแตะปุ่ม 🎞️ (บรรทัดบนสุดของเมนูบอกสัดส่วนวิดีโอที่เล่นอยู่)\n" +
            "   📸 จับภาพนี้ — จับภาพทีละภาพ\n" +
            "   ⏺ อัดต่อเนื่อง — เล่นคลิปไปเรื่อย ๆ แล้วจับทุก 0.1–1 วิ (ตั้งได้)\n" +
            "4. อัดเสร็จจะเปิดหน้าดูภาพให้เอง: ‹ › หรือปัดซ้าย/ขวาเปลี่ยนภาพ, ถ่างนิ้วซูม, กดค้างที่ภาพ = บันทึก\n" +
            "   💾 บันทึกภาพนี้ (กดค้าง = ทั้งหมด) · ↗ เปิดด้วยแอปอื่น/เบราว์เซอร์ · 📤 แชร์ทั้งหมด\n" +
            "   🗑 ลบภาพนี้ (กดค้าง = ล้างทั้งหมด)\n\n" +
            "ภาพมาจากการจับหน้าจอ จึงได้ความละเอียดเท่าจอ และอาจมีข้อความหรือปุ่มของแอปนั้นติดมาด้วย", 13.5f
        ), gap(6f))

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF12141B.toInt()); addView(col) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (intent?.getBooleanExtra(EXTRA_RESHARE, false) == true) {
            intent.removeExtra(EXTRA_RESHARE)
            reshare()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** เลือกแอปที่จะแชร์ใหม่ (ระหว่างนี้ยังแชร์แอปเดิมอยู่ ถ้ายกเลิกก็ใช้ต่อได้) */
    private fun reshare() {
        if (!CaptureService.running) { onStartClicked(); return }
        resharing = true
        askCapture()
    }

    private fun refresh() {
        val crash = Crash.read(this)
        crashBox.visibility = if (crash != null) android.view.View.VISIBLE else android.view.View.GONE
        if (crash != null) crashBox.text = "⚠️ ครั้งก่อนแอปปิดเอง — แตะเพื่อคัดลอกรายละเอียด แล้ววางส่งให้ Claude\n\n" +
            crash.lineSequence().take(6).joinToString("\n")
        val onlyApps = Prefs.onlyInVideoApps(this)
        val usageOk = AppWatch.hasPermission(this)
        appsPermBtn.visibility = if (onlyApps && !usageOk) android.view.View.VISIBLE else android.view.View.GONE
        appsNote.text = when {
            !onlyApps -> "ปุ่มลอยจะแสดงในทุกแอป"
            usageOk -> "✅ ปุ่มลอยจะซ่อนเองเมื่อออกจาก TikTok / IG / Facebook"
            else -> "ต้องอนุญาตก่อน แอปถึงจะรู้ว่าตอนนี้เปิดแอปไหนอยู่ (ระหว่างนี้ปุ่มลอยจะแสดงทุกแอป)"
        }
        val holdOn = HoldService.isEnabled(this)
        val holdRunning = HoldService.instance != null
        nameNote.visibility = if (Prefs.nameFromScreen(this) && !holdRunning && !Prefs.nameOcr(this)) android.view.View.VISIBLE else android.view.View.GONE
        nameNote.text = if (holdOn) "⚠️ การช่วยเหลือพิเศษของ FrameGrab ถูกระบบหยุดไว้ ชื่อไฟล์จะเป็น FrameGrab-… — แตะเพื่อปิดแล้วเปิดใหม่"
            else "⚠️ ต้องเปิดการช่วยเหลือพิเศษให้ FrameGrab ก่อน ถึงจะอ่านชื่อบัญชีได้ (ตอนนี้ชื่อไฟล์จะเป็น FrameGrab-…) — แตะเพื่อเปิด"
        val lastOcr = Prefs.lastOcr(this)
        ocrNote.visibility = if (Prefs.nameOcr(this) && lastOcr != null) android.view.View.VISIBLE else android.view.View.GONE
        ocrNote.text = "ครั้งล่าสุด: ${lastOcr ?: ""}"
        val overlayOk = Settings.canDrawOverlays(this)
        status.text = when {
            CaptureService.running && CaptureService.parked -> "💤 ปุ่มลอยซ่อนอยู่ — กด ▶ เพื่อเลือกแอปที่จะแชร์แล้วเปิดปุ่มลอย"
            CaptureService.running -> "✅ ปุ่มลอยทำงานอยู่ — เปิดแอปที่ต้องการแล้วแตะปุ่ม 🎞️"
            !overlayOk -> "⚠️ ยังไม่ได้อนุญาต \"แสดงทับแอปอื่น\""
            else -> "พร้อมใช้งาน"
        }
        startBtn.text = if (CaptureService.running && !CaptureService.parked) "■ ปิดปุ่มลอย" else "▶ เปิดปุ่มลอย"
        reshareBtn.visibility = if (CaptureService.running) android.view.View.VISIBLE else android.view.View.GONE
        val every = Prefs.extractEvery(this)
        for ((sec, b) in everyBtns) (b.background as? GradientDrawable)?.setColor(if (sec == every) 0xFF4F46E5.toInt() else 0xFF2A2D3A.toInt())
        val vn = FrameStore.count(this, true)
        vidLabel.visibility = if (vn > 0) android.view.View.VISIBLE else android.view.View.GONE
        vidLabel.text = "ภาพจากคลิปล่าสุด ($vn ภาพ)"
        val n = FrameStore.count(this)
        fillFrames(shotStrip, false)
        fillFrames(vidStrip, true)
        val vOk = hasVideoPermission()
        videoPermBtn.visibility = if (vOk) android.view.View.GONE else android.view.View.VISIBLE
        if (vOk) fillVideos() else videoGrid.visibility = android.view.View.GONE
        clearBtn.text = if (n > 0) "🧹 ล้างภาพทั้งหมด ($n ภาพ · ${FrameStore.sizeLabel(FrameStore.totalBytes(this))})" else "🧹 ล้างภาพทั้งหมด"
        clearBtn.alpha = if (n > 0) 1f else 0.5f
        intervalBtn.text = "⏱ จับทุก ${Prefs.intervalLabel(Prefs.interval(this))} วินาที  (แตะเพื่อเปลี่ยน)"
        cropBtn.text = "✂️ กรอบ: ${Cropper.presetLabel(this, Prefs.cropPreset(this))}  (แตะเพื่อเลือก)"
        maxBtn.text = "⌛ อัดนานสุด ${Prefs.maxLabel(Prefs.maxSeconds(this))}  (แตะเพื่อเปลี่ยน)"
        replayBtn.text = "⟲ บันทึกย้อนหลัง ${Prefs.replaySec(this)} วิ  (แตะเพื่อเปลี่ยน)"
    }

    /** เลือกกรอบจากรายการ (ทั้งจอ, อัตโนมัติ, 9:16, 3:4, 1:1 …) */
    private fun choosePreset() {
        val cur = Prefs.cropPreset(this)
        val items = Cropper.PRESETS.map { p ->
            val shape = when {
                p.rw == 0 && p.id == "full" -> "📱"
                p.id == "auto" -> "✨"
                p.id == "custom" -> "✏️"
                p.rw > p.rh -> "▭"
                p.rw < p.rh -> "▯"
                else -> "□"
            }
            (if (p.id == cur) "✓ " else "    ") + "$shape  ${p.label}" + (if (Cropper.isPrimary(p.id)) "  ★ ใช้บ่อย" else "")
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("เลือกกรอบที่จะเก็บ")
            .setItems(items) { _, i ->
                val p = Cropper.PRESETS[i]
                if (p.id == "custom" && !Prefs.hasCustomCrop(this)) {
                    if (Cropper.refFile(this).isFile) startActivity(Intent(this, CropActivity::class.java))
                    else Toast.makeText(this, "เปิดแอปวิดีโอก่อน แล้วแตะปุ่มลอย 🎞️ → ✂️ กรอบ → กำหนดเอง", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setCropPreset(this, p.id)
                    refresh()
                }
            }
            .show()
    }

    /** เปิดสิทธิ์ "การช่วยเหลือพิเศษ" ให้ FrameGrab กดค้างหน้าจอแทนได้ */
    private fun bankMode() {
        val r = BankMode.run(this)
        val b = android.app.AlertDialog.Builder(this)
            .setTitle("🏦 โหมดธนาคาร")
            .setMessage(r.joinToString("\n\n") + "\n\nใช้ FrameGrab ใหม่: กด ▶ เปิดปุ่มลอย (ฟีเจอร์กดค้างใน IG / คัดลอกข้อความ ต้องเปิดการช่วยเหลือพิเศษใหม่เอง)")
            .setPositiveButton("ตกลง", null)
        if (HoldService.isEnabled(this) && HoldService.instance == null)
            b.setNeutralButton("ไปปิดการช่วยเหลือพิเศษ") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        else if (Settings.canDrawOverlays(this))
            b.setNeutralButton("ปิดแสดงทับแอปอื่น") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        b.show()
        status.postDelayed({ refresh() }, 400)
    }

    private fun askHoldPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle("ต้องเปิดสิทธิ์ครั้งเดียว")
            .setMessage("ให้ FrameGrab อ่านชื่อบัญชีบนจอมาตั้งชื่อไฟล์ / กดค้างหน้าจอแทนคุณ / อ่านข้อความบนจอให้คัดลอกได้:\n\n" +
                "1) แตะ \"เปิดการช่วยเหลือพิเศษ\" → หา FrameGrab → เปิด\n\n" +
                "ถ้าเปิดไม่ได้ (ขึ้นว่าการตั้งค่าถูกจำกัด):\n" +
                "2) แตะ \"ข้อมูลแอป\" → ⋮ มุมขวาบน → \"อนุญาตการตั้งค่าที่จำกัด\" แล้วทำข้อ 1 ใหม่")
            .setPositiveButton("เปิดการช่วยเหลือพิเศษ") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("ข้อมูลแอป") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("ไว้ทีหลัง", null)
            .show()
    }

    private fun onStartClicked() {
        if (CaptureService.running) {
            // ปุ่มลอยซ่อนอยู่: เปิดใหม่ = ให้เลือกแอปที่จะแชร์ใหม่ทันที (ยกเลิก = ยังใช้แอปเดิม)
            if (CaptureService.parked) { reshare(); return }
            val act = CaptureService.ACTION_PARK
            startService(Intent(this, CaptureService::class.java).setAction(act)
                .putExtra(CaptureService.EXTRA_EXIT_APP, false))
            status.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "เปิด \"อนุญาตให้แสดงทับแอปอื่น\" ให้ FrameGrab แล้วกดกลับมา", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        // ให้เลือกเองได้ "แอปเดียว" (ไม่ติดแจ้งเตือน/ปุ่มระบบในภาพ) หรือ "ทั้งหน้าจอ"
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    // ---------- กล่องแยกโหมด ----------
    private fun section(parent: LinearLayout, title: String, sub: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(16f))
            background = GradientDrawable().apply { setColor(0xFF181B26.toInt()); cornerRadius = dp(16f).toFloat(); setStroke(dp(1f), 0xFF2C3142.toInt()) }
        }
        box.addView(label(title, 18f, Color.WHITE))
        box.addView(label(sub, 12.5f))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        return box
    }

    /** แถวภาพเล็กเลื่อนแนวนอน (คืนแถวด้านใน; ตัวเลื่อนคือ parent) */
    private fun strip(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        return row
    }

    private fun tile(onClick: () -> Unit) = android.widget.FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(68f), dp(120f)).apply { marginEnd = dp(6f) }
        background = GradientDrawable().apply { setColor(0xFF2A2D3A.toInt()); cornerRadius = dp(8f).toFloat() }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        setOnClickListener { onClick() }
    }

    private fun moreTile(text: String, onClick: () -> Unit) = tile(onClick).apply {
        addView(label(text, 13f, Color.WHITE).apply { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private fun img(): android.widget.ImageView = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }

    /** ตัวอย่างภาพเดียวของชุด (จับจากจอ = ภาพล่าสุด / จากวิดีโอ = ภาพแรก) + ช่อง "ดูทั้งหมด" */
    private fun fillFrames(row: LinearLayout, video: Boolean) {
        val frames = FrameStore.list(this, video)
        val scroller = row.parent as android.view.View
        row.removeAllViews()
        if (frames.isEmpty()) { scroller.visibility = android.view.View.GONE; return }
        scroller.visibility = android.view.View.VISIBLE
        val open = { name: String? ->
            if (name != null) Prefs.setViewerLast(this, name)
            startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_VIDEO, video))
        }
        // จับจากจอ: ใหม่สุดก่อน / จากวิดีโอ: เรียงตามเวลาในคลิป
        val show = if (video) frames.take(1) else frames.takeLast(1)   // ตัวอย่างภาพเดียวพอ
        for (fr in show) {
            val t = tile { open(fr.file.name) }
            val iv = img(); t.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
            row.addView(t)
            thumbPool.execute {
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                val b = try { android.graphics.BitmapFactory.decodeFile(fr.file.absolutePath, o) } catch (e: Exception) { null }
                if (b != null) ui.post { iv.setImageBitmap(b) }
            }
        }
        row.addView(moreTile("ดูทั้งหมด\n${frames.size}") { open(null) })
    }

    // ---------- คลิปล่าสุดในเครื่อง ----------
    private val videoPerm get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasVideoPermission(): Boolean = checkSelfPermission(videoPerm) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)
    private fun askVideoPermission() {
        val p = if (Build.VERSION.SDK_INT >= 34) arrayOf(videoPerm, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") else arrayOf(videoPerm)
        requestPermissions(p, REQ_VIDEO)
    }

    /** ช่องสัดส่วน 3:4 (กว้างตามน้ำหนักในแถว สูง = กว้าง × 4/3) */
    private inner class Cell : android.widget.FrameLayout(this@MainActivity) {
        override fun onMeasure(w: Int, h: Int) {
            val width = android.view.View.MeasureSpec.getSize(w)
            super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(width * 4 / 3, android.view.View.MeasureSpec.EXACTLY))
        }
    }

    /** ไอคอนเล่น (วงกลมโปร่ง + สามเหลี่ยมเส้น) วาดเอง ไม่ใช้อีโมจิ */
    private inner class PlayIcon : android.view.View(this@MainActivity) {
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(1.6f).toFloat(); strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val r = minOf(width, height) / 2f; val cx = width / 2f; val cy = height / 2f
            c.drawCircle(cx, cy, r, bg)
            val t = r * 0.42f
            val p = android.graphics.Path().apply { moveTo(cx - t * 0.6f, cy - t); lineTo(cx + t, cy); lineTo(cx - t * 0.6f, cy + t); close() }
            c.drawPath(p, fg)
        }
    }

    /** คลิปล่าสุดในเครื่อง: ตาราง 4 ช่อง × 2 แถว (ภาพตัวอย่าง + ความยาว) แตะ = แตกเลย */
    private fun fillVideos() {
        val grid = videoGrid
        thumbPool.execute {
            data class V(val uri: Uri, val durMs: Long, val addedSec: Long)
            val list = ArrayList<V>()
            try {
                contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DURATION, android.provider.MediaStore.Video.Media.DATE_ADDED),
                    null, null, android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext() && list.size < 8) {
                        list.add(V(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getLong(1), c.getLong(2)))
                    }
                }
            } catch (e: Exception) { }
            ui.post {
                grid.removeAllViews()
                grid.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                var row: LinearLayout? = null
                for ((i, v) in list.withIndex()) {
                    if (i % 4 == 0) {
                        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(6f) })
                    }
                    val cell = Cell().apply {
                        background = GradientDrawable().apply { setColor(0xFF2A2D3A.toInt()); cornerRadius = dp(10f).toFloat() }
                        clipToOutline = true
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        setOnClickListener { startActivity(Intent(this@MainActivity, VideoFramesActivity::class.java).setAction(Intent.ACTION_VIEW).setData(v.uri)) }
                    }
                    val iv = img(); cell.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
                    cell.addView(PlayIcon(), android.widget.FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
                    val sec = v.durMs / 1000
                    cell.addView(label(String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60), 11f, Color.WHITE).apply {
                        setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(0, 0, dp(6f), dp(4f))
                    }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
                    row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 4 > 0) marginStart = dp(6f) })
                    thumbPool.execute {
                        val b = try { contentResolver.loadThumbnail(v.uri, android.util.Size(dp(120f), dp(160f)), null) } catch (e: Exception) { null }
                        if (b != null) ui.post { iv.setImageBitmap(b) }
                    }
                }
                // แถวสุดท้ายไม่เต็ม: เติมช่องว่างให้ขนาดเท่ากัน
                val rem = list.size % 4
                if (rem > 0) repeat(4 - rem) { row?.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6f) }) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VIDEO) { refresh(); return }
        if (requestCode == REQ_NOTIF) askCapture() // ไม่อนุญาตแจ้งเตือนก็ใช้ได้ แค่ไม่เห็นปุ่มหยุดในแถบแจ้งเตือน
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val wasReshare = resharing
        resharing = false
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, if (wasReshare && CaptureService.running) "ยกเลิก — ยังแชร์แอปเดิมอยู่"
                else "ต้องอนุญาตการจับภาพหน้าจอ ปุ่มลอยจึงจะทำงานได้", Toast.LENGTH_LONG).show()
            if (wasReshare) moveTaskToBack(true)
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
        )
        Toast.makeText(this, "เปิดปุ่มลอยแล้ว เปิดแอปที่ต้องการได้เลย", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        OwnActivities.remove(this)
        super.onDestroy()
    }
}
