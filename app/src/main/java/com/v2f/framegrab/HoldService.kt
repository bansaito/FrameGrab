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
     * ชื่อเจ้าของโพสต์ที่แสดงบนจอ (ใช้ตั้งชื่อไฟล์) หรือ null
     * ไม่เอา @ชื่อในแคปชัน (มักเป็นการแท็กคนอื่น) — ดูจาก "ตำแหน่ง" ของชื่อบนจอแทน:
     *  1) หน้าสตอรี่: ชื่อบนแถบหัว (ส่วนบน 30% ของจอ ฝั่งซ้าย ข้างรูปโปรไฟล์) อันบนสุด-ซ้ายสุด
     *  2) หน้าคลิป/Reels: ชื่อเหนือแคปชันมุมซ้ายล่าง (ช่วง 55–93% ของจอ ฝั่งซ้าย) อันบนสุด
     */
    fun findUsername(): String? {
        if (!Prefs.nameFromScreen(this)) return null   // ปิดไว้ = ใช้ FrameGrab-วันที่-เลขรัน
        if (android.os.Build.VERSION.SDK_INT >= 34) try { clearCache() } catch (e: Exception) { }
        val roots = try {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        } catch (e: Exception) { emptyList() }
        val list = if (roots.isEmpty()) listOfNotNull(rootInActiveWindow) else roots
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        data class C(val t: String, val x: Int, val y: Int)
        val cands = ArrayList<C>()
        val r = android.graphics.Rect()
        // คำที่ไม่ใช่ชื่อ: เวลา, ปุ่ม, ตัวเลข, แท็บ
        val NOISE = Regex("""(ก่อน|นาที|ชม\.|ชั่วโมง|วัน|สัปดาห์|ติดตาม|ข้อความ|ส่งข้อความ|แชร์|ถูกใจ|ความคิดเห็น|เพิ่มเติม|แปล|ไฮไลท์|สตอรี่|Stories|ค้นหา|สำหรับคุณ|เพื่อน|LIVE|ไลฟ์|Follow|Reply|Send|\d+[:.,]\d+|^\d+[KMk]?$)""", RegexOption.IGNORE_CASE)
        fun nameLike(t: String) = t.length in 2..30 && !t.startsWith("#") && !t.contains('\n') && !t.contains('…') &&
            t.split(' ').size <= 4 && t.any { it.isLetter() } && !NOISE.containsMatchIn(t)
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (n.packageName?.toString() == packageName) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0 && nameLike(t)) cands.add(C(t, r.left, r.top))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        for (root in list) walk(root, 0)
        // 1) แถบหัวสตอรี่: บน 30%, ฝั่งซ้าย 75% ของจอ (ไม่เอาแถวบนสุดที่เป็นแถบสถานะ)
        val head = cands.filter { it.y in (h * 0.03f).toInt()..(h * 0.30f).toInt() && it.x < w * 0.75f }
            .minWithOrNull(compareBy<C>({ it.y / (h / 40) }, { it.x }))
        // 2) ชื่อเหนือแคปชันมุมซ้ายล่าง
        val foot = cands.filter { it.y in (h * 0.55f).toInt()..(h * 0.93f).toInt() && it.x < w * 0.45f }
            .minByOrNull { it.y }
        val pick = head ?: foot ?: return null
        return pick.t.removePrefix("@").trimEnd('.')
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
