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
import android.view.DragEvent
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
    private lateinit var permView: TextView
    private val nameFields = ArrayList<EditText>()
    private val baseFields = ArrayList<EditText>()
    private val stepFields = ArrayList<EditText>()
    private lateinit var menuField: EditText
    private lateinit var fieldField: EditText
    private lateinit var saveField: EditText
    private var group = 0                       // ชุดที่กำลังแก้
    private lateinit var groupBar: LinearLayout
    private lateinit var groupNameField: EditText
    private lateinit var speedRow: LinearLayout

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        group = prefs.lastGroup
        if (prefs.featTrash && android.os.Build.VERSION.SDK_INT >= 33 && !Parking.canNotify(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
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

        section("2 · ชุดและ deck")
        groupBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(groupBar)
        })
        groupNameField = labeled("ชื่อชุด (เช่น ไทย / Bio / Math)", prefs.groupName(group))
        note("กดค้างที่ชุดแล้วลาก = ย้ายตำแหน่ง · กด 🔎 เลือก deck จาก AnkiDroid · ช่อง เริ่ม = ค่าที่ ↺ เริ่มใหม่ กลับไป · ช่อง + คือเพิ่มครั้งละกี่ใบ\ndeck ที่ใช้ preset ร่วมกัน แก้ให้ครั้งเดียว")
        for (i in 0 until Prefs.MAX_DECKS) {
            val d = prefs.deck(group, i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = edit(d.name, "ชื่อ deck ${i + 1}", false)
            val base = edit(d.base.toString(), "เริ่ม", true)
            val step = edit(d.step.toString(), "+", true)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val idx = i
            row.addView(btn("🔎", false) { pickDeck(idx) },
                LinearLayout.LayoutParams(px(48), px(44)).apply { marginStart = px(4) })
            row.addView(small("เริ่ม"))
            row.addView(base, LinearLayout.LayoutParams(px(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(small("+"))
            row.addView(step, LinearLayout.LayoutParams(px(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(row)
            nameFields.add(name); baseFields.add(base); stepFields.add(step)
        }
        renderGroupBar()

        section("3 · รอบ")
        roundView = title("", 18f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("➕ อีก 1 รอบ", true) { withService { it.bump(group) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("↺ เริ่มชุดใหม่", false) { confirmReset() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        note("ปุ่มลอย: แตะ = เปิดเมนู · ลาก = ย้ายที่\n📚 = ยังท่องรอบนี้ไม่ครบ · ➕ สีเขียว (เด้งเรียก) = ครบแล้ว กดเพิ่มรอบได้")
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
        note("แสดงปุ่มลอยที่หน้าไหนของ AnkiDroid บ้าง")
        for ((key, label) in listOf(
            "HOME" to "   หน้าแรก (รายการ deck)",
            "CONGRATS" to "   หน้า Congratulations / หน้าเริ่มท่อง",
            "REVIEW" to "   ระหว่างท่องการ์ด",
            "ADD" to "   หน้าเพิ่ม / แก้ไขการ์ด",
            "BROWSER" to "   หน้า Browse (ค้นการ์ด)",
            "OTHER" to "   หน้าอื่น ๆ (ตั้งค่า สถิติ ฯลฯ)",
        )) featSwitch(label, prefs.showOn(key)) { prefs.setShowOn(key, it) }
        root.addView(Switch(this).apply {
            text = "แสดงปุ่มลอยเฉพาะตอนเปิด AnkiDroid"
            setTextColor(Color.WHITE)
            isChecked = prefs.onlyInAnki
            setOnCheckedChangeListener { _, on ->
                prefs.onlyInAnki = on
                RoundsService.instance?.refreshBubble()
            }
        })
        permView = note("")
        button("อนุญาตให้อ่านข้อมูล AnkiDroid (จำนวนการ์ด / ตรวจผล / เลือก deck)") {
            requestPermissions(arrayOf(AnkiApi.PERM), 8)
        }
        root.addView(Switch(this).apply {
            text = "ปุ่มลอยจางลงเองเมื่อไม่ได้ใช้"
            setTextColor(Color.WHITE)
            isChecked = prefs.bubbleFade
            setOnCheckedChangeListener { _, on ->
                prefs.bubbleFade = on
                RoundsService.instance?.refreshBubble()
            }
        })
        section("⚡ ความเร็ว")
        note("เร็วขึ้น = รอหน้าจอสั้นลง ถ้าเครื่องหรือ AnkiDroid ช้าอาจกดพลาด แอปจะลองใหม่ด้วยความเร็วปกติให้เอง")
        speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(speedRow)
        renderSpeed()

        section("✨ ฟีเจอร์เสริม (ปิดได้ถ้าไม่ชอบ)")
        featSwitch("ช่องค้นหาในหน้าเลือก deck", prefs.featSearch) { prefs.featSearch = it }
        featSwitch("หัวเมนูบอกการ์ดที่เหลือ (เมื่อมีชุดเดียว)", prefs.featStatus) { prefs.featStatus = it }
        featSwitch("หลายชุดในเมนู (ปิด = แสดงเฉพาะชุดแรก)", prefs.featGroups) { prefs.featGroups = it }
        featSwitch("      ↳ บอกการ์ดที่เหลือของแต่ละชุด", prefs.featGroupRemaining) { prefs.featGroupRemaining = it }
        featSwitch("ปุ่ม ↶ ย้อน 1 รอบ ในเมนู", prefs.featUndo) { prefs.featUndo = it }
        featSwitch("วงแหวนความคืบหน้ารอบปุ่มลอย", prefs.featRing) { prefs.featRing = it }
        featSwitch("ปุ่มลอยชัดเฉพาะตอนเพิ่งเปิด AnkiDroid (ท่องจบแล้วไม่เด้งขึ้นมา)", prefs.featCalm) { prefs.featCalm = it }
        featSwitch("ลากปุ่มลงขอบล่างเพื่อเก็บไว้ในแจ้งเตือน", prefs.featTrash) { on ->
            prefs.featTrash = on
            if (on && android.os.Build.VERSION.SDK_INT >= 33 && !Parking.canNotify(this)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            }
        }
        featSwitch("ปุ่มชิดขอบจอ ซ่อนครึ่งหนึ่งเมื่อไม่ได้ใช้", prefs.featEdge) { prefs.featEdge = it }
        featSwitch("ซ่อนปุ่มตอนเลื่อนรายการใน AnkiDroid", prefs.featScrollHide) { prefs.featScrollHide = it }
        button("📤 เรียกปุ่มลอยที่เก็บไว้กลับมา") {
            prefs.parked = false; Parking.hide(this); RoundsService.instance?.refreshBubble()
            Toast.makeText(this, "แสดงปุ่มลอยแล้ว (เห็นตอนเปิด AnkiDroid)", Toast.LENGTH_SHORT).show()
        }

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
        featSwitch("ปิดผู้ช่วยเองเมื่อเปิดแอปธนาคาร (ตั้งแยกแต่ละเครื่อง)", prefs.featAutoBank) { on ->
            prefs.featAutoBank = on
            if (on && prefs.bankApps.isEmpty()) pickBankApps()
            if (on && android.os.Build.VERSION.SDK_INT >= 33 && !Parking.canNotify(this)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            }
        }
        button("🏦 เลือกแอปธนาคาร") { pickBankApps() }

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
        roundView.text = "${prefs.groupName(group)}: เพิ่มไปแล้ว ${prefs.round(group) - 1} รอบ"
        val perm = AnkiApi.hasPermission(this)
        permView.text = if (perm) "✅ อ่านข้อมูล AnkiDroid ได้: ปุ่มบอกจำนวนการ์ดที่เหลือ และตรวจผลหลัง Save"
                        else "⚠️ ยังไม่ได้อนุญาตให้อ่าน AnkiDroid: ปุ่มจะไม่บอกจำนวนการ์ด และไม่ตรวจผลหลัง Save"
        permView.setTextColor(Color.parseColor(if (perm) "#4ADE80" else "#FBBF24"))
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
            val old = prefs.deck(group, i)
            val name = nameFields[i].text.toString()
            prefs.setDeck(group, i, Prefs.Deck(
                name,
                baseFields[i].text.toString().toIntOrNull() ?: old.base,   // ค่าที่ ↺ เริ่มใหม่ จะกลับไป
                stepFields[i].text.toString().toIntOrNull() ?: 0,
            ))
        }
        if (::groupNameField.isInitialized) prefs.setGroupName(group, groupNameField.text.toString())
        prefs.menuLabels = menuField.text.toString()
        prefs.fieldLabels = fieldField.text.toString()
        prefs.saveLabels = saveField.text.toString()
        if (::roundView.isInitialized) {
            roundView.text = "${prefs.groupName(group)}: เพิ่มไปแล้ว ${prefs.round(group) - 1} รอบ"
        }
    }

    /** แถบเลือกชุด: [ชุด 1] [ชุด 2] [＋] [ลบ] */
    private fun renderGroupBar() {
        groupBar.removeAllViews()
        for (g in 0 until prefs.groupCount) {
            val sel = g == group
            groupBar.addView(Button(this).apply {
                text = prefs.groupName(g); isAllCaps = false; textSize = 14f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 20 * dp
                    setColor(Color.parseColor(if (sel) "#2563EB" else "#2A2F38"))
                }
                setOnClickListener { switchGroup(g) }
                // กดค้างแล้วลากไปวางบนชุดอื่น = ย้ายตำแหน่ง
                tag = g
                setOnLongClickListener { v ->
                    saveFields()
                    v.startDragAndDrop(null, View.DragShadowBuilder(v), g, 0)
                    v.alpha = 0.4f
                    true
                }
                setOnDragListener { v, e ->
                    when (e.action) {
                        DragEvent.ACTION_DRAG_STARTED -> e.localState is Int
                        DragEvent.ACTION_DRAG_ENTERED -> { if (v.tag != e.localState) v.scaleX = 1.12f; v.scaleY = v.scaleX; true }
                        DragEvent.ACTION_DRAG_EXITED -> { v.scaleX = 1f; v.scaleY = 1f; true }
                        DragEvent.ACTION_DROP -> {
                            val from = e.localState as Int
                            val to = v.tag as Int
                            if (from != to) {
                                val cur = group
                                prefs.moveGroup(from, to)
                                // ชุดที่เปิดอยู่ตามไปตำแหน่งใหม่
                                group = when {
                                    cur == from -> to
                                    from < to && cur in (from + 1)..to -> cur - 1
                                    from > to && cur in to until from -> cur + 1
                                    else -> cur
                                }
                                v.post { switchGroup(group) }
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> { v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f; true }
                        else -> true
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)).apply { marginEnd = px(6) })
        }
        groupBar.addView(btn("＋ ชุด", false) {
            saveFields(); switchGroup(prefs.addGroup())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)).apply { marginEnd = px(6) })
        if (prefs.groupCount > 1) {
            groupBar.addView(btn("ลบชุดนี้", false) { confirmRemoveGroup() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)))
        }
    }

    private fun switchGroup(g: Int) {
        if (g != group) saveFields()
        group = g.coerceIn(0, prefs.groupCount - 1)
        prefs.lastGroup = group
        groupNameField.setText(prefs.groupName(group))
        for (i in 0 until Prefs.MAX_DECKS) {
            val d = prefs.deck(group, i)
            nameFields[i].setText(d.name)
            baseFields[i].setText(d.base.toString())
            stepFields[i].setText(d.step.toString())
        }
        renderGroupBar()
        refresh()
    }

    private fun confirmRemoveGroup() {
        AlertDialog.Builder(this)
            .setTitle("ลบ ${prefs.groupName(group)}?")
            .setMessage("ลบเฉพาะการตั้งค่าในแอปนี้ ไม่แตะ deck ใน AnkiDroid")
            .setPositiveButton("ลบ") { _, _ ->
                prefs.removeGroup(group)
                group = 0
                switchGroup(0)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun renderSpeed() {
        speedRow.removeAllViews()
        listOf("ปกติ", "เร็ว", "เร็วสุด").forEachIndexed { k, label ->
            speedRow.addView(btn(label, k == prefs.speed) { prefs.speed = k; renderSpeed() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(6) })
        }
    }

    /** เลือกแอปธนาคาร (แอปที่ชื่อเหมือนธนาคารขึ้นก่อน) */
    private fun pickBankApps() {
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val words = listOf("bank", "ธนาคาร", "k plus", "k+", "scb", "krungthai", "กรุงไทย", "เป๋าตัง",
            "paotang", "ttb", "mymo", "kma", "krungsri", "กรุงศรี", "bualuang", "บัวหลวง", "truemoney",
            "uob", "cimb", "ghb", "baac", "ธ.ก.ส", "lh bank", "kkp", "tisco", "wallet", "pay")
        val apps = pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName && it.first != Prefs.ANKI_PKG }
            .sortedWith(compareBy({ a -> if (words.any { a.second.lowercase().contains(it) }) 0 else 1 },
                { it.second.lowercase() }))
        val chosen = prefs.bankApps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle("แอปธนาคาร (เปิดแล้วจะปิดผู้ช่วยให้เอง)")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen.add(apps[i].first) else chosen.remove(apps[i].first)
            }
            .setPositiveButton("บันทึก") { _, _ ->
                prefs.bankApps = chosen
                Toast.makeText(this, "เลือกไว้ ${chosen.size} แอป", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun featSwitch(label: String, on: Boolean, set: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = on
            setPadding(0, px(4), 0, px(4))
            setOnCheckedChangeListener { _, v -> set(v); RoundsService.instance?.refreshBubble() }
        })
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

    private var pendingPick = -1

    private fun pickDeck(i: Int) {
        saveFields()
        if (!AnkiApi.hasPermission(this)) {
            pendingPick = i
            Toast.makeText(this, "อนุญาตให้อ่านรายชื่อ deck จาก AnkiDroid", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(AnkiApi.PERM), 7)
            return
        }
        val all = try {
            AnkiApi.decks(this).filter { !it.filtered }
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("อ่านรายชื่อ deck ไม่ได้").setMessage(e.message)
                .setPositiveButton("ตกลง", null).show()
            return
        }
        if (all.isEmpty()) {
            Toast.makeText(this, "ไม่พบ deck ใน AnkiDroid", Toast.LENGTH_LONG).show(); return
        }
        val chosen = (0 until Prefs.MAX_DECKS).filter { it != i }
            .mapNotNull { AnkiApi.find(all, nameFields[it].text.toString()) }
        showDeckTree(i, all, chosen)
    }

    /** รายชื่อ deck แบบพับได้: แตะ ▸ เพื่อกาง/พับ deck ย่อย · แตะชื่อเพื่อเลือก */
    private fun showDeckTree(i: Int, all: List<AnkiApi.DeckInfo>, chosen: List<AnkiApi.DeckInfo>) {
        val byName = all.associateBy { it.name }
        val children = HashMap<String, MutableList<AnkiApi.DeckInfo>>()
        val tops = ArrayList<AnkiApi.DeckInfo>()
        for (d in all) {
            val parent = if (d.name.contains("::")) d.name.substringBeforeLast("::") else null
            if (parent != null && byName.containsKey(parent)) children.getOrPut(parent) { ArrayList() }.add(d)
            else tops.add(d)
        }
        // กางให้เห็น deck ที่เลือกไว้ในช่องนี้อยู่แล้ว
        val expanded = HashSet<String>()
        val cur = nameFields[i].text.toString()
        var p = cur
        while (p.contains("::")) { p = p.substringBeforeLast("::"); expanded.add(p) }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(4), px(8), px(4))
        }
        lateinit var dialog: AlertDialog
        var query = ""

        fun render() {
            list.removeAllViews()
            if (query.isNotBlank()) {
                // โหมดค้นหา: แสดงผลลัพธ์แบบแบน พร้อมชื่อกลุ่มตัวเล็ก
                val q = query.trim().lowercase()
                val hits = all.filter { it.name.lowercase().contains(q) }
                if (hits.isEmpty()) list.addView(TextView(this).apply {
                    text = "ไม่พบ \"$query\""; setTextColor(Color.GRAY); setPadding(px(8), px(14), 0, px(14))
                })
                for (d in hits) {
                    list.addView(TextView(this).apply {
                        val parent = if (d.name.contains("::")) d.name.substringBeforeLast("::").replace("::", " › ") + " › " else ""
                        text = android.text.SpannableStringBuilder().apply {
                            append(parent)
                            setSpan(android.text.style.ForegroundColorSpan(Color.GRAY), 0, parent.length, 0)
                            append(d.shortName)
                        }
                        textSize = 16f
                        setTextColor(if (d.name == cur) Color.parseColor("#60A5FA") else Color.WHITE)
                        setPadding(px(8), px(12), px(8), px(12))
                        setOnClickListener { dialog.dismiss(); choose(i, d, chosen) }
                    })
                }
                return
            }
            fun add(d: AnkiApi.DeckInfo, depth: Int) {
                val kids = children[d.name].orEmpty()
                val open = expanded.contains(d.name)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(depth * 20), 0, 0, 0)
                }
                val arrow = TextView(this).apply {
                    text = if (kids.isEmpty()) "" else if (open) "▾" else "▸"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#93C5FD"))
                    if (kids.isNotEmpty()) setOnClickListener {
                        if (open) expanded.remove(d.name) else expanded.add(d.name)
                        render()
                    }
                }
                val label = TextView(this).apply {
                    text = d.shortName
                    textSize = 17f
                    setTextColor(if (d.name == cur) Color.parseColor("#60A5FA") else Color.WHITE)
                    setPadding(px(4), px(12), px(8), px(12))
                    setOnClickListener {
                        dialog.dismiss()
                        choose(i, d, chosen)
                    }
                }
                row.addView(arrow, LinearLayout.LayoutParams(px(40), px(44)))
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                list.addView(row)
                if (open) for (k in kids) add(k, depth + 1)
            }
            for (t in tops) add(t, 0)
        }
        render()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (prefs.featSearch) {
            content.addView(EditText(this).apply {
                hint = "🔍 ค้นหา deck"
                setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
                isSingleLine = true
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) {
                        query = e?.toString() ?: ""
                        render()
                    }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px(16), px(4), px(16), 0)
            })
        }
        content.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = AlertDialog.Builder(this)
            .setTitle("เลือก deck ${i + 1}")
            .setView(content)
            .setNegativeButton("ยกเลิก", null)
            .create()
        dialog.show()
    }

    private fun choose(i: Int, d: AnkiApi.DeckInfo, chosen: List<AnkiApi.DeckInfo>) {
        nameFields[i].setText(d.name)
        if ((stepFields[i].text.toString().toIntOrNull() ?: 0) <= 0) stepFields[i].setText("1")
        saveFields()
        val dup = chosen.firstOrNull { it.presetId == d.presetId && d.presetId >= 0 }
        if (dup != null) {
            Toast.makeText(this, "${d.shortName} ใช้ preset เดียวกับ ${dup.shortName} — แอปจะแก้ครั้งเดียวให้", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) refresh()
        if (requestCode == 7) {
            val i = pendingPick
            pendingPick = -1
            if (AnkiApi.hasPermission(this) && i >= 0) pickDeck(i)
            else Toast.makeText(this, "ยังไม่ได้รับอนุญาต พิมพ์ชื่อ deck เองแทนได้", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("เริ่มชุดใหม่?")
            .setMessage("ตั้ง ${prefs.groupName(group)} กลับเป็นค่าก่อนกด ➕ ครั้งแรก ใน AnkiDroid")
            .setPositiveButton("เริ่มชุดใหม่") { _, _ -> withService { it.reset(group) } }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun pickRound() {
        saveFields()
        val e = edit(prefs.round(group).toString(), "รอบ", true)
        AlertDialog.Builder(this)
            .setTitle("ตั้งรอบปัจจุบันของ ${prefs.groupName(group)}")
            .setMessage("ใช้เมื่อปรับบนคอมไปแล้ว ให้ตัวนับในโทรศัพท์ตรงกัน (ไม่แก้ AnkiDroid)")
            .setView(e)
            .setPositiveButton("ตกลง") { _, _ ->
                prefs.setRound(group, e.text.toString().toIntOrNull() ?: prefs.round(group))
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
