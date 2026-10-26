package com.v2f.ankirounds

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * บริการช่วยเหลือพิเศษ: แสดงปุ่มลอยและกดแทนใน AnkiDroid เมื่อผู้ใช้สั่งเท่านั้น
 *  - ปุ่มลอยแสดงจำนวนการ์ดที่เหลือในรอบนี้ (ครบแล้วเปลี่ยนเป็น ➕ และกะพริบ)
 *  - แสดงเฉพาะตอนเปิด AnkiDroid (ตั้งค่าได้)
 *  - แตะ = อีก 1 รอบ · ลาก = ย้าย · กดค้าง = เมนู
 */
class RoundsService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    @Volatile private var busy = false

    private var ankiInFront = false
    private var remaining = -1          // -1 = ไม่รู้ (ยังไม่ได้รับสิทธิ์อ่าน AnkiDroid)
    private var lastCountAt = 0L
    @Volatile private var counting = false

    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        refreshBubble()
        refreshTiles()
        main.post(countLoop)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hideMenu()
        removeBubble()
        if (instance === this) instance = null
        refreshTiles()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun refreshTiles() {
        for (c in listOf(RoundsTile::class.java, BankTile::class.java)) {
            try { TileService.requestListeningState(this, ComponentName(this, c)) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == packageName || pkg == "com.android.systemui" || pkg == currentIme()) return
            val now = pkg == Prefs.ANKI_PKG
            if (now != ankiInFront) {
                ankiInFront = now
                refreshBubble()
                if (now) requestCount(0)
            }
        } else if (pkg == Prefs.ANKI_PKG) {
            requestCount(1500)       // เนื้อหาใน AnkiDroid เปลี่ยน (เช่นตอบการ์ดไป 1 ใบ)
        }
    }

    private fun currentIme(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') ?: ""

    // ------------------------------------------------------------------ remaining cards

    private val countLoop = object : Runnable {
        override fun run() {
            if (ankiInFront && bubble != null) requestCount(0)
            main.postDelayed(this, 5000)
        }
    }

    private fun requestCount(minGapMs: Long) {
        if (!AnkiApi.hasPermission(this) || counting || busy) return
        if (System.currentTimeMillis() - lastCountAt < minGapMs) return
        counting = true
        lastCountAt = System.currentTimeMillis()
        val names = prefs.decks().map { it.name }
        Thread {
            var n = -1
            try {
                val all = AnkiApi.decks(this)
                val ids = HashSet<Long>()
                n = 0
                for (name in names) {
                    val d = AnkiApi.find(all, name) ?: continue
                    if (ids.add(d.id)) n += d.due
                }
            } catch (_: Exception) {
            }
            main.post {
                counting = false
                val before = remaining
                remaining = n
                updateBubbleFace()
                if (before > 0 && n == 0) { wake(); pulse() }   // เพิ่งท่องครบ → เรียกให้เห็น
            }
        }.start()
    }

    // ------------------------------------------------------------------ jobs

    /** ➕ อีก 1 รอบ */
    fun bump() = run(prefs.round + 1, "รอบ ${prefs.round + 1}")

    /** ↺ เริ่มชุดใหม่ */
    fun reset() = run(1, "เริ่มชุดใหม่")

    private fun run(newRound: Int, title: String) {
        hideMenu()
        if (busy) { toast("กำลังทำงานอยู่ รอสักครู่"); return }
        val decks = prefs.decks()
        if (decks.isEmpty()) { toast("ยังไม่ได้ตั้งชื่อ deck ในแอป AnkiRounds"); return }
        busy = true
        updateBubbleFace()
        val targets = dedupeByPreset(decks.map { it.name to prefs.valueFor(it, newRound) })
        Thread {
            val a = Automator(this, prefs)
            var ok = false
            var msg: String
            try {
                a.apply(targets)
                val bad = verify(targets, a)
                if (bad.isEmpty()) {
                    ok = true
                    msg = "$title ✓  ${targets.joinToString(" / ") { it.second.toString() }}"
                } else {
                    msg = "บันทึกไม่ติด: " + bad.joinToString(", ")
                }
            } catch (e: Automator.StepFailed) {
                msg = "ทำไม่สำเร็จ: ${e.message}"
            } catch (e: Exception) {
                a.log("✗ ${e.javaClass.simpleName}: ${e.message}")
                msg = "ทำไม่สำเร็จ: ${e.message}"
            }
            prefs.lastLog = a.logText()
            main.post {
                if (ok) prefs.round = newRound
                busy = false
                lastCountAt = 0
                refreshBubble()
                requestCount(0)
                refreshTiles()
                toast(msg + if (ok) "" else "\nกดค้างที่ปุ่มลอย → ⚙️ ตั้งค่า → ส่ง log ให้ผู้ช่วยดูได้")
            }
        }.start()
    }

    /** อ่านค่าจาก AnkiDroid กลับมาเช็กว่าบันทึกเป็นตัวเลขใหม่จริง  คืนรายการที่ไม่ตรง */
    private fun verify(targets: List<Pair<String, Int>>, a: Automator): List<String> {
        if (!AnkiApi.hasPermission(this)) {
            a.log("ข้ามการตรวจผล (ยังไม่ได้อนุญาตให้อ่าน AnkiDroid)")
            return emptyList()
        }
        var bad = emptyList<String>()
        repeat(3) { attempt ->
            Thread.sleep(if (attempt == 0) 600 else 1200)
            val all = try { AnkiApi.decks(this) } catch (e: Exception) {
                a.log("ตรวจผลไม่ได้: ${e.message}"); return emptyList()
            }
            bad = targets.mapNotNull { (name, want) ->
                val d = AnkiApi.find(all, name)
                when {
                    d == null -> null
                    d.reviewsPerDay == want -> null
                    else -> "${d.shortName} ยังเป็น ${d.reviewsPerDay} (ควรเป็น $want)"
                }
            }
            if (bad.isEmpty()) {
                a.log("ตรวจผล ✓ ค่าใน AnkiDroid ตรงทุก deck")
                return bad
            }
        }
        a.log("ตรวจผล ✗ " + bad.joinToString(", "))
        return bad
    }

    /** deck ที่ใช้ preset เดียวกัน แก้ครั้งเดียวพอ (ใช้ค่าที่มากที่สุด) */
    private fun dedupeByPreset(targets: List<Pair<String, Int>>): List<Pair<String, Int>> {
        if (!AnkiApi.hasPermission(this)) return targets
        val all = try { AnkiApi.decks(this) } catch (_: Exception) { return targets }
        val best = LinkedHashMap<Long, Pair<String, Int>>()
        val out = ArrayList<Pair<String, Int>>()
        for (t in targets) {
            val info = AnkiApi.find(all, t.first)
            if (info == null || info.presetId < 0) { out.add(t); continue }
            val cur = best[info.presetId]
            if (cur == null || t.second > cur.second) best[info.presetId] = t
        }
        out.addAll(best.values)
        return out
    }

    /** บันทึกโครงหน้าจอปัจจุบัน (หลังนับถอยหลัง) */
    fun dumpAfter(delayMs: Long) {
        toast("อีก ${delayMs / 1000} วินาทีจะบันทึกโครงหน้าจอ สลับไปหน้าที่ต้องการได้เลย")
        main.postDelayed({
            Thread {
                val text = Automator(this, prefs).dump()
                prefs.lastLog = "---- โครงหน้าจอ ----\n$text"
                main.post { toast("บันทึกโครงหน้าจอแล้ว เปิดแอป AnkiRounds → ส่ง log") }
            }.start()
        }, delayMs)
    }

    // ------------------------------------------------------------------ bubble

    private fun shouldShow(): Boolean =
        prefs.bubble && (!prefs.onlyInAnki || ankiInFront || busy || menu != null)

    fun refreshBubble() {
        if (!::prefs.isInitialized) return
        if (shouldShow()) showBubble() else { hideMenu(); removeBubble() }
        updateBubbleFace()
        wake()
    }

    private fun updateBubbleFace() {
        val v = bubble ?: return
        val done = remaining == 0
        v.text = when {
            busy -> "⏳"
            remaining > 0 -> remaining.toString()
            else -> "➕"
        }
        v.textSize = if (remaining > 0 && !busy) 20f else 22f
        (v.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (done && !busy) "#E616A34A" else "#E62563EB"))
        v.contentDescription = if (remaining > 0) "เหลือ $remaining ใบ" else "อีก 1 รอบ"
    }

    // จางลงเองเมื่อไม่ได้ใช้ 3 วินาที (แตะเมื่อไหร่ก็กลับมาชัด)
    private val fade = Runnable {
        if (menu == null) bubble?.animate()?.alpha(0.25f)?.setDuration(600)?.start()
    }

    private fun wake() {
        val v = bubble ?: return
        main.removeCallbacks(fade)
        v.animate().cancel()
        v.alpha = 0.9f
        if (prefs.bubbleFade && !busy) main.postDelayed(fade, if (remaining == 0) 6000 else 3000)
    }

    private fun pulse() {
        val v = bubble ?: return
        v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(180).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
                v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(160).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
            }.start()
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = (56 * resources.displayMetrics.density).toInt()
        val v = TextView(this).apply {
            text = "➕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E62563EB"))
            }
            alpha = 0.9f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longFired = false
        val longPress = Runnable { longFired = true; showMenu() }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(fade); v.animate().cancel(); v.alpha = 0.95f
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 12 || abs(dy) > 12)) {
                        moved = true; main.removeCallbacks(longPress); hideMenu()
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    wake()
                    if (moved) {
                        prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
                    } else if (!longFired && e.action == MotionEvent.ACTION_UP) {
                        if (menu != null) hideMenu() else bump()     // แตะ = อีก 1 รอบ
                    }
                }
            }
            true
        }
        try {
            wm.addView(v, lp)
            bubble = v
            bubbleLp = lp
            updateBubbleFace()
            wake()
        } catch (e: Exception) {
            toast("แสดงปุ่มลอยไม่ได้: ${e.message}")
        }
    }

    private fun removeBubble() {
        val v = bubble ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        bubble = null
        bubbleLp = null
    }

    // ------------------------------------------------------------------ long-press menu

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        hideMenu()
        val blp = bubbleLp ?: return
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(Color.parseColor("#F01E222A"))
            }
        }
        var confirmReset = false
        fun item(text: String, onTap: (TextView) -> Unit) {
            val t = TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding((14 * dp).toInt(), (11 * dp).toInt(), (18 * dp).toInt(), (11 * dp).toInt())
                setOnClickListener { onTap(this) }
            }
            box.addView(t)
        }
        val next = prefs.summary(prefs.round + 1)
        item("➕  อีก 1 รอบ  → $next") { bump() }
        item("↺  เริ่มชุดใหม่") { tv ->
            if (!confirmReset) {
                confirmReset = true
                tv.text = "↺  แตะอีกครั้งเพื่อยืนยัน (${prefs.summary(1)})"
                tv.setTextColor(Color.parseColor("#FBBF24"))
                main.removeCallbacks(autoClose); main.postDelayed(autoClose, 5000)
            } else reset()
        }
        item("🏦  โหมดธนาคาร (ปิดผู้ช่วย)") { hideMenu(); Bank.enterBankMode(this) }
        item("⚙️  ตั้งค่า") { hideMenu(); openApp() }
        item("✕  ปิดเมนู") { hideMenu() }

        val screenW = resources.displayMetrics.widthPixels
        val bubbleSize = (56 * dp).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val onRight = blp.x > screenW / 2
            x = if (onRight) (blp.x - 280 * dp).toInt().coerceAtLeast(0) else blp.x + bubbleSize + (8 * dp).toInt()
            y = blp.y
        }
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false
        }
        try {
            wm.addView(box, lp)
            menu = box
            main.removeCallbacks(fade)
            bubble?.alpha = 0.95f
            main.removeCallbacks(autoClose); main.postDelayed(autoClose, 6000)
        } catch (e: Exception) {
            toast("เปิดเมนูไม่ได้: ${e.message}")
        }
    }

    private val autoClose = Runnable { hideMenu() }

    private fun hideMenu() {
        val m = menu ?: return
        main.removeCallbacks(autoClose)
        try { wm.removeView(m) } catch (_: Exception) {}
        menu = null
        if (::prefs.isInitialized && !shouldShow()) removeBubble() else wake()
    }

    // ------------------------------------------------------------------ misc

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    }

    companion object {
        @Volatile var instance: RoundsService? = null
    }
}
