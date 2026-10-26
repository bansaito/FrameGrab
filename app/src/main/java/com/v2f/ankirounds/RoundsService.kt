package com.v2f.ankirounds

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * บริการช่วยเหลือพิเศษ: แสดงปุ่มลอย ➕ และกดแทนใน AnkiDroid เมื่อผู้ใช้สั่งเท่านั้น
 */
class RoundsService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var bubble: TextView? = null
    @Volatile private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        refreshBubble()
        refreshTiles()
    }

    private fun refreshTiles() {
        for (c in listOf(RoundsTile::class.java, BankTile::class.java)) {
            try {
                android.service.quicksettings.TileService.requestListeningState(
                    this, android.content.ComponentName(this, c))
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        removeBubble()
        if (instance === this) instance = null
        refreshTiles()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ jobs

    /** ➕ อีก 1 รอบ */
    fun bump() = run(prefs.round + 1, "รอบ ${prefs.round + 1}")

    /** ↺ เริ่มชุดใหม่ */
    fun reset() = run(1, "เริ่มชุดใหม่")

    /** ตั้งค่าตามรอบปัจจุบันซ้ำ (ใช้ทดสอบ) */
    fun applyCurrent() = run(prefs.round, "ตั้งค่ารอบ ${prefs.round} ซ้ำ")

    private fun run(newRound: Int, title: String) {
        if (busy) { toast("กำลังทำงานอยู่ รอสักครู่"); return }
        val decks = prefs.decks()
        if (decks.isEmpty()) { toast("ยังไม่ได้ตั้งชื่อ deck ในแอป AnkiRounds"); return }
        busy = true
        setBubbleText("⏳")
        val targets = decks.map { it.name to prefs.valueFor(it, newRound) }
        Thread {
            val a = Automator(this, prefs)
            var ok = false
            var msg: String
            try {
                a.apply(targets)
                ok = true
                msg = "$title ✓  ${targets.joinToString(" / ") { it.second.toString() }}"
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
                refreshBubble()
                toast(msg + if (ok) "" else "\nเปิดแอป AnkiRounds → ส่ง log ให้ผู้ช่วยดูได้")
            }
        }.start()
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

    fun refreshBubble() {
        if (!::prefs.isInitialized) return
        if (prefs.bubble) showBubble() else removeBubble()
        setBubbleText(if (busy) "⏳" else "➕")
    }

    private fun setBubbleText(t: String) {
        bubble?.text = t
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
            alpha = 0.85f
            contentDescription = "อีก 1 รอบ"
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
        var moved = false; var downAt = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
                    } else if (System.currentTimeMillis() - downAt > 600) {
                        openApp()            // กดค้าง = เปิดหน้าแอป
                    } else {
                        bump()               // แตะ = อีก 1 รอบ
                    }
                }
            }
            true
        }
        try {
            wm.addView(v, lp)
            bubble = v
        } catch (e: Exception) {
            toast("แสดงปุ่มลอยไม่ได้: ${e.message}")
        }
    }

    private fun removeBubble() {
        val v = bubble ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        bubble = null
    }

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
