package com.v2f.framegrab

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** บันทึกสาเหตุตอนแอปเด้ง เพื่อแสดงในหน้าแรกให้ผู้ใช้คัดลอกส่งต่อได้ */
object Crash {
    @Volatile private var installed = false

    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = "FrameGrab crash " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}" +
                    "\nthread: ${t.name}\n\n"
                file(app).writeText(head + sw.toString().take(6000))
            } catch (ignored: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.isFile }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
