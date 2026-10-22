package com.v2f.framegrab

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * ปุ่มในแผงควบคุม (ลากแถบด้านบนลงมา) — แตะเพื่อเปิด/ซ่อนปุ่มลอย
 *  - ยังไม่ทำงาน / ซ่อนอยู่ → เปิดหน้าเลือกแอปที่จะแชร์ แล้วเปิดปุ่มลอย
 *  - ปุ่มลอยแสดงอยู่ → ซ่อนปุ่มลอย
 */
class QsTile : TileService() {
    companion object {
        /** ให้ระบบอัปเดตหน้าตาปุ่ม (เรียกเมื่อสถานะปุ่มลอยเปลี่ยน) */
        fun refresh(c: Context) {
            try { requestListeningState(c, ComponentName(c, QsTile::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onStartListening() { update() }

    private fun update() {
        val t = qsTile ?: return
        val on = CaptureService.running && !CaptureService.parked
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "FrameGrab"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "ปุ่มลอยเปิดอยู่" else "แตะเพื่อเปิด"
        t.icon = Icon.createWithResource(this, android.R.drawable.ic_menu_camera)
        t.updateTile()
    }

    override fun onClick() {
        if (CaptureService.running && !CaptureService.parked) {
            // ซ่อนปุ่มลอย (การจับหน้าจอค้างไว้)
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PARK)
                .putExtra(CaptureService.EXTRA_EXIT_APP, false))
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        // เปิด: ต้องผ่านหน้าแอปเพื่อขอสิทธิ์จับหน้าจอ/เลือกแอป
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_RESHARE, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
