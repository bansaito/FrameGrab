package com.v2f.framegrab

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * อ่านชื่อบัญชีจาก "ภาพเต็มจอ" ด้วย OCR (ML Kit, ทำงานในเครื่อง ไม่ต้องใช้เน็ต)
 * ใช้แทนเมื่อการช่วยเหลือพิเศษไม่ทำงาน — อ่านได้เฉพาะชื่อที่เป็นตัวอังกฤษ/ตัวเลข/._ (แบบ @handle)
 * ตำแหน่งที่ดูเหมือน HoldService.findUsername:
 *  1) แถบหัวสตอรี่: บน 3–30% ของจอ ฝั่งซ้าย 75%  → อันบนสุด-ซ้ายสุด
 *  2) ชื่อเหนือแคปชันมุมซ้ายล่าง: 55–93% ของจอ ฝั่งซ้าย 50% → อันบนสุด
 * ต้องเรียกจากเธรดเบื้องหลัง (รอผลแบบ sync สูงสุด ~1.5 วิ)
 */
object NameOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val HANDLE = Regex("""^@?([A-Za-z0-9._]{3,30})$""")
    // คำบนจอที่หน้าตาเหมือนชื่อแต่ไม่ใช่
    private val NOISE = setOf(
        "follow", "following", "followers", "reply", "send", "message", "live", "sponsored", "suggested",
        "instagram", "tiktok", "facebook", "reels", "stories", "story", "explore", "home", "inbox", "profile",
        "friends", "foryou", "shop", "search", "share", "like", "likes", "comment", "comments", "more",
        "original", "audio", "sound", "translate", "seemore", "see", "less", "now", "today", "yesterday",
        "for", "you", "stem", "nearby", "add", "close", "view", "replies", "ads", "ad")
    private val TIME = Regex("""^\d+[smhdwy]$|^\d+$|^\d+[.,]\d+[kmKM]?$|^\d+[kmKM]$""")

    fun enabled(c: Context) = Prefs.nameFromScreen(c) && Prefs.nameOcr(c)

    fun find(c: Context, full: Bitmap): String? {
        if (!enabled(c)) return null
        return try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(full, 0)), 1500, TimeUnit.MILLISECONDS)
            val w = full.width; val h = full.height
            data class L(val raw: String, val x: Int, val y: Int)
            val lines = ArrayList<L>()
            for (b in text.textBlocks) for (l in b.lines) {
                val box = l.boundingBox ?: continue
                lines.add(L(l.text.trim(), box.left, box.top))
            }
            // บรรทัดนี้เป็นชื่อบัญชีไหม: ทั้งบรรทัด หรือคำแรก (เช่น "wa.veva 2h" / "wa.veva • Follow")
            fun handleOf(raw: String): String? {
                val first = raw.split(' ', '•', '·').firstOrNull()?.trim() ?: return null
                for (t in listOf(raw, first)) {
                    val m = HANDLE.find(t) ?: continue
                    val name = m.groupValues[1].trimEnd('.')
                    if (name.length < 3 || name.none { it.isLetter() }) continue
                    if (name.lowercase().replace(".", "") in NOISE || TIME.matches(name)) continue
                    return name
                }
                return null
            }
            // ดู "บรรทัดแรก" ของแต่ละตำแหน่งเท่านั้น — ถ้าบรรทัดแรกไม่ใช่ชื่อ (เช่น ชื่อภาษาไทย หรือแท็บ For You)
            // จะไม่ไปหยิบคำในแคปชันข้างล่างมาแทน
            val head = lines.filter { it.y in (h * 0.03f).toInt()..(h * 0.30f).toInt() && it.x < w * 0.75f }
                .minWithOrNull(compareBy<L>({ it.y / (h / 40) }, { it.x }))?.let { handleOf(it.raw) }
            val foot = lines.filter { it.y in (h * 0.55f).toInt()..(h * 0.93f).toInt() && it.x < w * 0.5f }
                .minByOrNull { it.y }?.let { handleOf(it.raw) }
            head ?: foot
        } catch (e: Exception) { null }
    }
}
