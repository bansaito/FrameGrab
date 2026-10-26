package com.v2f.ankirounds

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** หน้าตั้งค่าของแอป */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var roundView: TextView
    private lateinit var bankView: TextView
    private val nameFields = ArrayList<EditText>()
    private val baseFields = ArrayList<EditText>()
    private val stepFields = ArrayList<EditText>()
    private lateinit var menuField: EditText
    private lateinit var fieldField: EditText
    private lateinit var saveField: EditText

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(20), px(18), px(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121418"))
            addView(root)
        })
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    // ------------------------------------------------------------------ UI

    private fun build() {
        title("📚 AnkiRounds", 24f)
        note("กด ➕ แล้วแอปจะเปิด AnkiDroid เข้าตั้งค่า deck แก้ช่อง Maximum reviews/day (Preset) และกด Save ให้เอง")

        section("1 · เปิดสิทธิ์")
        status = note("")
        button("เปิดสิทธิ์ช่วยเหลือพิเศษ") { openAccessibility() }
        note("ถ้าเปิดไม่ได้ (ขึ้นว่า \"การตั้งค่าที่จำกัด\"): ตั้งค่า → แอป → AnkiRounds → ⋮ มุมขวาบน → อนุญาตการตั้งค่าที่จำกัด แล้วลองใหม่")

        section("2 · deck")
        note("deck ที่ใช้ preset ร่วมกัน ใส่แค่ deck เดียว (เช่น deck 2 กับ 3 ใช้ preset เดียวกัน ใส่แค่ deck 2) · ชื่อต้องตรงกับที่เห็นในรายการ AnkiDroid")
        for (i in 0 until Prefs.MAX_DECKS) {
            val d = prefs.deck(i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = edit(d.name, "ชื่อ deck ${i + 1}", false)
            val base = edit(d.base.toString(), "เริ่ม", true)
            val step = edit(d.step.toString(), "+", true)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(small("เริ่ม"))
            row.addView(base, LinearLayout.LayoutParams(px(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(small("เพิ่ม"))
            row.addView(step, LinearLayout.LayoutParams(px(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(row)
            nameFields.add(name); baseFields.add(base); stepFields.add(step)
        }

        section("3 · รอบ")
        roundView = title("", 18f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("➕ อีก 1 รอบ", true) { withService { it.bump() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("↺ เริ่มชุดใหม่", false) { confirmReset() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        note("ปุ่มลอย ➕ : แตะ = อีก 1 รอบ · ลาก = ย้ายที่ · กดค้าง = เปิดหน้านี้\nยังเพิ่มปุ่ม \"➕ อีก 1 รอบ\" ในแถบตั้งค่าด่วน (ดึงแถบแจ้งเตือนลง → ✏️ แก้ไข) ได้ด้วย")
        val sw = Switch(this).apply {
            text = "แสดงปุ่มลอย ➕"
            setTextColor(Color.WHITE)
            isChecked = prefs.bubble
            setOnCheckedChangeListener { _, on ->
                prefs.bubble = on
                RoundsService.instance?.refreshBubble()
            }
        }
        root.addView(sw)
        button("ตั้งรอบเองโดยไม่แตะ AnkiDroid…") { pickRound() }

        section("🏦 โหมดธนาคาร")
        note("แอปธนาคารบางแอปไม่ยอมเปิดถ้าผู้ช่วยทำงานอยู่ กดปิดก่อนเข้าแอปธนาคาร แล้วกดเปิดกลับเมื่อเสร็จ (ปุ่มลอย ➕ จะหายไประหว่างนั้น)")
        bankView = note("")
        val bankRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bankRow.addView(btn("🏦 ปิดผู้ช่วย", false) { Bank.enterBankMode(this); root.postDelayed({ refresh() }, 600) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bankRow.addView(btn("✅ เปิดผู้ช่วยกลับ", false) {
            if (!Bank.leaveBankMode(this)) startActivity(Bank.accessibilitySettingsIntent(this))
            root.postDelayed({ refresh() }, 800)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bankRow)
        button("📋 คัดลอกคำสั่งให้เปิดกลับได้ในแตะเดียว (ทำครั้งเดียวผ่านคอม)") { showAdbHelp() }

        section("➕ ปุ่มในแผงควบคุม")
        note("ดึงแถบแจ้งเตือนลงแล้วกดได้เลย ไม่ต้องเปิดแอป")
        val tileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tileRow.addView(btn("เพิ่ม ➕ อีก 1 รอบ", false) {
            addTile(RoundsTile::class.java, "อีก 1 รอบ", R.drawable.ic_tile_plus)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tileRow.addView(btn("เพิ่ม 🏦 โหมดธนาคาร", false) {
            addTile(BankTile::class.java, "โหมดธนาคาร", R.drawable.ic_tile_bank)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tileRow)

        section("4 · ถ้ากดแล้วไม่สำเร็จ")
        note("แอปหาเมนูและปุ่มจากข้อความบนจอ ถ้า AnkiDroid ใช้คำอื่น ใส่คำที่เห็นบนจอเพิ่มได้ (คั่นด้วย | )")
        menuField = labeled("เมนูหลังกดค้างที่ deck", prefs.menuLabels)
        fieldField = labeled("ข้อความหัวช่องตัวเลข", prefs.fieldLabels)
        saveField = labeled("ปุ่มบันทึก", prefs.saveLabels)
        button("↺ คืนค่าข้อความเริ่มต้น") {
            menuField.setText(Prefs.DEFAULT_MENU); fieldField.setText(Prefs.DEFAULT_FIELD)
            saveField.setText(Prefs.DEFAULT_SAVE); saveFields()
        }
        button("🔍 บันทึกโครงหน้าจอ AnkiDroid (นับถอยหลัง 6 วิ)") {
            saveFields()
            withService { it.dumpAfter(6000) }
        }
        button("📤 ส่ง log / โครงหน้าจอล่าสุด") { shareLog() }
    }

    private fun refresh() {
        val on = RoundsService.instance != null
        status.text = if (on) "✅ เปิดสิทธิ์ช่วยเหลือพิเศษแล้ว" else "⚠️ ยังไม่ได้เปิดสิทธิ์ช่วยเหลือพิเศษ"
        status.setTextColor(if (on) Color.parseColor("#4ADE80") else Color.parseColor("#FBBF24"))
        roundView.text = "ตอนนี้รอบ ${prefs.round}  ·  ${prefs.summary()}"
        val bank = !Bank.isOn(this)
        bankView.text = (if (bank) "สถานะ: 🏦 โหมดธนาคาร (ผู้ช่วยปิดอยู่)" else "สถานะ: ผู้ช่วยทำงานอยู่") +
            "\nเปิดกลับในแตะเดียว: " + (if (Bank.canToggleDirectly(this)) "✅ ได้" else "— ต้องกดเปิดในหน้าตั้งค่า")
    }

    private fun addTile(cls: Class<*>, label: String, icon: Int) {
        if (!Bank.requestAddTile(this, cls, label, icon)) {
            AlertDialog.Builder(this)
                .setTitle("เพิ่มเองในแผงควบคุม")
                .setMessage("ดึงแถบแจ้งเตือนลง 2 ครั้ง → กด ✏️ แก้ไข → ลากปุ่ม \"$label\" ของ AnkiRounds ขึ้นไปวาง")
                .setPositiveButton("ตกลง", null).show()
        }
    }

    private fun showAdbHelp() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", Bank.ADB_CMD))
        AlertDialog.Builder(this)
            .setTitle("เปิดกลับได้ในแตะเดียว (ไม่บังคับ)")
            .setMessage("Android ไม่ให้แอปเปิดสิทธิ์ช่วยเหลือพิเศษของตัวเอง ถ้าอยากให้ปุ่ม 🏦 สลับเปิด/ปิดได้ในแตะเดียว ต้องอนุญาตครั้งเดียวจากคอม:\n\n" +
                "1. เปิด ตัวเลือกสำหรับนักพัฒนา → การแก้ไขข้อบกพร่อง USB\n" +
                "2. ต่อสายเข้าคอมที่มี adb แล้วพิมพ์คำสั่ง (คัดลอกให้แล้ว):\n\n${Bank.ADB_CMD}\n\n" +
                "ทำครั้งเดียว อยู่ถาวรจนกว่าจะลบแอป")
            .setPositiveButton("ตกลง", null).show()
    }

    private fun saveFields() {
        if (nameFields.isEmpty()) return
        for (i in 0 until Prefs.MAX_DECKS) {
            prefs.setDeck(i, Prefs.Deck(
                nameFields[i].text.toString(),
                baseFields[i].text.toString().toIntOrNull() ?: 0,
                stepFields[i].text.toString().toIntOrNull() ?: 0,
            ))
        }
        prefs.menuLabels = menuField.text.toString()
        prefs.fieldLabels = fieldField.text.toString()
        prefs.saveLabels = saveField.text.toString()
        if (::roundView.isInitialized) roundView.text = "ตอนนี้รอบ ${prefs.round}  ·  ${prefs.summary()}"
    }

    // ------------------------------------------------------------------ actions

    private fun withService(f: (RoundsService) -> Unit) {
        saveFields()
        val s = RoundsService.instance
        if (s == null) {
            Toast.makeText(this, "เปิดสิทธิ์ช่วยเหลือพิเศษก่อน", Toast.LENGTH_LONG).show()
            openAccessibility()
        } else f(s)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("เริ่มชุดใหม่?")
            .setMessage("ตั้ง deck กลับเป็นค่าเริ่มต้น (${prefs.summary(1)}) ใน AnkiDroid")
            .setPositiveButton("เริ่มชุดใหม่") { _, _ -> withService { it.reset() } }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun pickRound() {
        saveFields()
        val e = edit(prefs.round.toString(), "รอบ", true)
        AlertDialog.Builder(this)
            .setTitle("ตั้งรอบปัจจุบัน")
            .setMessage("ใช้เมื่อปรับบนคอมไปแล้ว ให้ตัวนับในโทรศัพท์ตรงกัน (ไม่แก้ AnkiDroid)")
            .setView(e)
            .setPositiveButton("ตกลง") { _, _ ->
                prefs.round = e.text.toString().toIntOrNull() ?: prefs.round
                refresh()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun openAccessibility() {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // บางรุ่นเปิดตรงไปที่บริการของเราได้
        i.putExtra(":settings:fragment_args_key",
            ComponentName(this, RoundsService::class.java).flattenToString())
        startActivity(i)
    }

    private fun shareLog() {
        val text = prefs.lastLog.ifBlank { "ยังไม่มี log" }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AnkiRounds log")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000))
        }
        startActivity(Intent.createChooser(i, "ส่ง log"))
    }

    // ------------------------------------------------------------------ view helpers

    private fun px(v: Int) = (v * dp).toInt()

    private fun title(s: String, size: Float): TextView = TextView(this).apply {
        text = s; textSize = size; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); setPadding(0, px(6), 0, px(6))
        root.addView(this)
    }

    private fun section(s: String) {
        TextView(this).apply {
            text = s; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD")); setPadding(0, px(22), 0, px(4))
            root.addView(this)
        }
    }

    private fun note(s: String): TextView = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(Color.parseColor("#B0B6C0"))
        setPadding(0, px(2), 0, px(6))
        root.addView(this)
    }

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(Color.parseColor("#B0B6C0"))
        setPadding(px(6), 0, px(2), 0)
    }

    private fun edit(value: String, hint: String, number: Boolean) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 15f
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        if (number) {
            inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
        } else inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
    }

    private fun labeled(label: String, value: String): EditText {
        small(label).also { it.setPadding(0, px(6), 0, 0); root.addView(it) }
        return edit(value, label, false).also { root.addView(it) }
    }

    private fun btn(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.parseColor(if (primary) "#2563EB" else "#2A2F38"))
        }
        setOnClickListener { onClick() }
    }

    private fun button(s: String, onClick: () -> Unit) {
        val b = btn(s, false, onClick)
        root.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(8) })
    }
}
