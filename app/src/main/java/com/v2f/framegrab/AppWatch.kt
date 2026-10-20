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
        // บางยี่ห้อคืน MODE_DEFAULT แม้เปิดสิทธิ์แล้ว → ดูจากสิทธิ์แทน
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                c.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    fun permissionIntent(c: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}"))

    private var lastPkg: String? = null
    private var lastQuery = 0L

    /** แอปที่อยู่หน้าจอตอนนี้ (null = ยังไม่รู้) */
    fun foreground(c: Context): String? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return lastPkg
        val now = System.currentTimeMillis()
        // ครั้งแรกดูย้อนหลัง 12 ชม. (เผื่อเปิดแอปนั้นค้างไว้นาน) ครั้งต่อไปดูเฉพาะช่วงที่ยังไม่ได้ดู
        val from = if (lastQuery == 0L) now - 12 * 3600_000L else lastQuery - 2_000
        val events = try { usm.queryEvents(from, now) } catch (e: Exception) { return lastPkg }
        lastQuery = now
        val ev = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = ev.packageName
        }
        if (pkg != null) lastPkg = pkg
        if (lastPkg == null) {
            // สำรอง: แอปที่ถูกใช้ล่าสุดจากสถิติการใช้งาน
            try {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3600_000L, now)
                    ?.filter { it.lastTimeUsed > 0 }
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.let { lastPkg = it.packageName }
            } catch (e: Exception) { }
        }
        return lastPkg
    }

    /** ชื่อแอปสำหรับแสดง */
    fun label(c: Context, pkg: String?): String {
        if (pkg == null) return "?"
        return try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    fun reset() { lastPkg = null; lastQuery = 0L }
}
