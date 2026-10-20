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

    private val NAME = Regex("""f_(\d+)_([cs])_(\d+)\.jpg""")

    fun list(c: Context): List<Frame> =
        (dir(c).listFiles() ?: emptyArray())
            .mapNotNull { f ->
                NAME.matchEntire(f.name)?.let { m ->
                    Frame(f, m.groupValues[1].toInt(), m.groupValues[2][0], m.groupValues[3].toLong())
                }
            }
            .sortedBy { it.index }

    fun count(c: Context): Int = list(c).size

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun save(c: Context, bmp: Bitmap, kind: Char, tMs: Long, quality: Int = 92): File {
        val next = (list(c).maxOfOrNull { it.index } ?: 0) + 1
        val f = File(dir(c), String.format(Locale.US, "f_%06d_%c_%09d.jpg", next, kind, tMs))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return f
    }

    /** ลบเฟรมที่อัดหลังเวลา cutoffMs (ใช้ตอนหยุดจากแถบแจ้งเตือน เพื่อตัดภาพตอนดึงแถบลงมา) */
    fun dropAfter(c: Context, cutoffMs: Long) {
        list(c).filter { it.kind == 'c' && it.tMs > cutoffMs }.forEach { it.file.delete() }
    }

    /** บันทึกเฟรมทั้งหมดลง Pictures/FrameGrab/<วันเวลา> คืนจำนวนที่บันทึกได้ */
    fun saveAllToGallery(c: Context, frames: List<Frame>): Int {
        val folder = "Pictures/FrameGrab/" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var ok = 0
        for ((i, fr) in frames.withIndex()) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, String.format(Locale.US, "frame_%03d.jpg", i + 1))
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
            } catch (e: Exception) {
                c.contentResolver.delete(uri, null, null)
            }
        }
        return ok
    }
}
