package com.v2f.ankirounds

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * โหมดธนาคาร: ปิดผู้ช่วย (Accessibility) ชั่วคราว เพราะแอปธนาคารหลายแอปไม่ยอมเปิดถ้ามีผู้ช่วยทำงานอยู่
 *  - ปิด: แอปปิดตัวเองได้เสมอ (disableSelf)
 *  - เปิดกลับ: Android ไม่ให้แอปเปิดตัวเอง → พาไปหน้าตั้งค่า
 *    ยกเว้นเคยอนุญาต WRITE_SECURE_SETTINGS ผ่าน adb ครั้งเดียว → เปิด/ปิดได้ในแตะเดียว
 */
object Bank {

    const val ADB_CMD = "adb shell pm grant com.v2f.ankirounds android.permission.WRITE_SECURE_SETTINGS"

    private fun component(ctx: Context) =
        ComponentName(ctx, RoundsService::class.java).flattenToString()

    fun canToggleDirectly(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** ผู้ช่วยเปิดอยู่ไหม (ดูจากการตั้งค่าของระบบ) */
    fun isOn(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = component(ctx)
        return RoundsService.instance != null ||
            list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** เปิดโหมดธนาคาร = ปิดผู้ช่วย */
    fun enterBankMode(ctx: Context) {
        if (canToggleDirectly(ctx)) {
            writeEnabled(ctx, false)
        } else {
            RoundsService.instance?.disableSelf()
        }
        Toast.makeText(ctx, "🏦 ปิดผู้ช่วยแล้ว เข้าแอปธนาคารได้เลย", Toast.LENGTH_LONG).show()
    }

    /**
     * ออกจากโหมดธนาคาร = เปิดผู้ช่วยกลับ
     * คืน true ถ้าเปิดได้ทันที, false ถ้าต้องให้ผู้ใช้เปิดเองในหน้าตั้งค่า
     */
    fun leaveBankMode(ctx: Context): Boolean {
        if (canToggleDirectly(ctx)) {
            writeEnabled(ctx, true)
            Toast.makeText(ctx, "✅ เปิดผู้ช่วยกลับแล้ว", Toast.LENGTH_SHORT).show()
            return true
        }
        Toast.makeText(ctx, "แตะ AnkiRounds แล้วเปิดสวิตช์ เพื่อใช้ปุ่ม ➕ อีกครั้ง", Toast.LENGTH_LONG).show()
        return false
    }

    fun accessibilitySettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component(ctx))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun writeEnabled(ctx: Context, on: Boolean) {
        val cr = ctx.contentResolver
        val me = component(ctx)
        val list = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
            .toMutableList()
        if (on) list.add(me)
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** ขอเพิ่มปุ่มลงแผงควบคุม (Android 13+)  คืน false ถ้าเครื่องไม่รองรับ */
    fun requestAddTile(ctx: Context, cls: Class<*>, label: String, iconRes: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val sbm = ctx.getSystemService(StatusBarManager::class.java) ?: return false
        sbm.requestAddTileService(
            ComponentName(ctx, cls), label, Icon.createWithResource(ctx, iconRes),
            ctx.mainExecutor
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "เพิ่ม \"$label\" ลงแผงควบคุมแล้ว"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "\"$label\" อยู่ในแผงควบคุมแล้ว"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "ยกเลิกการเพิ่ม"
                else -> "เพิ่มไม่ได้ ให้เพิ่มเองที่แผงควบคุม → ✏️ แก้ไข"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
        return true
    }
}
