package com.v2f.framegrab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** เก็บเฟรมที่จับได้ไว้ในพื้นที่ของแอป และบันทึกลงแกลเลอรีเมื่อผู้ใช้ต้องการ */
object FrameStore {

    /** kind: 'c' = อัดต่อเนื่อง (t = มิลลิวินาทีนับจากเริ่มอัด), 's' = จับทีละภาพ (t = มิลลิวินาทีของวัน) */
    data class Frame(val file: File, val index: Int, val kind: Char, val tMs: Long) {
        val label: String
            get() = if (kind == 'c') {
                val s = tMs / 1000.0
                String.format(Locale.US, "%02d:%06.3f", (s / 60).toInt(), s % 60)
            } else {
                val sec = tMs / 1000
                String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
            }
    }

    fun dir(c: Context): File = File(c.filesDir, "frames").apply { mkdirs() }
    /** video = true: ชุดภาพที่แตกจากไฟล์วิดีโอ เก็บแยกจากภาพที่จับจากจอ (แตกวิดีโอใหม่ = ล้างชุดนี้เอง ภาพจากจอไม่หาย) */
    fun dir(c: Context, video: Boolean): File = if (video) File(c.filesDir, "vframes").apply { mkdirs() } else dir(c)

    private val NAME = Regex("""f_(\d+)_([cs])_(\d+)\.jpg""")

    fun list(c: Context, video: Boolean = false): List<Frame> =
        (dir(c, video).listFiles() ?: emptyArray())
            .mapNotNull { f ->
                NAME.matchEntire(f.name)?.let { m ->
                    Frame(f, m.groupValues[1].toInt(), m.groupValues[2][0], m.groupValues[3].toLong())
                }
            }
            .sortedBy { it.index }

    fun count(c: Context, video: Boolean = false): Int = list(c, video).size

    // ---------- ชื่อบัญชีของภาพ (ไว้ตั้งชื่อไฟล์ตอนบันทึกลงแกลเลอรี) ----------
    private fun ownerFile(d: File) = File(d, ".owners")
    @Synchronized private fun owners(d: File): MutableMap<String, String> =
        ownerFile(d).takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap(LinkedHashMap()) ?: LinkedHashMap()
    @Synchronized fun setOwner(c: Context, f: File, user: String?) {
        if (user.isNullOrBlank()) return
        val d = f.parentFile ?: dir(c)
        val m = owners(d); m[f.name] = user
        ownerFile(d).writeText(m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }
    fun ownerOf(c: Context, f: File): String? = owners(f.parentFile ?: dir(c)).let { m -> m[f.name] ?: if (f.name.contains("_c_")) m["*c"] else null }
    /** ภาพอัดต่อเนื่องทั้งชุดเป็นของคนเดียวกัน: เก็บครั้งเดียว */
    fun setRecordingOwner(c: Context, user: String?) { setOwner(c, File(dir(c), "*c"), user) }

    /** ตัดอักขระที่ใช้ในชื่อไฟล์ไม่ได้ (รวมอีโมจิ) ยาวไม่เกิน 40 ตัว */
    fun cleanName(s: String?): String? {
        if (s == null) return null
        // สระ/วรรณยุกต์ไทย (ั ิ ่ ้ ฯลฯ) เป็น "เครื่องหมายกำกับ" ไม่ใช่ตัวอักษร — ต้องเก็บไว้ ไม่งั้นชื่อไทยเพี้ยน
        val t = s.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' ||
                Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }
            .trim().replace(Regex("\\s+"), "_").trim('.', '_', '-').take(40)
        return t.ifEmpty { null }
    }

    /** ชื่อไฟล์แบบ  ชื่อบัญชี-วว-ดด-ปปปป-0001.jpg  (ไม่มีชื่อบัญชี = FrameGrab-...) เลขรันแยกตามชื่อ+วัน */
    @Synchronized fun galleryName(c: Context, user: String?, whenMs: Long, ext: String): String {
        val who = cleanName(user) ?: "FrameGrab"
        val day = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(whenMs))
        val sp = c.getSharedPreferences("framegrab_seq", Context.MODE_PRIVATE)
        val key = "$who|$day"
        val n = sp.getInt(key, 0) + 1
        sp.edit().putInt(key, n).apply()
        return String.format(Locale.US, "%s-%s-%04d.%s", who, day, n, ext)
    }

    // ---------- จำว่าภาพไหนบันทึกลงแกลเลอรีแล้ว (ไฟล์ .saved ในโฟลเดอร์เดียวกัน ล้างภาพ = ล้างด้วย) ----------
    private fun markFile(d: File) = File(d, ".saved")
    private fun names(d: File): MutableSet<String> =
        markFile(d).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
    @Synchronized fun savedNames(c: Context, video: Boolean = false): MutableSet<String> = names(dir(c, video))
    fun isSaved(c: Context, f: File): Boolean = f.name in names(f.parentFile ?: dir(c))
    @Synchronized fun markSaved(c: Context, files: Collection<File>) {
        files.groupBy { it.parentFile ?: dir(c) }.forEach { (d, fs) ->
            val s = names(d); s.addAll(fs.map { it.name }); markFile(d).writeText(s.joinToString("\n"))
        }
    }
    @Synchronized fun unmark(c: Context, f: File) {
        val d = f.parentFile ?: dir(c)
        val s = names(d); if (s.remove(f.name)) markFile(d).writeText(s.joinToString("\n"))
    }

    fun clear(c: Context, video: Boolean = false) {
        val d = dir(c, video)
        // ย้ายทั้งโฟลเดอร์ไปทิ้ง (ทันที) แล้วค่อยลบไฟล์อีกเธรด — ภาพหลายร้อยภาพ ลบทีละไฟล์ทำให้เริ่มอัดช้า
        val trash = File(c.filesDir, ".trash_" + System.nanoTime())
        if (d.renameTo(trash)) {
            d.mkdirs()
            Thread {
                c.filesDir.listFiles()?.filter { it.name.startsWith(".trash_") }?.forEach { it.deleteRecursively() }
            }.start()
        } else d.listFiles()?.forEach { it.delete() }
    }

    /** ขนาดรวมของภาพทั้งหมด (ไบต์) */
    fun totalBytes(c: Context, video: Boolean = false): Long = dir(c, video).listFiles()?.sumOf { it.length() } ?: 0L

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** ถามยืนยันแล้วล้างภาพทั้งหมดในแอป (ภาพที่บันทึกลงแกลเลอรีแล้วไม่ถูกลบ) */
    fun confirmClear(a: android.app.Activity, video: Boolean = false, onDone: () -> Unit) {
        val n = count(a, video)
        if (n == 0) {
            android.widget.Toast.makeText(a, "ไม่มีภาพให้ล้าง", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(a)
            .setTitle("ล้างภาพทั้งหมด $n ภาพ?")
            .setMessage("ลบภาพที่จับไว้ในแอป (${sizeLabel(totalBytes(a, video))})\nภาพที่บันทึกลงแกลเลอรีแล้วจะยังอยู่")
            .setPositiveButton("ล้างทั้งหมด") { _, _ ->
                clear(a, video)
                android.widget.Toast.makeText(a, "🧹 ล้างภาพแล้ว", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    @Synchronized
    fun save(c: Context, bmp: Bitmap, kind: Char, tMs: Long, quality: Int = 92, video: Boolean = false): File {
        val next = (list(c, video).maxOfOrNull { it.index } ?: 0) + 1
        val f = File(dir(c, video), String.format(Locale.US, "f_%06d_%c_%09d.jpg", next, kind, tMs))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return f
    }

    /** ลบเฟรมที่อัดหลังเวลา cutoffMs (ใช้ตอนหยุดจากแถบแจ้งเตือน เพื่อตัดภาพตอนดึงแถบลงมา) */
    fun dropAfter(c: Context, cutoffMs: Long) {
        list(c).filter { it.kind == 'c' && it.tMs > cutoffMs }.forEach { unmark(c, it.file); it.file.delete() }
    }

    /** บันทึกภาพเดียวลง Pictures/FrameGrab คืน true ถ้าสำเร็จ */
    fun saveOneToGallery(c: Context, fr: Frame): Boolean = saveFileToGallery(c, fr.file, fr.index)

    fun saveFileToGallery(c: Context, f: File, index: Int): Boolean {
        val name = galleryName(c, ownerOf(c, f), f.lastModified(), "jpg")
        return copyToGallery(c, f, name, "Pictures/FrameGrab").also { if (it) markSaved(c, listOf(f)) }
    }

    /** ย้ายวิดีโอไปไว้ใน Movies/FrameGrab ของแกลเลอรี */
    fun saveVideoToGallery(c: Context, f: File, user: String? = null): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, galleryName(c, user, f.lastModified(), "mp4"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FrameGrab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            c.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) { c.contentResolver.delete(uri, null, null); false }
    }

    private fun copyToGallery(c: Context, src: File, displayName: String, folder: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            c.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            c.contentResolver.delete(uri, null, null)
            false
        }
    }

    /** บันทึกเฟรมทั้งหมดลง Pictures/FrameGrab/<วันเวลา> คืนจำนวนที่บันทึกได้ */
    fun saveAllToGallery(c: Context, frames: List<Frame>): Int {
        val folder = "Pictures/FrameGrab"
        var ok = 0
        val done = ArrayList<File>()
        for ((i, fr) in frames.withIndex()) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, galleryName(c, ownerOf(c, fr.file), fr.file.lastModified(), "jpg"))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            try {
                c.contentResolver.openOutputStream(uri)?.use { out ->
                    fr.file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                c.contentResolver.update(uri, values, null, null)
                ok++
                done.add(fr.file)
            } catch (e: Exception) {
                c.contentResolver.delete(uri, null, null)
            }
        }
        if (done.isNotEmpty()) markSaved(c, done)
        return ok
    }
}
