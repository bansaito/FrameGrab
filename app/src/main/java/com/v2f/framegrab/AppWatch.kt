package com.v2f.framegrab

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/** รู้ว่าแอปไหนเปิดอยู่ (ต้องอนุญาต "การเข้าถึงข้อมูลการใช้งาน") เพื่อแสดงปุ่มลอยเฉพาะในแอปวิดีโอ */
object AppWatch {

    /** TikTok, Instagram, Facebook (รวมรุ่น Lite / รุ่นภูมิภาค) */
    val VIDEO_APPS = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme",
        "com.zhiliaoapp.musically.go", "com.ss.android.ugc.tiktok.lite",
        "com.instagram.android", "com.instagram.lite",
        "com.facebook.katana", "com.facebook.lite",
    )

    fun hasPermission(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun permissionIntent(c: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}"))

    private var lastPkg: String? = null

    /** แอปที่อยู่หน้าจอตอนนี้ (null = ยังไม่รู้) */
    fun foreground(c: Context): String? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return lastPkg
        val now = System.currentTimeMillis()
        val events = try { usm.queryEvents(now - 20_000, now) } catch (e: Exception) { return lastPkg }
        val ev = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = ev.packageName
        }
        if (pkg != null) lastPkg = pkg
        return lastPkg
    }
}
