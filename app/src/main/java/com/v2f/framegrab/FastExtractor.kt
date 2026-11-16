package com.v2f.framegrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * แตกเฟรมแบบเร็ว: ถอดรหัสวิดีโอ "ต่อเนื่องครั้งเดียว" ทั้งคลิป (ไม่ต้องย้อนไปเฟรมหลักทุกภาพแบบ MediaMetadataRetriever)
 * ตัวถอดรหัส (MediaCodec) → SurfaceTexture → วาดด้วย GPU (แปลงสี/หมุนภาพให้) → อ่านพิกเซลเป็น Bitmap
 * เฟรมที่ไม่ต้องการถูกข้ามโดยไม่วาด จึงเร็วเกือบเท่าความเร็วถอดรหัสของเครื่อง
 * onFrame(bitmap, ptsMs): เจ้าของ bitmap คือผู้รับ (ต้อง recycle เอง) คืน false = หยุด
 * เรียกจากเธรดเบื้องหลัง; ถ้าโยน exception ให้ผู้เรียกใช้วิธีเดิมแทน
 */
class FastExtractor(private val c: Context, private val uri: Uri) {

    fun run(targetsMs: List<Long>, onFrame: (Bitmap, Long) -> Boolean) {
        if (targetsMs.isEmpty()) return
        val ex = MediaExtractor()
        ex.setDataSource(c, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: run { ex.release(); throw IllegalStateException("ไม่มีภาพวิดีโอ") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val vw = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val vh = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val rot = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val ow = if (rot % 180 != 0) vh else vw
        val oh = if (rot % 180 != 0) vw else vh
        val frameUs = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) (1_000_000 / fmt.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)).toLong() else 33_333L

        val gl = Gl(ow, oh)
        val cbThread = HandlerThread("fx-cb").apply { start() }
        val st = SurfaceTexture(gl.tex)
        val lock = Object()
        var available = false
        st.setOnFrameAvailableListener({ synchronized(lock) { available = true; lock.notifyAll() } }, Handler(cbThread.looper))
        val surface = Surface(st)
        val dec = MediaCodec.createDecoderByType(mime)
        try {
            dec.configure(fmt, surface, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var ti = 0
            val mtx = FloatArray(16)
            var idle = 0   // รอผลจากตัวถอดรหัสนานเกิน ~5 วิ = ถือว่าค้าง
            loop@ while (ti < targetsMs.size) {
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi < 0) { if (++idle > 500) throw IllegalStateException("ตัวถอดรหัสค้าง"); continue }
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                // เฟรมนี้ถึงเวลาของภาพถัดไปแล้วหรือยัง (เผื่อครึ่งเฟรม)
                val want = info.size > 0 && pts + frameUs / 2 >= targetsMs[ti] * 1000
                dec.releaseOutputBuffer(oi, want)
                if (want) {
                    synchronized(lock) {
                        val until = System.currentTimeMillis() + 1000
                        while (!available && System.currentTimeMillis() < until) lock.wait(50)
                        if (!available) throw IllegalStateException("ไม่ได้รับภาพจากตัวถอดรหัส")
                        available = false
                    }
                    st.updateTexImage()
                    st.getTransformMatrix(mtx)
                    val bmp = gl.draw(mtx)
                    // ข้ามเป้าหมายที่เฟรมนี้ครอบคลุมแล้ว (ช่วงถี่กว่าเฟรมของวิดีโอ)
                    while (ti < targetsMs.size && targetsMs[ti] * 1000 <= pts + frameUs / 2) ti++
                    if (!onFrame(bmp, pts / 1000)) break@loop
                }
                if (eos) break
            }
        } finally {
            try { dec.stop() } catch (e: Exception) { }
            dec.release()
            surface.release()
            st.release()
            cbThread.quitSafely()
            gl.release()
            ex.release()
        }
    }

    /** OpenGL บนพื้นที่วาดนอกจอ ขนาดเท่าภาพ */
    private class Gl(val w: Int, val h: Int) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ctx: EGLContext
        val surf: EGLSurface
        val tex: Int
        private val prog: Int
        private val pix = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        // วาดกลับหัว (บนของภาพอยู่แถวแรกของ glReadPixels) จะได้ไม่ต้องพลิกเอง
        private val pos = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val uv = fb(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

        init {
            val v = IntArray(2)
            EGL14.eglInitialize(display, v, 0, v, 1)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
            val cfg = cfgs[0] ?: throw IllegalStateException("EGL config")
            ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreatePbufferSurface(display, cfg, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surf, surf, ctx)) throw IllegalStateException("EGL surface")
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            prog = program(
                """attribute vec4 aPos; attribute vec4 aUv; uniform mat4 uM; varying vec2 vUv;
                   void main() { gl_Position = aPos; vUv = (uM * aUv).xy; }""",
                """#extension GL_OES_EGL_image_external : require
                   precision mediump float; varying vec2 vUv; uniform samplerExternalOES sTex;
                   void main() { gl_FragColor = texture2D(sTex, vUv); }""")
        }

        fun draw(m: FloatArray): Bitmap {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(prog)
            val ap = GLES20.glGetAttribLocation(prog, "aPos"); val au = GLES20.glGetAttribLocation(prog, "aUv")
            GLES20.glEnableVertexAttribArray(ap); GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, pos)
            GLES20.glEnableVertexAttribArray(au); GLES20.glVertexAttribPointer(au, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uM"), 1, false, m, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            pix.rewind()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pix)
            pix.rewind()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pix) }
        }

        fun release() {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglReleaseThread()
            } catch (e: Exception) { }
        }

        private fun program(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        private fun fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    }
}
