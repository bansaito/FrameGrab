package com.v2f.framegrab

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * หน้าจอของแอปที่เปิดอยู่ ไว้ปิดตอนกด ✕ ที่ปุ่มลอย
 * ใช้ finish() แทนการลบออกจากรายการแอปล่าสุด เพราะมือถือบางยี่ห้อ (เช่น OPPO/ColorOS)
 * จะฆ่าทั้งแอปเมื่อถูกลบจากรายการ → การจับหน้าจอที่ค้างไว้หายไปด้วย ต้องกดยืนยันใหม่
 */
object OwnActivities {
    private val list = ArrayList<WeakReference<Activity>>()

    @Synchronized fun add(a: Activity) { list.removeAll { it.get() == null || it.get() === a }; list.add(WeakReference(a)) }
    @Synchronized fun remove(a: Activity) { list.removeAll { it.get() == null || it.get() === a } }
    @Synchronized fun finishAll() {
        list.mapNotNull { it.get() }.forEach { try { if (!it.isFinishing) it.finish() } catch (e: Exception) { } }
        list.clear()
    }
}
