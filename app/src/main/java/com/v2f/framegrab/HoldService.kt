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

    private var stroke: GestureDescription.StrokeDescription? = null
    @Volatile private var holding = false
    private var hx = 0f; private var hy = 0f

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = event?.packageName?.toString() ?: return
        // ไม่นับหน้าต่างของระบบ/คีย์บอร์ด/ของเราเอง
        if (p == packageName || p == "com.android.systemui" || p.contains("inputmethod")) return
        currentPkg = p
    }
    override fun onInterrupt() {}

    private fun point() = Path().apply { moveTo(hx, hy) }

    /** เริ่มกดค้างที่ (x, y) — ค้างไว้จนกว่าจะเรียก release() */
    fun hold(x: Float, y: Float) {
        if (holding) return
        holding = true
        hx = x; hy = y
        val s = GestureDescription.StrokeDescription(point(), 0, 8000, true)
        stroke = s
        send(s)
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // ยังต้องค้างอยู่: ต่อเวลาไปอีก (ท่าเดียวยาวได้จำกัด)
                if (holding && stroke === s) {
                    val n = s.continueStroke(point(), 0, 8000, true)
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
