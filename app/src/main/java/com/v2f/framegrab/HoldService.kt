package com.v2f.framegrab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * "กดค้างหน้าจอ" ให้เองระหว่างจับภาพ/อัด → สตอรี่ซ่อนชื่อโปรไฟล์และปุ่มต่าง ๆ
 * ต้องเปิดใน การตั้งค่า → การช่วยเหลือพิเศษ → FrameGrab (ครั้งเดียว)
 */
class HoldService : AccessibilityService() {
    companion object {
        @Volatile var instance: HoldService? = null
            private set
        /** แอปที่อยู่หน้าจอล่าสุด (จากเหตุการณ์เปลี่ยนหน้าต่าง) */
        @Volatile var currentPkg: String? = null
            private set

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, HoldService::class.java).flattenToString()
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    // ท่ากดค้างยาวได้สูงสุด ~60 วิ (ของระบบ) ใช้ยาวไว้ ไม่ต้องต่อท่าบ่อย ๆ (ตอนต่อจะสะดุดแวบหนึ่ง)
    private val HOLD_MS = minOf(55_000L, GestureDescription.getMaxGestureDuration() - 1000)
    private var stroke: GestureDescription.StrokeDescription? = null
    @Volatile private var holding = false
    private var hx = 0f; private var hy = 0f

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        // ไม่นับหน้าต่างของระบบ/คีย์บอร์ด/ของเราเอง
        if (p == packageName || p == "com.android.systemui" || p.contains("inputmethod")) return
        currentPkg = p
    }
    override fun onInterrupt() {}

    private fun point() = Path().apply { moveTo(hx, hy) }

    /**
     * ชื่อบัญชีของคนโพสต์บนจอตอนนี้ (ใช้ตั้งชื่อไฟล์) หรือ null
     *  1) ข้อความ @ชื่อ (TikTok)  2) ข้อความแบบชื่อบัญชี (a-z0-9._) บนส่วนหัว 30% ของจอ (หัวสตอรี่ IG/TikTok)
     *  3) ชื่อที่แสดงบนส่วนหัว (เช่นชื่อเล่น) ถ้าไม่เจอแบบแรก ๆ
     */
    fun findUsername(): String? {
        if (android.os.Build.VERSION.SDK_INT >= 34) try { clearCache() } catch (e: Exception) { }
        val roots = try {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        } catch (e: Exception) { emptyList() }
        val list = if (roots.isEmpty()) listOfNotNull(rootInActiveWindow) else roots
        val h = resources.displayMetrics.heightPixels
        val at = ArrayList<String>(); val handle = ArrayList<String>(); val shown = ArrayList<String>()
        val r = android.graphics.Rect()
        val AT = Regex("""^@([A-Za-z0-9._]{2,30})$""")
        val HANDLE = Regex("""^[A-Za-z0-9._]{3,30}$""")
        val NOISE = Regex("""(ก่อน|นาที|ชม\.|ชั่วโมง|วัน|ติดตาม|ข้อความ|LIVE|ไลฟ์|\d+[:.]\d+|^\d+$)""", RegexOption.IGNORE_CASE)
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (n.packageName?.toString() == packageName) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length <= 40) {
                n.getBoundsInScreen(r)
                val top = r.top < h * 0.30f
                AT.matchEntire(t)?.let { at.add(it.groupValues[1]) }
                    ?: run {
                        if (top && HANDLE.matches(t) && t.any { it.isLetter() } && !NOISE.containsMatchIn(t)) handle.add(t)
                        else if (top && !NOISE.containsMatchIn(t) && t.any { it.isLetter() } && t.split(' ').size <= 4) shown.add(t)
                    }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        for (root in list) walk(root, 0)
        return (at.firstOrNull() ?: handle.firstOrNull() ?: shown.firstOrNull())?.trimEnd('.')
    }

    /**
     * ข้อความทั้งหมดบนจอของแอปที่เปิดอยู่ (รวมส่วนที่แอปไม่ให้กดค้างคัดลอก เช่น แคปชัน คอมเมนต์ ชื่อ)
     * ไม่รวมตัวหนังสือที่อยู่ "ในภาพ/วิดีโอ" (อันนั้นเป็นพิกเซล ไม่ใช่ข้อความ)
     */
    fun screenTexts(): ArrayList<String> {
        val out = LinkedHashSet<String>()
        // ล้างข้อมูลหน้าจอที่ระบบจำไว้ (บางเครื่อง เช่น Samsung คืนข้อความของหน้าก่อนหน้า)
        if (android.os.Build.VERSION.SDK_INT >= 34) try { clearCache() } catch (e: Exception) { }
        val roots = try {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
        } catch (e: Exception) { emptyList() }
        val list = if (roots.isEmpty()) listOfNotNull(rootInActiveWindow) else roots
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (depth == 0) try { n.refresh() } catch (e: Exception) { }
            if (n.packageName?.toString() == packageName) return
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t) else if (!d.isNullOrEmpty() && d.length > 1) out.add(d)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        for (r in list) walk(r, 0)
        return ArrayList(out)
    }

    /** เริ่มกดค้างที่ (x, y) — ค้างไว้จนกว่าจะเรียก release() */
    fun hold(x: Float, y: Float) {
        if (holding) return
        holding = true
        hx = x; hy = y
        val s = GestureDescription.StrokeDescription(point(), 0, HOLD_MS, true)
        stroke = s
        send(s)
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // ยังต้องค้างอยู่: ต่อเวลาไปอีก (ท่าเดียวยาวได้จำกัด)
                if (holding && stroke === s) {
                    val n = s.continueStroke(point(), 0, HOLD_MS, true)
                    stroke = n
                    send(n)
                }
            }
            override fun onCancelled(g: GestureDescription?) { if (stroke === s) { holding = false; stroke = null } }
        }, null)
    }

    /** ปล่อยนิ้ว */
    fun release() {
        if (!holding) return
        holding = false
        val s = stroke ?: return
        stroke = null
        try {
            val end = s.continueStroke(point(), 0, 30, false)
            dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, null)
        } catch (e: Exception) { }
    }
}
