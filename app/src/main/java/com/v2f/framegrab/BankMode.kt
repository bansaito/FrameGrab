package com.v2f.framegrab

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.graphics.drawable.Icon
import android.os.Build

/**
 * 🏦 โหมดธนาคาร: ปิดสิ่งที่แอปธนาคารมองว่าเสี่ยงในครั้งเดียว
 *  - ปิด FrameGrab ใน "การช่วยเหลือพิเศษ" (แอปปิดเองได้ แต่เปิดเองไม่ได้)
 *  - หยุดจับหน้าจอ + ปิดปุ่มลอย
 * "แสดงทับแอปอื่น" แอปถอนเองไม่ได้ → บอกให้ไปปิดเองถ้าธนาคารยังไม่ยอม
 */
object BankMode {
    /** คืนรายการสถานะไว้แสดงผู้ใช้ */
    fun run(c: Context): List<String> {
        val out = ArrayList<String>()
        val hs = HoldService.instance
        if (hs != null) {
            try { hs.disableSelf(); out.add("✅ ปิดการช่วยเหลือพิเศษของ FrameGrab แล้ว") }
            catch (e: Exception) { out.add("⚠️ ปิดการช่วยเหลือพิเศษไม่ได้ — ไปปิดเองในการตั้งค่า") }
        } else if (HoldService.isEnabled(c)) {
            out.add("⚠️ การช่วยเหลือพิเศษยังเปิดอยู่ — ไปปิดเองในการตั้งค่า")
        } else out.add("✅ การช่วยเหลือพิเศษปิดอยู่แล้ว")
        if (CaptureService.running) {
            c.startService(Intent(c, CaptureService::class.java).setAction(CaptureService.ACTION_QUIT)
                .putExtra(CaptureService.EXTRA_EXIT_APP, false))
            out.add("✅ หยุดจับหน้าจอและปิดปุ่มลอยแล้ว")
        } else out.add("✅ ไม่ได้จับหน้าจออยู่")
        out.add(if (Settings.canDrawOverlays(c)) "ℹ️ \"แสดงทับแอปอื่น\" ยังเปิด — ถ้าธนาคารยังไม่ยอม ให้ปิดตัวนี้ด้วย"
            else "✅ \"แสดงทับแอปอื่น\" ปิดอยู่")
        return out
    }
}

/** ปุ่ม 🏦 ในแผงควบคุม */
class BankTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.label = "โหมดธนาคาร"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = "FrameGrab"
        t.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_lock)
        t.updateTile()
    }

    override fun onClick() {
        val r = BankMode.run(this)
        android.widget.Toast.makeText(this, "🏦 " + r.joinToString("\n"), android.widget.Toast.LENGTH_LONG).show()
        qsTile?.let { if (Build.VERSION.SDK_INT >= 29) it.subtitle = "ปิดแล้ว"; it.updateTile() }
    }
}
