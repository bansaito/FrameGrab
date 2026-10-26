package com.v2f.ankirounds

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ขั้นตอนกดแทนใน AnkiDroid (ทำงานบนเธรดแยก ห้ามเรียกจาก main thread)
 *   1. เปิดหน้ารายการ deck
 *   2. กดค้างที่ชื่อ deck → เลือกเมนู Deck options
 *   3. หาช่องถัดจากข้อความ "Maximum reviews/day" → ใส่ตัวเลข
 *   4. กด Save
 * หาทุกอย่างจากข้อความบนจอ (ไม่ใช้ตำแหน่งตายตัว) และบันทึก log ทุกขั้น
 */
class Automator(private val svc: AccessibilityService, private val prefs: Prefs) {

    class StepFailed(msg: String) : Exception(msg)

    private val log = StringBuilder()

    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        log.append(t).append("  ").append(s).append('\n')
    }

    fun logText(): String = log.toString()

    /** ตั้งค่า Maximum reviews/day ของแต่ละ deck  คืนข้อความผลลัพธ์ */
    /**
     * @param shortcutDeck deck ที่กำลังเปิดหน้า Congratulations อยู่ (ถ้ามี) — ใช้ไอคอน ⚙ มุมขวาบนเป็นทางลัด
     */
    fun apply(targets: List<Pair<String, Int>>, shortcutDeck: String? = null) {
        log("เริ่ม: " + targets.joinToString { "${it.first}=${it.second}" })
        for ((name, value) in targets) {
            if (shortcutDeck != null && name == shortcutDeck && tryShortcut(name, value)) continue
            setDeckLimit(name, value)
        }
        openDeckList()
        log("เสร็จ ✓")
    }

    private val congratsWords = listOf("congratulations", "ยินดีด้วย", "custom study", "เรียนแบบกำหนดเอง")

    /** อยู่หน้า Congratulations ของ deck นี้ → แตะ ⚙ (Deck options) แล้วแก้ค่าเลย ไม่ต้องหาในรายการ */
    private fun tryShortcut(deckName: String, value: Int): Boolean {
        if (findNode { containsAny(it, congratsWords) } == null) return false
        val menuLabels = split(prefs.menuLabels)
        val gear = findNode { n ->
            n.packageName?.toString() == Prefs.ANKI_PKG && (exactAny(n, menuLabels) ||
                (n.viewIdResourceName ?: "").contains("deck_options", ignoreCase = true))
        }
        if (gear == null) {
            log("หน้า Congratulations: ไม่เจอไอคอน ⚙ → ใช้ทางปกติ")
            return false
        }
        log("— deck \"$deckName\" → $value (ทางลัด ⚙ จากหน้า Congratulations)")
        click(gear)
        return try {
            editOpenOptions(value)
            true
        } catch (e: StepFailed) {
            log("ทางลัดไม่สำเร็จ → ใช้ทางปกติ")
            false
        }
    }

    // ------------------------------------------------------------------ steps

    private fun setDeckLimit(deckName: String, value: Int) {
        log("— deck \"$deckName\" → $value")
        openDeckList()

        val deck = findDeckInList(deckName)
            ?: fail("ไม่เจอ deck \"$deckName\" ในรายการ (ถ้าเป็น deck ย่อย ให้กางกลุ่มใน AnkiDroid ก่อน)")
        longPress(deck)
        log("กดค้างที่ deck แล้ว")

        val menuLabels = split(prefs.menuLabels)
        val menu = waitFor(4000) { n -> exactAny(n, menuLabels) }
            ?: fail("ไม่เจอเมนู ${menuLabels.joinToString(" / ")} หลังกดค้าง")
        log("เจอเมนู \"${label(menu)}\"")
        click(menu)
        editOpenOptions(value)
    }

    /** อยู่ในหน้าตั้งค่า deck แล้ว: แก้ Maximum reviews/day แล้วกด Save */
    private fun editOpenOptions(value: Int) {
        val fieldLabels = split(prefs.fieldLabels)
        val fieldLabel = waitFor(15000) { n -> containsAny(n, fieldLabels) }
            ?: fail("ไม่เจอข้อความ ${fieldLabels.joinToString(" / ")} ในหน้าตั้งค่า")
        log("เจอ \"${label(fieldLabel)}\"")
        sleep(600)

        val input = findInputAfter(fieldLabels)
            ?: fail("ไม่เจอช่องตัวเลขถัดจาก \"${label(fieldLabel)}\"")
        val before = label(input)
        setText(input, value.toString())
        sleep(500)
        val after = findInputAfter(fieldLabels)?.let { label(it) } ?: "?"
        log("ช่องตัวเลข: \"$before\" → \"$after\"")
        if (after.trim() != value.toString()) {
            log("⚠ ค่าในช่องยังไม่ตรง ลองพิมพ์ทับอีกครั้ง")
            findInputAfter(fieldLabels)?.let { setText(it, value.toString()) }
            sleep(500)
        }

        val saveLabels = split(prefs.saveLabels)
        val save = findNode { n -> exactAny(n, saveLabels) }
            ?: fail("ไม่เจอปุ่ม ${saveLabels.joinToString(" / ")}")
        click(save)
        log("กด \"${label(save)}\" แล้ว")

        // รอให้กลับออกจากหน้าตั้งค่า
        val left = waitGone(6000) { n -> containsAny(n, fieldLabels) }
        if (!left) fail("กด Save แล้วยังอยู่หน้าตั้งค่า (อาจบันทึกไม่สำเร็จ)")
    }

    private fun openDeckList() {
        val i = Intent().apply {
            component = ComponentName(Prefs.ANKI_PKG, "com.ichi2.anki.DeckPicker")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            svc.startActivity(i)
        } catch (e: Exception) {
            val launch = svc.packageManager.getLaunchIntentForPackage(Prefs.ANKI_PKG)
                ?: fail("ไม่พบแอป AnkiDroid ในเครื่อง")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(launch)
        }
        waitFor(6000) { n -> n.packageName?.toString() == Prefs.ANKI_PKG }
            ?: fail("เปิด AnkiDroid ไม่ขึ้น")
        sleep(900)
    }

    private fun findDeckInList(name: String): AccessibilityNodeInfo? {
        val want = norm(name.substringAfterLast("::"))   // ในรายการ AnkiDroid แสดงแค่ชื่อสั้น
        val match: (AccessibilityNodeInfo) -> Boolean = { n ->
            n.packageName?.toString() == Prefs.ANKI_PKG && norm(label(n)) == want
        }
        findNode(match)?.let { return it }
        // เลื่อนขึ้นบนสุดก่อน แล้วค่อยเลื่อนลงหา
        repeat(15) { if (!scroll(false)) return@repeat; sleep(250) }
        findNode(match)?.let { return it }
        repeat(25) {
            if (!scroll(true)) return null
            sleep(350)
            findNode(match)?.let { return it }
        }
        return null
    }

    /** ช่องกรอก (editable) ตัวแรกที่อยู่ถัดจากข้อความ label ตามลำดับบนจอ */
    private fun findInputAfter(labels: List<String>): AccessibilityNodeInfo? {
        for (root in roots()) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all)
            val i = all.indexOfFirst { containsAny(it, labels) }
            if (i < 0) continue
            all.drop(i + 1).firstOrNull { isInput(it) }?.let { return it }
            // สำรอง: ช่องกรอกที่อยู่ใต้ label ใกล้ที่สุด
            val lb = Rect().also { all[i].getBoundsInScreen(it) }
            return all.filter { isInput(it) }
                .map { n -> n to Rect().also { n.getBoundsInScreen(it) } }
                .filter { it.second.top >= lb.top - 10 }
                .minByOrNull { it.second.top - lb.top }?.first
        }
        return null
    }

    // ------------------------------------------------------------------ actions

    private fun setText(n: AccessibilityNodeInfo, text: String) {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sleep(200)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        log("ใส่ \"$text\" (${if (ok) "สำเร็จ" else "ไม่ตอบสนอง"})")
        sleep(200)
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
    }

    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            sleep(500); return
        }
        tap(n, 60)
        sleep(500)
    }

    private fun longPress(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isLongClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            sleep(700); return
        }
        tap(n, 900)
        sleep(700)
    }

    private fun tap(n: AccessibilityNodeInfo, durationMs: Long) {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val latch = CountDownLatch(1)
        svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
    }

    private fun scroll(forward: Boolean): Boolean {
        val s = findNode { it.isScrollable && it.packageName?.toString() == Prefs.ANKI_PKG } ?: return false
        return s.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    // ------------------------------------------------------------------ tree helpers

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in svc.windows) w.root?.let { out.add(it) }
        } catch (_: Exception) {
        }
        svc.rootInActiveWindow?.let { r -> if (out.none { it == r }) out.add(0, r) }
        return out
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun findNode(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in roots()) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all)
            all.firstOrNull { n -> n.isVisibleToUser && pred(n) }?.let { return it }
        }
        return null
    }

    private fun waitFor(ms: Long, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findNode(pred)?.let { return it }
            sleep(250)
        }
        return null
    }

    private fun waitGone(ms: Long, pred: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (findNode(pred) == null) return true
            sleep(250)
        }
        return false
    }

    private fun isInput(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.contains("EditText") == true)

    fun dump(): String {
        val sb = StringBuilder()
        for ((wi, root) in roots().withIndex()) {
            sb.append("=== window $wi  pkg=${root.packageName}\n")
            dumpNode(root, 0, sb)
        }
        return sb.toString()
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (n == null || depth > 60) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val flags = buildString {
            if (n.isClickable) append(" CLICK")
            if (n.isLongClickable) append(" LONG")
            if (n.isEditable) append(" EDIT")
            if (n.isScrollable) append(" SCROLL")
            if (!n.isVisibleToUser) append(" hidden")
        }
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = n.text?.toString()?.replace('\n', ' ')?.take(60) ?: ""
        val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.take(40) ?: ""
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
        sb.append("  ".repeat(depth)).append(cls)
        if (txt.isNotEmpty()) sb.append(" \"").append(txt).append('"')
        if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc).append('"')
        if (id.isNotEmpty()) sb.append(" #").append(id)
        sb.append(flags).append(" [").append(r.left).append(',').append(r.top).append(']').append('\n')
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, sb)
    }

    // ------------------------------------------------------------------ text helpers

    private fun label(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: ""

    private fun norm(s: String) = s.replace(' ', ' ').trim().lowercase(Locale.ROOT)

    private fun split(s: String) = s.split('|').map { norm(it) }.filter { it.isNotEmpty() }

    private fun exactAny(n: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val t = norm(n.text?.toString() ?: "")
        val d = norm(n.contentDescription?.toString() ?: "")
        return labels.any { it == t || it == d }
    }

    private fun containsAny(n: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val t = norm(label(n))
        return t.isNotEmpty() && t.length < 80 && labels.any { t.contains(it) }
    }

    /** ความเร็ว: ช่วงรอแบบตายตัวคูณด้วยค่านี้ (การรอหน้าโหลดยังรอจนเจอเหมือนเดิม) */
    var speed: Float = prefs.speedFactor

    private fun sleep(ms: Long) = Thread.sleep((ms * speed).toLong().coerceAtLeast(60))

    private fun fail(msg: String): Nothing {
        log("✗ $msg")
        log("---- โครงหน้าจอตอนที่หาไม่เจอ ----")
        log(dump())
        throw StepFailed(msg)
    }
}
