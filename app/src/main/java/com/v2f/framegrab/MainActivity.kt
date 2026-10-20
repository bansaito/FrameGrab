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
        /** เปิดมาจากแจ้งเตือน "🔄 เปลี่ยนแอป" */
        const val EXTRA_RESHARE = "reshare"
    }

    private lateinit var status: TextView
    private lateinit var startBtn: TextView
    private lateinit var reshareBtn: TextView
    private var resharing = false
    private lateinit var viewBtn: TextView
    private lateinit var intervalBtn: TextView
    private lateinit var maxBtn: TextView
    private lateinit var crashBox: TextView
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

        startBtn = button("", 0xFF4F46E5.toInt()) { onStartClicked() }
        col.addView(startBtn, gap(12f))
        reshareBtn = button("🔄 เปลี่ยนแอปที่แชร์ (แอปเดียว / ทั้งหน้าจอ)", 0xFF2A2D3A.toInt()) { reshare() }
        col.addView(reshareBtn, gap(10f))
        viewBtn = button("", 0xFF2A2D3A.toInt()) { startActivity(Intent(this, ViewerActivity::class.java)) }
        col.addView(viewBtn, gap(10f))
        clearBtn = button("", 0xFF3A2026.toInt()) { FrameStore.confirmClear(this) { refresh() } }
        col.addView(clearBtn, gap(10f))

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
            "   ⏺ อัดต่อเนื่อง — เล่นคลิปไปเรื่อย ๆ แล้วจับทุก 0.25 วิ\n" +
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
        val overlayOk = Settings.canDrawOverlays(this)
        status.text = when {
            CaptureService.running && CaptureService.parked -> "💤 ปุ่มลอยซ่อนอยู่ — กด ▶ เปิดได้ทันที ไม่ต้องกดยืนยัน"
            CaptureService.running -> "✅ ปุ่มลอยทำงานอยู่ — เปิดแอปที่ต้องการแล้วแตะปุ่ม 🎞️"
            !overlayOk -> "⚠️ ยังไม่ได้อนุญาต \"แสดงทับแอปอื่น\""
            else -> "พร้อมใช้งาน"
        }
        startBtn.text = if (CaptureService.running && !CaptureService.parked) "■ ปิดปุ่มลอย" else "▶ เปิดปุ่มลอย"
        reshareBtn.visibility = if (CaptureService.running) android.view.View.VISIBLE else android.view.View.GONE
        val n = FrameStore.count(this)
        viewBtn.text = "🖼 ดูภาพที่จับไว้ ($n)"
        clearBtn.text = if (n > 0) "🧹 ล้างภาพทั้งหมด ($n ภาพ · ${FrameStore.sizeLabel(FrameStore.totalBytes(this))})" else "🧹 ล้างภาพทั้งหมด"
        clearBtn.alpha = if (n > 0) 1f else 0.5f
        intervalBtn.text = "⏱ จับทุก ${Prefs.intervalLabel(Prefs.interval(this))} วินาที  (แตะเพื่อเปลี่ยน)"
        cropBtn.text = "✂️ กรอบ: ${Cropper.presetLabel(this, Prefs.cropPreset(this))}  (แตะเพื่อเลือก)"
        maxBtn.text = "⌛ อัดนานสุด ${Prefs.maxLabel(Prefs.maxSeconds(this))}  (แตะเพื่อเปลี่ยน)"
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

    private fun onStartClicked() {
        if (CaptureService.running) {
            // ยังจับหน้าจอค้างอยู่: เปิด/ซ่อนปุ่มลอยได้ทันที ไม่ต้องกดยืนยันใหม่
            val act = if (CaptureService.parked) CaptureService.ACTION_UNPARK else CaptureService.ACTION_PARK
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
}
