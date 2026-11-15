package com.v2f.framegrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * อ่านชื่อบัญชีจาก "ภาพเต็มจอ" (ทำงานในเครื่อง ไม่ใช้เน็ต) — ใช้แทนเมื่อการช่วยเหลือพิเศษไม่ทำงาน
 *  1) ML Kit หา "ตำแหน่ง" บรรทัดตัวหนังสือบนจอ (หาเจอทุกภาษา) + อ่านชื่ออังกฤษ (@handle) ได้แม่น
 *  2) ถ้าบรรทัดนั้นไม่ใช่ตัวอังกฤษ (เช่น ชื่อไทย) ตัดเฉพาะบรรทัดนั้นไปให้ Tesseract (ไทย+อังกฤษ) อ่าน
 * ตำแหน่งที่ดูเหมือน HoldService.findUsername:
 *  - แถบหัวสตอรี่: บน 3–30% ของจอ ฝั่งซ้าย 75% → บรรทัดบนสุด-ซ้ายสุด
 *  - ชื่อเหนือแคปชันมุมซ้ายล่าง: 55–93% ของจอ ฝั่งซ้าย 50% → บรรทัดบนสุด
 * ต้องเรียกจากเธรดเบื้องหลัง
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
    // คำไทยที่ไม่ใช่ชื่อ (ปุ่ม/แท็บ/เวลา)
    private val THAI_NOISE = Regex("""(ติดตาม|กำลังติดตาม|สำหรับคุณ|เพื่อน|ข้อความ|ตอบกลับ|ส่ง|แชร์|ถูกใจ|ความคิดเห็น|ดูเพิ่มเติม|แปล|ไลฟ์|สตอรี่|ได้รับการสนับสนุน|ผู้สนับสนุน|นาที|ชม\.|ชั่วโมง|วันที่แล้ว|สัปดาห์)""")

    fun enabled(c: Context) = Prefs.nameFromScreen(c) && Prefs.nameOcr(c)

    fun find(c: Context, full: Bitmap): String? {
        if (!enabled(c)) return null
        val dbg = StringBuilder()
        val res = try { read(c, full, dbg) } catch (e: Throwable) { dbg.append("ผิดพลาด: ${e.javaClass.simpleName} ${e.message ?: ""}"); null }
        Prefs.setLastOcr(c, (if (res != null) "ได้ \"$res\"" else "ไม่พบชื่อ") + " · " + dbg.toString().take(160))
        return res
    }

    private fun read(c: Context, full: Bitmap, dbg: StringBuilder): String? {
        // ครั้งแรกต้องโหลดโมเดล อาจช้า — ให้เวลา 4 วิ
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(full, 0)), 4000, TimeUnit.MILLISECONDS)
        val w = full.width; val h = full.height
        data class L(val raw: String, val box: Rect)
        val lines = ArrayList<L>()
        for (b in text.textBlocks) for (l in b.lines) {
            val box = l.boundingBox ?: continue
            lines.add(L(l.text.trim(), box))
        }
        // ดู "บรรทัดแรก" ของแต่ละตำแหน่งเท่านั้น — ถ้าบรรทัดแรกไม่ใช่ชื่อ จะไม่ไปหยิบคำในแคปชันข้างล่างมาแทน
        val head = lines.filter { it.box.top in (h * 0.03f).toInt()..(h * 0.30f).toInt() && it.box.left < w * 0.75f }
            .minWithOrNull(compareBy<L>({ it.box.top / (h / 40) }, { it.box.left }))
        val foot = lines.filter { it.box.top in (h * 0.55f).toInt()..(h * 0.93f).toInt() && it.box.left < w * 0.5f }
            .minByOrNull { it.box.top }
        for ((tag, l) in listOf("บน" to head, "ล่าง" to foot)) {
            if (l == null) continue
            dbg.append("$tag:\"${l.raw.take(24)}\" ")
            handleOf(l.raw)?.let { return it }
            // ไม่ใช่ตัวอังกฤษ: ให้ Tesseract อ่านเฉพาะบรรทัดนี้ (ไทย+อังกฤษ)
            val th = readThai(c, full, l.box)
            dbg.append("→ไทย:\"${th ?: ""}\" ")
            th?.let { thaiName(it) }?.let { return it }
        }
        if (head == null && foot == null) dbg.append("ไม่เจอตัวหนังสือตรงตำแหน่งชื่อ (${lines.size} บรรทัดทั้งจอ)")
        return null
    }

    // บรรทัดนี้เป็นชื่อบัญชีอังกฤษไหม: ทั้งบรรทัด หรือคำแรก (เช่น "wa.veva 2h" / "wa.veva • Follow")
    private fun handleOf(raw: String): String? {
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

    // ทำความสะอาดผลจาก Tesseract: ตัดส่วนหลัง • · | (เวลา/ปุ่ม) แล้วตรวจว่าเหมือนชื่อ
    private fun thaiName(raw: String): String? {
        val t = raw.lineSequence().firstOrNull { it.isNotBlank() }?.split('•', '·', '|')?.firstOrNull()?.trim()
            ?.trim('@', '.', ',', '-', '_', '"', '\'', ' ') ?: return null
        if (t.length !in 2..30 || t.split(' ').size > 4) return null
        if (t.count { it.isLetter() } < 2) return null
        if (THAI_NOISE.containsMatchIn(t) || t.lowercase().replace(" ", "") in NOISE) return null
        return t
    }

    // ---------- Tesseract (ไทย + อังกฤษ) ----------
    private var tess: TessBaseAPI? = null
    private var tessFailed = false

    @Synchronized private fun readThai(c: Context, full: Bitmap, box: Rect): String? {
        val api = tess ?: if (tessFailed) return null else initTess(c) ?: return null
        val crop = prepLine(full, box) ?: return null
        return try {
            api.setImage(crop)
            val s = api.getUTF8Text()?.trim()
            val conf = api.meanConfidence()
            api.clear()
            if (s.isNullOrEmpty() || conf < 45) null else s
        } catch (e: Throwable) { null } finally { crop.recycle() }
    }

    private fun initTess(c: Context): TessBaseAPI? {
        return try {
            // ไฟล์ภาษาอยู่ใน assets/tessdata — คัดลอกไปที่เก็บของแอปครั้งแรก (Tesseract อ่านจากไฟล์จริง)
            val base = File(c.filesDir, "ocr")
            val dir = File(base, "tessdata").apply { mkdirs() }
            for (lang in listOf("tha", "eng")) {
                val f = File(dir, "$lang.traineddata")
                if (f.isFile && f.length() > 0) continue
                c.assets.open("tessdata/$lang.traineddata").use { inp -> f.outputStream().use { inp.copyTo(it) } }
            }
            val api = TessBaseAPI()
            if (!api.init(base.absolutePath, "tha+eng")) { api.recycle(); tessFailed = true; return null }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            tess = api
            api
        } catch (e: Throwable) { tessFailed = true; null }
    }

    /** ตัดบรรทัดชื่อ → ขาวดำ, ขยายให้ตัวสูง ~48px, ตัวอักษรสีเข้มบนพื้นสว่าง, มีขอบขาว (Tesseract อ่านแม่นขึ้น) */
    private fun prepLine(full: Bitmap, box: Rect): Bitmap? {
        val pad = (box.height() * 0.25f).toInt()
        val r = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        if (!r.intersect(0, 0, full.width, full.height) || r.width() < 8 || r.height() < 8) return null
        val scale = (48f / box.height().coerceAtLeast(1)).coerceIn(1f, 4f)
        val ow = (r.width() * scale).toInt(); val oh = (r.height() * scale).toInt()
        // ความสว่างเฉลี่ยของพื้น: พื้นมืด (ชื่อสีขาวบนวิดีโอ) → กลับสี
        var sum = 0L; var n = 0
        val step = maxOf(1, r.width() / 40)
        for (y in r.top until r.bottom step maxOf(1, r.height() / 10)) for (x in r.left until r.right step step) {
            val p = full.getPixel(x, y); sum += (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10; n++
        }
        val dark = n > 0 && sum / n < 128
        val cm = ColorMatrix().apply { setSaturation(0f) }
        if (dark) cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        val border = 12
        val out = Bitmap.createBitmap(ow + border * 2, oh + border * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(full, r, Rect(border, border, border + ow, border + oh), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }
}
