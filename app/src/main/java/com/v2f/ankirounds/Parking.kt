package com.v2f.ankirounds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * ที่จอดปุ่มลอย: ลากปุ่มไปทิ้งที่ขอบล่าง → ปุ่มหาย และมีแจ้งเตือนค้างไว้
 *   [แสดงปุ่มลอย]  [➕ อีก 1 รอบ]  [ปิด]
 */
object Parking {
    private const val CHANNEL = "parked"
    private const val ID = 42
    const val ACT_SHOW = "com.v2f.ankirounds.SHOW"
    const val ACT_BUMP = "com.v2f.ankirounds.BUMP"
    const val ACT_CLOSE = "com.v2f.ankirounds.CLOSE"

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ปุ่มลอยที่เก็บไว้", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ParkReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_plus)
            .setContentTitle("AnkiRounds")
            .setContentText("เก็บปุ่มลอยไว้ตรงนี้ · แตะเพื่อแสดงปุ่มกลับ")
            .setContentIntent(pi(ACT_SHOW, 1))
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "แสดงปุ่มลอย", pi(ACT_SHOW, 1)).build())
            .addAction(Notification.Action.Builder(null, "➕ อีก 1 รอบ", pi(ACT_BUMP, 2)).build())
            .addAction(Notification.Action.Builder(null, "ปิด", pi(ACT_CLOSE, 3)).build())
            .build()
        try { nm.notify(ID, n) } catch (_: SecurityException) {}
    }

    fun hide(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    fun canNotify(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
}

class ParkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val svc = RoundsService.instance
        when (intent.action) {
            Parking.ACT_SHOW -> {
                prefs.parked = false
                Parking.hide(ctx)
                if (svc != null) svc.refreshBubble()
                else ctx.startActivity(Bank.accessibilitySettingsIntent(ctx))
            }
            Parking.ACT_BUMP -> {
                if (svc != null) svc.bump()
                else Toast.makeText(ctx, "ผู้ช่วยปิดอยู่ กด \"แสดงปุ่มลอย\" เพื่อเปิดก่อน", Toast.LENGTH_LONG).show()
            }
            Parking.ACT_CLOSE -> {
                prefs.parked = false          // เปิดผู้ช่วยครั้งหน้า ปุ่มลอยกลับมาตามปกติ
                Parking.hide(ctx)
                svc?.disableSelf()
                Toast.makeText(ctx, "ปิด AnkiRounds แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
