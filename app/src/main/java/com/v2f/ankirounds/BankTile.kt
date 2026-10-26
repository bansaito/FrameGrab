package com.v2f.ankirounds

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** ปุ่ม "🏦 โหมดธนาคาร" ในแผงควบคุม: สว่าง = ผู้ช่วยปิดอยู่ เข้าแอปธนาคารได้ */
class BankTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val bank = !Bank.isOn(this)
        t.state = if (bank) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "โหมดธนาคาร"
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_bank)
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (bank) "เปิดอยู่ · แตะเพื่อปิด" else "ปิดอยู่"
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Bank.isOn(this)) {
            Bank.enterBankMode(this)
        } else if (!Bank.leaveBankMode(this)) {
            val i = Bank.accessibilitySettingsIntent(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ update() }, 800)
    }
}
