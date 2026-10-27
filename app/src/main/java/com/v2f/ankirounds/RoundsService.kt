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
 *  - แตะ (หรือกดค้าง) = เมนูให้เลือกก่อน · ลาก = ย้าย
 */
class RoundsService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    @Volatile private var busy = false

    private var ankiInFront = false
    private var remaining = -1          // รวมทุกชุดที่แสดง · -1 = ไม่รู้ (ยังไม่ได้รับสิทธิ์อ่าน AnkiDroid)
    private val remainingBy = HashMap<Int, Int>()
    private var roundTotal = 0
    private var lastCountAt = 0L
    @Volatile private var counting = false

    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        Parking.hideBankOff(this)
        refreshBubble()
        refreshTiles()
        main.post(countLoop)
        if (prefs.parked) Parking.show(this)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Parking.hide(this)
        hideTrash()
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
            if (prefs.featAutoBank && !busy && pkg in prefs.bankApps) {
                Parking.showBankOff(this, appLabel(pkg))
                Bank.enterBankMode(this)
                return
            }
            val now = pkg == Prefs.ANKI_PKG
            if (now) classify(event.className?.toString() ?: "")
            if (now != ankiInFront) {
                ankiInFront = now
                refreshBubble()
                if (now) requestCount(0)
            }
        } else if (pkg == Prefs.ANKI_PKG) {
            if (screen == Screen.UNKNOWN && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                main.removeCallbacks(checkScreen); main.postDelayed(checkScreen, 400)
            }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                if (prefs.featScrollHide && menu == null && !busy) {
                    bubble?.let { b -> main.removeCallbacks(fade); b.animate().cancel(); b.alpha = 0f }
                    main.removeCallbacks(afterScroll); main.postDelayed(afterScroll, 900)
                }
                return
            }
            requestCount(1500)       // เนื้อหาใน AnkiDroid เปลี่ยน (เช่นตอบการ์ดไป 1 ใบ)
        }
    }

    private val afterScroll = Runnable { wake() }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ------------------------------------------------------------------ which AnkiDroid screen

    private enum class Screen { HOME, CONGRATS, OTHER, UNKNOWN }
    private var screen = Screen.HOME

    /** แยกหน้าจาก class ของหน้าต่าง (dialog/เมนูเล็ก ๆ ไม่นับ คงสถานะเดิมไว้) */
    private fun classify(cls: String) {
        if (!cls.startsWith("com.ichi2")) return
        val s = when {
            cls.endsWith(".DeckPicker") -> Screen.HOME
            cls.contains("StudyOptions") || cls.contains("Congrats") -> Screen.CONGRATS
            cls.contains("Reviewer") || cls.contains("Previewer") -> Screen.OTHER
            else -> Screen.UNKNOWN          // เช่น SingleFragmentActivity: ดูจากเนื้อหาบนจอ
        }
        setScreen(s)
        if (s == Screen.UNKNOWN) { main.removeCallbacks(checkScreen); main.postDelayed(checkScreen, 400) }
    }

    private val checkScreen = Runnable {
        val root = rootInActiveWindow ?: return@Runnable
        if (root.packageName?.toString() != Prefs.ANKI_PKG) return@Runnable
        val words = listOf("congratulations", "ยินดีด้วย", "custom study", "เรียนแบบกำหนดเอง")
        val answer = listOf("show answer", "แสดงคำตอบ")
        var congrats = false; var reviewing = false
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || congrats) return
            val t = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: ""
            if (t.isNotEmpty()) {
                if (words.any { t.contains(it) }) congrats = true
                if (answer.any { t == it }) reviewing = true
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        setScreen(when { congrats -> Screen.CONGRATS; reviewing -> Screen.OTHER; else -> Screen.UNKNOWN })
    }

    private fun setScreen(s: Screen) {
        if (s == screen) return
        screen = s
        refreshBubble()
        if (s == Screen.HOME || s == Screen.CONGRATS) requestCount(0)
    }

    private fun screenOk(): Boolean =
        !prefs.onlyHome || screen == Screen.HOME || screen == Screen.CONGRATS

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
        val groups = prefs.visibleGroups().associateWith { g -> prefs.decks(g).map { it.name } }
        Thread {
            var total = -1
            val by = HashMap<Int, Int>()
            try {
                val all = AnkiApi.decks(this)
                val seen = HashSet<Long>()
                total = 0
                for ((g, names) in groups) {
                    var n = 0
                    val ids = HashSet<Long>()
                    for (name in names) {
                        val d = AnkiApi.find(all, name) ?: continue
                        if (ids.add(d.id)) n += d.due
                        if (seen.add(d.id)) total += d.due
                    }
                    by[g] = n
                }
            } catch (_: Exception) {
            }
            main.post {
                counting = false
                val before = remaining
                remaining = total
                // จำนวนการ์ดตอนเริ่มรอบ (ใช้วาดวงแหวน): ตั้งใหม่เมื่อจำนวนเพิ่มขึ้น (เพิ่งกด ➕ / ขึ้นวันใหม่)
                if (total > roundTotal || before <= 0) roundTotal = total
                remainingBy.clear(); remainingBy.putAll(by)
                updateBubbleFace()
                if (before > 0 && total == 0) { wake(); pulse() }   // เพิ่งท่องครบ → เรียกให้เห็น
            }
        }.start()
    }

    // ------------------------------------------------------------------ jobs

    private enum class Kind { BUMP, UNDO, RESET }

    /** ➕ อีก 1 รอบ: ค่าปัจจุบันใน AnkiDroid + ค่าเพิ่ม */
    fun bump(g: Int = prefs.lastGroup) = run(g, Kind.BUMP)

    /** ↶ ย้อน 1 รอบ: ค่าปัจจุบัน − ค่าเพิ่ม */
    fun undo(g: Int = prefs.lastGroup) {
        if (prefs.round(g) <= 1) { toast("ยังไม่ได้เพิ่มรอบ ไม่มีอะไรให้ย้อน"); return }
        run(g, Kind.UNDO)
    }

    /** ↺ เริ่มใหม่: กลับเป็นค่าที่จำไว้ตอนกด ➕ ครั้งแรกของชุด */
    fun reset(g: Int = prefs.lastGroup) = run(g, Kind.RESET)

    private fun run(g: Int, kind: Kind) {
        hideMenu()
        if (busy) { toast("กำลังทำงานอยู่ รอสักครู่"); return }
        val decks = prefs.decks(g)
        if (decks.isEmpty()) { toast("ยังไม่ได้เลือก deck ใน ${prefs.groupName(g)}"); return }
        if (!AnkiApi.hasPermission(this)) {
            toast("ต้องอนุญาตให้อ่าน AnkiDroid ก่อน (แอปจะได้รู้ค่าปัจจุบัน)")
            openApp(); return
        }
        busy = true
        updateBubbleFace()
        Thread {
            val a = Automator(this, prefs)
            var ok = false
            var msg = ""
            try {
                val all = AnkiApi.decks(this)
                val raw = ArrayList<Pair<String, Int>>()
                for ((i, d) in prefs.deckSlots(g)) {
                    val info = AnkiApi.find(all, d.name)
                        ?: throw Automator.StepFailed("ไม่พบ deck \"${d.name}\" ใน AnkiDroid")
                    val cur = info.reviewsPerDay
                    val start = d.base                 // ค่าเริ่ม (ตั้งในแอป ค่าเริ่มต้น 2/1/1) ใช้กับ ↺
                    val want = when (kind) {
                        Kind.BUMP -> cur + d.step
                        Kind.UNDO -> (cur - d.step).coerceAtLeast(start)
                        Kind.RESET -> start
                    }
                    raw.add(d.name to want)
                }
                val targets = dedupeByPreset(raw)
                a.log("ค่าใหม่: " + targets.joinToString { "${it.first}=${it.second}" })
                val shortcut = if (screen == Screen.CONGRATS) {
                    AnkiApi.selectedDeckName(this)?.let { sel -> targets.firstOrNull { AnkiApi.find(all, it.first)?.name == sel }?.first }
                } else null
                // deck ที่เปิดหน้า Congratulations อยู่ ทำก่อน (ใช้ทางลัด ⚙)
                val ordered = if (shortcut != null) targets.sortedBy { if (it.first == shortcut) 0 else 1 } else targets
                a.apply(ordered, shortcut)
                var bad = verify(targets, a)
                if (bad.isNotEmpty() && a.speed < 1f) {
                    a.log("ลองใหม่ด้วยความเร็วปกติ")
                    a.speed = 1f
                    a.apply(targets)
                    bad = verify(targets, a)
                }
                if (bad.isEmpty()) ok = true else msg = "บันทึกไม่ติด: " + bad.joinToString(", ")
            } catch (e: Automator.StepFailed) {
                msg = "ทำไม่สำเร็จ: ${e.message}"
            } catch (e: Exception) {
                a.log("✗ ${e.javaClass.simpleName}: ${e.message}")
                msg = "ทำไม่สำเร็จ: ${e.message}"
            }
            prefs.lastLog = a.logText()
            main.post {
                if (ok) {
                    prefs.setRound(g, when (kind) {
                        Kind.BUMP -> prefs.round(g) + 1
                        Kind.UNDO -> prefs.round(g) - 1
                        Kind.RESET -> 1
                    })
                    prefs.lastGroup = g
                }
                busy = false
                lastCountAt = 0
                refreshBubble()
                requestCount(0)
                refreshTiles()
                if (ok) {
                    val name = if (prefs.visibleGroups().size > 1) prefs.groupName(g) + " " else ""
                    Toast.makeText(this, name + when (kind) {
                        Kind.BUMP -> "✓ เพิ่มแล้ว"; Kind.UNDO -> "✓ ย้อนแล้ว"; Kind.RESET -> "✓ เริ่มใหม่แล้ว"
                    }, Toast.LENGTH_SHORT).show()
                } else toast("$msg\nแตะปุ่มลอย → ⚙️ → ส่ง log ให้ผู้ช่วยดูได้")
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

    private fun shouldShow(): Boolean {
        if (!prefs.bubble) return false
        if (busy || menu != null) return true
        if (prefs.parked) return false
        if (!prefs.onlyInAnki) return true
        return ankiInFront && screenOk()
    }

    fun refreshBubble() {
        if (!::prefs.isInitialized) return
        if (shouldShow()) showBubble() else { hideMenu(); removeBubble() }
        updateBubbleFace()
        wake()
    }

    /** ปุ่มบอกแค่ 2 สถานะ: 📚 ยังท่องไม่ครบ · ➕ เขียว = ครบรอบแล้ว (ไม่มีตัวเลข) */
    private fun updateBubbleFace() {
        val v = bubble ?: return
        val done = remaining == 0
        v.text = when {
            busy -> "⏳"
            done -> "➕"
            else -> "📚"
        }
        v.textSize = 16f
        (v.background as? RingDrawable)?.let { r ->
            r.fillColor = Color.parseColor(if (done && !busy) "#E616A34A" else "#E62563EB")
            r.progress = if (prefs.featRing && !busy && !done && remaining > 0 && roundTotal > 0)
                ((roundTotal - remaining).toFloat() / roundTotal) else -1f
        }
        v.contentDescription = if (done) "ครบรอบแล้ว แตะเพื่อเพิ่มรอบ" else "AnkiRounds"
    }

    // จางลงเองเมื่อไม่ได้ใช้ 1.5 วินาที (แตะเมื่อไหร่ก็กลับมาชัด)
    private val fade = Runnable {
        if (menu == null) {
            bubble?.animate()?.alpha(0.25f)?.setDuration(300)?.start()
            tuck(true)
        }
    }

    private fun wake() {
        val v = bubble ?: return
        main.removeCallbacks(fade)
        v.animate().cancel()
        v.alpha = 0.9f
        if (prefs.bubbleFade && !busy) main.postDelayed(fade, if (remaining == 0) 3000 else 1500)
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
        val size = (BUBBLE_DP * resources.displayMetrics.density).toInt()
        val v = TextView(this).apply {
            text = "📚"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = RingDrawable(2.5f * resources.displayMetrics.density)
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
        val longPress = Runnable { longFired = true; tuck(false); showMenu() }
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
                        if (prefs.featTrash) showTrash()
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        highlightTrash(overTrash(lp))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    val drop = moved && prefs.featTrash && overTrash(lp)
                    hideTrash()
                    if (drop) {
                        park()
                    } else {
                        wake()
                        if (moved) {
                            if (prefs.featEdge) snapToEdge() else { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y }
                        } else if (!longFired && e.action == MotionEvent.ACTION_UP) {
                            if (menu != null) hideMenu() else { tuck(false); showMenu() }   // แตะ = เปิดเมนูให้เลือกก่อน
                        }
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

    // ------------------------------------------------------------------ park / trash / edge

    /** เก็บปุ่มลอยไว้ในแจ้งเตือน */
    fun park() {
        hideMenu()
        prefs.parked = true
        removeBubble()
        Parking.show(this)
        if (!Parking.canNotify(this)) {
            toast("เก็บปุ่มแล้ว แต่แจ้งเตือนถูกปิดอยู่ · เรียกปุ่มกลับได้ที่หน้าแอป AnkiRounds")
        } else {
            Toast.makeText(this, "เก็บไว้ในแจ้งเตือนแล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    private var trash: TextView? = null
    private var trashLp: WindowManager.LayoutParams? = null

    private fun showTrash() {
        if (trash != null) return
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val t = TextView(this).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC374151"))
                setStroke((2 * dp).toInt(), Color.parseColor("#80FFFFFF"))
            }
        }
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - size) / 2
            y = dm.heightPixels - size - (90 * dp).toInt()
        }
        try { wm.addView(t, lp); trash = t; trashLp = lp } catch (_: Exception) {}
    }

    private fun hideTrash() {
        val t = trash ?: return
        try { wm.removeView(t) } catch (_: Exception) {}
        trash = null; trashLp = null
    }

    private fun overTrash(blp: WindowManager.LayoutParams): Boolean {
        val tl = trashLp ?: return false
        val dp = resources.displayMetrics.density
        val b = (BUBBLE_DP * dp) / 2; val t = (64 * dp) / 2
        val dx = (blp.x + b) - (tl.x + t); val dy = (blp.y + b) - (tl.y + t)
        return dx * dx + dy * dy < (80 * dp) * (80 * dp)
    }

    private fun highlightTrash(on: Boolean) {
        val t = trash ?: return
        (t.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) "#E6DC2626" else "#CC374151"))
        t.scaleX = if (on) 1.2f else 1f; t.scaleY = t.scaleX
    }

    /** ปล่อยปุ่มแล้วไหลไปชิดขอบที่ใกล้กว่า (แล้วซ่อนครึ่งหนึ่งเมื่อไม่ได้ใช้) */
    private fun snapToEdge() {
        val v = bubble ?: return; val lp = bubbleLp ?: return
        val size = lp.width
        val w = resources.displayMetrics.widthPixels
        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
    }

    /** ซ่อนครึ่งหนึ่งที่ขอบจอ (true) / ดึงออกมาเต็ม (false) */
    private fun tuck(hide: Boolean) {
        if (!::prefs.isInitialized || !prefs.featEdge) return
        val v = bubble ?: return; val lp = bubbleLp ?: return
        val size = lp.width
        val w = resources.displayMetrics.widthPixels
        val left = lp.x + size / 2 < w / 2
        val x = when {
            hide && left -> -size / 2
            hide -> w - size / 2
            left -> 0
            else -> w - size
        }
        if (lp.x != x) {
            lp.x = x
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
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
        fun px(v: Int) = (v * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(10), px(10), px(8))
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(Color.parseColor("#F21E222A"))
            }
        }
        fun chip(text: String, color: String, onTap: (TextView) -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(12), px(9), px(12), px(9))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor(color)) }
            setOnClickListener { onTap(this) }
        }
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addChip(r: LinearLayout, c: TextView) {
            r.addView(c, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = px(6) })
        }

        val groups = prefs.visibleGroups().ifEmpty { listOf(0) }
        val multi = groups.size > 1
        for ((k, g) in groups.withIndex()) {
            // หัวข้อ: ชื่อชุด + การ์ดที่เหลือ (ไม่มีตัวเลขเพดาน)
            val left = remainingBy[g]
            val head = buildString {
                append(prefs.groupName(g))                    // ชื่อชุดแสดงเสมอ (แม้ปิดหลายชุด)
                val showLeft = prefs.featStatus && (!multi || prefs.featGroupRemaining)
                if (showLeft && left != null) {
                    if (isNotEmpty()) append("  ·  ")
                    append(if (left > 0) "เหลือ $left ใบ" else "ครบแล้ว ✓")
                }
            }
            if (head.isNotEmpty()) {
                box.addView(TextView(this).apply {
                    text = head
                    textSize = 13f
                    setTextColor(Color.parseColor(if (left == 0) "#86EFAC" else "#B0B6C0"))
                    setPadding(px(4), if (k == 0) 0 else px(10), px(4), px(6))
                })
            }
            val r = row()
            addChip(r, chip("➕ อีก 1 รอบ", "#2563EB") { bump(g) })
            if (prefs.featUndo && prefs.round(g) > 1) {
                addChip(r, chip("↶ ย้อน", "#374151") { undo(g) })
            }
            if (prefs.round(g) > 1) {
                var confirm = false
                addChip(r, chip("↺ เริ่มใหม่", "#374151") { tv ->
                    if (!confirm) {
                        confirm = true
                        tv.text = "↺ แตะอีกครั้ง"
                        tv.setTextColor(Color.parseColor("#FBBF24"))
                        main.removeCallbacks(autoClose); main.postDelayed(autoClose, 3000)
                    } else reset(g)
                })
            }
            box.addView(r)
        }

        val bottom = row().apply { setPadding(0, px(10), 0, 0) }
        addChip(bottom, chip("🏦 ธนาคาร", "#2A2F38") { hideMenu(); Bank.enterBankMode(this@RoundsService) })
        addChip(bottom, chip("⚙️", "#2A2F38") { hideMenu(); openApp() })
        addChip(bottom, chip("📥 เก็บ", "#2A2F38") { park() })
        addChip(bottom, chip("✕", "#2A2F38") { hideMenu() })
        box.addView(bottom)

        val screenW = resources.displayMetrics.widthPixels
        val bubbleSize = px(BUBBLE_DP.toInt())
        box.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(screenW, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.UNSPECIFIED)
        val w = box.measuredWidth
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val right = blp.x + bubbleSize + px(8)
            x = if (right + w <= screenW) right else (blp.x - w - px(8)).coerceAtLeast(0)
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
            main.removeCallbacks(autoClose); main.postDelayed(autoClose, 3000)
            requestCount(0)
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
        const val BUBBLE_DP = 42f
        @Volatile var instance: RoundsService? = null
    }
}
