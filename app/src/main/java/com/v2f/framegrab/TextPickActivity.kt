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
    companion object {
        const val EXTRA_TEXTS = "texts"
        const val EXTRA_PKG = "pkg"

        private val IG = Regex("""(?i)(?:\bIG|\binstagram|\binsta|ไอจี)(?:\s*[:：\-–]\s*|\s+|\s*(?=@))@?([A-Za-z0-9._]{2,30})""")
        private val AT = Regex("""@([A-Za-z0-9._]{2,30})""")
        private val PLAIN = Regex("""^[a-z0-9._]{3,30}$""")

        /** ดึงชื่อบัญชีจากข้อความบนจอ: คืน (ป้าย, ชื่อที่จะคัดลอก) */
        fun handles(texts: List<String>, pkg: String?): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()   // ชื่อ → ป้าย
            val app = when {
                pkg?.contains("instagram") == true -> "IG"
                pkg?.contains("facebook") == true -> "FB"
                else -> "TikTok"
            }
            for (t in texts) {
                IG.findAll(t).forEach { m -> out.putIfAbsent(m.groupValues[1].trimEnd('.'), "IG") }
            }
            for (t in texts) {
                // @ชื่อ ที่ไม่ได้อยู่หลังคำว่า IG = ชื่อบัญชีในแอปนี้
                AT.findAll(t).forEach { m ->
                    val h = m.groupValues[1].trimEnd('.')
                    out.putIfAbsent(h, app)
                }
                // ในไอจี ชื่อบัญชีแสดงแบบไม่มี @
                if (app == "IG" && PLAIN.matches(t.trim()) && t.any { it.isLetter() }) out.putIfAbsent(t.trim(), "IG")
            }
            return out.map { (h, l) -> l to h }
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun copy(t: String, label: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FrameGrab", t))
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texts = intent.getStringArrayListExtra(EXTRA_TEXTS) ?: arrayListOf()
        val found = handles(texts, intent.getStringExtra(EXTRA_PKG))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply { setColor(0xF2181A22.toInt()); cornerRadius = dp(18f).toFloat() }
        }
        val header = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        col.addView(header)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val handleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((label, h) in found) {
            handleBox.addView(TextView(this).apply {
                text = "$label  ·  $h"
                setTextColor(Color.WHITE); textSize = 17f; paint.isFakeBoldText = true
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply { setColor(0xFF4F46E5.toInt()); cornerRadius = dp(12f).toFloat() }
                setOnClickListener { copy(h, "คัดลอก $h แล้ว") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
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
        // เริ่มที่ "ชื่อบัญชี" อย่างเดียว (ถ้าเจอ) แตะปุ่มด้านล่างเพื่อดูข้อความทั้งหมด
        var showAll = found.isEmpty()
        val scroll = ScrollView(this)
        lateinit var toggle: TextView
        fun render() {
            scroll.removeAllViews()
            scroll.addView(if (showAll) list else handleBox)
            header.text = if (showAll) "📋 ข้อความบนจอ (${texts.size})\nแตะ = คัดลอกทั้งบรรทัด · กดค้าง = เลือกบางส่วน"
                else "👤 ชื่อบัญชีบนจอ (${found.size})\nแตะเพื่อคัดลอกชื่อ (ไม่มี @)"
            toggle.text = if (showAll) "เฉพาะชื่อบัญชี" else "ข้อความทั้งหมด"
            toggle.visibility = if (found.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(t: String, act: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f)); setOnClickListener { act() }
        }
        toggle = btn("") { showAll = !showAll; render() }
        row.addView(toggle)
        if (texts.isNotEmpty()) row.addView(btn("คัดลอกทั้งหมด") { copy(texts.joinToString("\n"), "คัดลอกทั้งหมดแล้ว") })
        row.addView(btn("ปิด") { finish() })
        col.addView(row)
        render()
        setContentView(col)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.75f).toInt())
    }

    override fun onResume() { super.onResume(); CaptureService.setOwnUiHidden(this, true) }
    override fun onPause() { super.onPause(); CaptureService.setOwnUiHidden(this, false) }
}
