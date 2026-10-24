package com.v2f.framegrab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** แสดงข้อความบนจอที่อ่านได้ แตะ = คัดลอกบรรทัดนั้น, กดค้าง = เลือกบางส่วนเอง */
class TextPickActivity : Activity() {
    companion object { const val EXTRA_TEXTS = "texts" }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun copy(t: String, label: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FrameGrab", t))
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texts = intent.getStringArrayListExtra(EXTRA_TEXTS) ?: arrayListOf()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply { setColor(0xF2181A22.toInt()); cornerRadius = dp(18f).toFloat() }
        }
        col.addView(TextView(this).apply {
            text = "📋 ข้อความบนจอ (${texts.size})\nแตะ = คัดลอกทั้งบรรทัด · กดค้าง = เลือกบางส่วน"
            setTextColor(Color.WHITE); textSize = 15f
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (texts.isEmpty()) list.addView(TextView(this).apply {
            text = "ไม่พบข้อความ — ตัวหนังสือที่อยู่ในภาพ/วิดีโอ อ่านด้วยวิธีนี้ไม่ได้\n(ลองจับภาพแล้วเปิดด้วย Google Lens ในหน้าดูภาพ ↗)"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 14f; setPadding(0, dp(12f), 0, 0)
        })
        for (t in texts) {
            list.addView(TextView(this).apply {
                text = t
                setTextColor(Color.WHITE); textSize = 15f
                setTextIsSelectable(true)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply { setColor(0xFF2A2D3A.toInt()); cornerRadius = dp(10f).toFloat() }
                setOnClickListener { copy(t, "คัดลอกแล้ว") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(t: String, act: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f)); setOnClickListener { act() }
        }
        if (texts.isNotEmpty()) row.addView(btn("คัดลอกทั้งหมด") { copy(texts.joinToString("\n"), "คัดลอกทั้งหมดแล้ว") })
        row.addView(btn("ปิด") { finish() })
        col.addView(row)
        setContentView(col)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.75f).toInt())
    }

    override fun onResume() { super.onResume(); CaptureService.setOwnUiHidden(this, true) }
    override fun onPause() { super.onPause(); CaptureService.setOwnUiHidden(this, false) }
}
