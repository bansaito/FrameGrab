package com.v2f.ankirounds

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** ปุ่ม "➕ อีก 1 รอบ" ในแถบตั้งค่าด่วน */
class RoundsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (RoundsService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "อีก 1 รอบ"
            it.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_plus)
            if (Build.VERSION.SDK_INT >= 29) {
                it.subtitle = if (RoundsService.instance != null) Prefs(this).let { pr -> "ตอนนี้ " + pr.summary(pr.lastGroup) }
                              else "🏦 โหมดธนาคาร"
            }
            it.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val svc = RoundsService.instance
        if (svc != null) {
            svc.bump()
            return
        }
        // ผู้ช่วยปิดอยู่ (เช่นอยู่ในโหมดธนาคาร) → เปิดกลับก่อน
        if (Bank.leaveBankMode(this)) {
            Toast.makeText(this, "เปิดผู้ช่วยกลับแล้ว แตะ ➕ อีกครั้งเพื่อเพิ่มรอบ", Toast.LENGTH_LONG).show()
            return
        }
        val i = Bank.accessibilitySettingsIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
