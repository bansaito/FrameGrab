package com.v2f.framegrab

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * ตัวอัดวิดีโอ: ภาพจอ (VirtualDisplay) → SurfaceTexture → วาดเฉพาะกรอบลง MediaCodec (H.264) → MP4
 * และวาดภาพเต็มจอส่งต่อให้ ImageReader เดิม (mirror) ทุก ~1/15 วิ ฟีเจอร์เดิม (กรอบ/📸/ตรวจสตอรี่) จึงทำงานต่อได้
 *
 * โหมด:
 *  - record: เขียนไฟล์ทันที แบ่งไฟล์ทุก [splitMs], พัก/อัดต่อได้
 *  - replay: เก็บวนไว้ในหน่วยความจำ [replayMs] ล่าสุด กด save() แล้วเขียนออกเป็นไฟล์
 */
class VideoEngine(
    private val vdW: Int, private val vdH: Int,
    crop: Rect,
    private val mirror: Surface?,
    private val dir: File,
    private val replayMs: Long,          // 0 = โหมดอัดปกติ
    private val splitMs: Long = 10 * 60_000L,
    private val listener: Listener,
) {
    interface Listener {
        /** ไฟล์เสร็จแล้ว 1 ไฟล์ (เรียกจากเธรดวิดีโอ) */
        fun onFile(f: File)
        /** ภาพนิ่ง/ไม่มีภาพใหม่นานเกินกำหนด (ไลฟ์จบ) */
        fun onFrozen()
        /** หยุดเสร็จ ปล่อยทรัพยากรแล้ว */
        fun onReleased()
        fun onError(msg: String)
    }

    private val crop = Rect(crop)
    val outW = (crop.width() / 2 * 2).coerceIn(64, 1080 / 2 * 2)
    val outH = ((crop.height() * outW.toFloat() / crop.width()).toInt() / 2 * 2).coerceAtLeast(64)

    private val thread = HandlerThread("fg-video").apply { start() }
    private val h = Handler(thread.looper)

    private lateinit var codec: MediaCodec
    private lateinit var codecSurface: Surface
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var encSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var mirSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var dummy: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var prog = 0
    private lateinit var st: SurfaceTexture
    /** ให้ VirtualDisplay ส่งภาพมาที่นี่ */
    lateinit var inputSurface: Surface
        private set

    // ---------- สถานะ ----------
    @Volatile var paused = false
        private set
    @Volatile var bytesWritten = 0L
        private set
    @Volatile var stopped = false
        private set
    private var pauseStartNs = 0L
    private var pausedTotalNs = 0L
    private var lastEncNs = 0L
    private var lastMirNs = 0L
    private var lastFrameAt = SystemClock.uptimeMillis()
    private var outFormat: MediaFormat? = null

    // record
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var fileStartUs = -1L
    private var curFile: File? = null
    private var fileNo = 0
    private val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

    // replay: (ข้อมูล, เวลา µs, flags)
    private class Pkt(val data: ByteArray, val us: Long, val flags: Int)
    private val ring = ArrayDeque<Pkt>()

    // ตรวจภาพนิ่ง
    var frozenAfterMs = 10_000L
    private var lastSig: IntArray? = null
    private var sameSince = SystemClock.uptimeMillis()
    private var lastSigAt = 0L
    private var fbo = 0; private var fboTex = 0
    private val SIG = 24
    private val sigBuf = ByteBuffer.allocateDirect(SIG * SIG * 4).order(ByteOrder.nativeOrder())

    /** เริ่ม (บล็อกจนพร้อม) คืน false ถ้าเริ่มไม่ได้ */
    fun start(): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        h.post {
            ok = try { setup(); true } catch (e: Exception) { listener.onError(e.message ?: "เริ่มอัดวิดีโอไม่ได้"); false }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (ok) h.postDelayed(watchdog, 1000)
        return ok
    }

    private fun setup() {
        // ---- ตัวเข้ารหัส ----
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 30 * 0.12f).toInt().coerceIn(1_500_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                outFormat = f
                if (replayMs <= 0) openMuxer()
            }
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = c.getOutputBuffer(i)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) onSample(buf, info)
                } catch (e: Exception) { }
                try { c.releaseOutputBuffer(i, false) } catch (e: Exception) { }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finishAll()
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { listener.onError("ตัวเข้ารหัสวิดีโอผิดพลาด"); finishAll() }
        }, h)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codecSurface = codec.createInputSurface()
        codec.start()

        // ---- EGL ----
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attr, 0, cfgs, 0, 1, n, 0)
        val cfg = cfgs[0] ?: throw IllegalStateException("ไม่มี EGL config")
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        encSurf = EGL14.eglCreateWindowSurface(eglDisplay, cfg, codecSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (mirror != null) mirSurf = try {
            EGL14.eglCreateWindowSurface(eglDisplay, cfg, mirror, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: Exception) { EGL14.EGL_NO_SURFACE }
        EGL14.eglMakeCurrent(eglDisplay, encSurf, encSurf, eglContext)

        // ---- shader + texture ----
        prog = program(
            """
            attribute vec2 aPos; attribute vec2 aUv; uniform mat4 uTex; varying vec2 vUv;
            void main(){ gl_Position = vec4(aPos,0.0,1.0); vUv = (uTex * vec4(aUv,0.0,1.0)).xy; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vUv; uniform samplerExternalOES sTex;
            void main(){ gl_FragColor = texture2D(sTex, vUv); }
            """.trimIndent()
        )
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // ภาพย่อ 24x24 ไว้ตรวจภาพนิ่ง
        val ft = IntArray(1); GLES20.glGenTextures(1, ft, 0); fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SIG, SIG, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        st = SurfaceTexture(tex)
        st.setDefaultBufferSize(vdW, vdH)
        st.setOnFrameAvailableListener({ onFrame() }, h)
        inputSurface = Surface(st)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private val pos: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    /** พิกัดภาพ (ก่อนคูณเมทริกซ์ของ SurfaceTexture): v=0 คือด้านล่างของภาพ */
    private fun uvFor(r: Rect?): FloatBuffer {
        val l = (r?.left ?: 0) / vdW.toFloat(); val rr = (r?.right ?: vdW) / vdW.toFloat()
        val b = 1f - (r?.bottom ?: vdH) / vdH.toFloat(); val t = 1f - (r?.top ?: 0) / vdH.toFloat()
        return floatBuf(floatArrayOf(l, b, rr, b, l, t, rr, t))
    }
    private val uvCrop by lazy { uvFor(crop) }
    private val uvFull by lazy { uvFor(null) }
    private val texM = FloatArray(16)

    private fun draw(uv: FloatBuffer, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos"); val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTex"), 1, false, texM, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun onFrame() {
        if (stopped) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, encSurf, encSurf, eglContext)
            st.updateTexImage()
            st.getTransformMatrix(texM)
            val ns = st.timestamp
            lastFrameAt = SystemClock.uptimeMillis()
            // ลงตัวเข้ารหัส ≤30 เฟรม/วิ
            if (!paused && ns - lastEncNs >= 30_000_000L) {
                lastEncNs = ns
                draw(uvCrop, outW, outH)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encSurf, ns - pausedTotalNs)
                EGL14.eglSwapBuffers(eglDisplay, encSurf)
                checkFrozen()
            }
            // ภาพเต็มจอให้ ImageReader เดิม ≤15 เฟรม/วิ
            if (mirSurf != EGL14.EGL_NO_SURFACE && ns - lastMirNs >= 60_000_000L) {
                lastMirNs = ns
                EGL14.eglMakeCurrent(eglDisplay, mirSurf, mirSurf, eglContext)
                draw(uvFull, vdW, vdH)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, mirSurf, ns)
                EGL14.eglSwapBuffers(eglDisplay, mirSurf)
                EGL14.eglMakeCurrent(eglDisplay, encSurf, encSurf, eglContext)
            }
        } catch (e: Exception) { }
    }

    /** ทุก ~0.5 วิ: ย่อภาพในกรอบเหลือ 24x24 ถ้าเหมือนเดิมทุกจุดนานเกินกำหนด = ไลฟ์จบ/ภาพค้าง */
    private fun checkFrozen() {
        if (frozenAfterMs <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSigAt < 500) return
        lastSigAt = now
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        draw(uvCrop, SIG, SIG)
        sigBuf.position(0)
        GLES20.glReadPixels(0, 0, SIG, SIG, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, sigBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val sig = IntArray(SIG * SIG) { i ->
            val o = i * 4
            ((sigBuf.get(o).toInt() and 0xFF) * 299 + (sigBuf.get(o + 1).toInt() and 0xFF) * 587 + (sigBuf.get(o + 2).toInt() and 0xFF) * 114) / 1000
        }
        val p = lastSig
        if (p == null || sig.indices.any { abs(sig[it] - p[it]) > 4 }) sameSince = now
        lastSig = sig
        if (now - sameSince >= frozenAfterMs) { sameSince = now; listener.onFrozen() }
    }

    /** ไม่มีภาพใหม่จากจอเลย (จอนิ่งสนิท ระบบไม่ส่งภาพ) ก็นับเป็นภาพนิ่ง */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            if (stopped) return
            if (frozenAfterMs > 0 && !paused && SystemClock.uptimeMillis() - lastFrameAt >= frozenAfterMs) {
                lastFrameAt = SystemClock.uptimeMillis(); listener.onFrozen()
            }
            h.postDelayed(this, 1000)
        }
    }

    // ---------- เขียนไฟล์ ----------
    private fun newFile(): File {
        fileNo++
        return File(dir.apply { mkdirs() }, "FrameGrab_${stamp}" + (if (fileNo > 1) "_$fileNo" else "") + ".mp4")
    }

    private fun openMuxer() {
        val f = outFormat ?: return
        val file = newFile()
        val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = m.addTrack(f)
        m.start()
        muxer = m; curFile = file; fileStartUs = -1L
    }

    private fun closeMuxer() {
        val m = muxer ?: return
        muxer = null
        try { m.stop() } catch (e: Exception) { }
        try { m.release() } catch (e: Exception) { }
        curFile?.let { if (it.length() > 0) listener.onFile(it) else it.delete() }
        curFile = null
    }

    private fun onSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (replayMs > 0) {
            val a = ByteArray(info.size)
            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(a)
            ring.addLast(Pkt(a, info.presentationTimeUs, info.flags))
            // เก็บไว้แค่ช่วงที่ต้องใช้ (+ เผื่อ 2 วิ ให้ยังมีเฟรมหลักอยู่ต้นช่วง)
            val keepFrom = info.presentationTimeUs - (replayMs + 2000) * 1000
            while (ring.size > 1 && ring.first().us < keepFrom) ring.removeFirst()
            return
        }
        val m = muxer ?: return
        if (fileStartUs < 0) { if (!key) return; fileStartUs = info.presentationTimeUs }
        // แบ่งไฟล์ทุก splitMs (ตัดที่เฟรมหลัก)
        if (key && info.presentationTimeUs - fileStartUs >= splitMs * 1000) {
            closeMuxer(); openMuxer()
            fileStartUs = info.presentationTimeUs
        }
        val mm = muxer ?: return
        buf.position(info.offset); buf.limit(info.offset + info.size)
        mm.writeSampleData(track, buf, info)
        bytesWritten += info.size
    }

    /** โหมดบันทึกย้อนหลัง: เขียนช่วงล่าสุดออกเป็นไฟล์ (ทำงานบนเธรดวิดีโอ) */
    fun saveReplay(onDone: (File?) -> Unit) {
        h.post {
            val f = outFormat
            if (f == null || ring.isEmpty()) { onDone(null); return@post }
            val lastUs = ring.last().us
            val from = lastUs - replayMs * 1000
            val list = ring.toList()
            var startIdx = list.indexOfFirst { it.us >= from && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            if (startIdx < 0) startIdx = list.indexOfFirst { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            if (startIdx < 0) { onDone(null); return@post }
            val file = newFile()
            try {
                val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val tr = m.addTrack(f)
                m.start()
                val info = MediaCodec.BufferInfo()
                for (p in list.subList(startIdx, list.size)) {
                    info.set(0, p.data.size, p.us, p.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    m.writeSampleData(tr, ByteBuffer.wrap(p.data), info)
                }
                m.stop(); m.release()
                onDone(file)
            } catch (e: Exception) { file.delete(); onDone(null) }
        }
    }

    fun setPaused(p: Boolean) {
        h.post {
            if (p == paused) return@post
            if (p) { pauseStartNs = System.nanoTime(); paused = true }
            else {
                pausedTotalNs += System.nanoTime() - pauseStartNs
                paused = false
                sameSince = SystemClock.uptimeMillis(); lastFrameAt = sameSince
            }
        }
    }

    /** หยุดและปิดไฟล์ (รอให้ตัวเข้ารหัสส่งเฟรมสุดท้ายออกมาก่อน) */
    fun stop() {
        h.post {
            if (stopped) return@post
            stopped = true
            h.removeCallbacks(watchdog)
            try { codec.signalEndOfInputStream() } catch (e: Exception) { finishAll() }
            // กันค้าง: ถ้าไม่ได้สัญญาณจบใน 1.5 วิ ปิดเลย
            h.postDelayed({ finishAll() }, 1500)
        }
    }

    private var finished = false
    private fun finishAll() {
        if (finished) return
        finished = true
        stopped = true
        closeMuxer()
        try { codec.stop() } catch (e: Exception) { }
        try { codec.release() } catch (e: Exception) { }
        try { st.release() } catch (e: Exception) { }
        try { inputSurface.release() } catch (e: Exception) { }
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (encSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encSurf)
            if (mirSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, mirSurf)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        } catch (e: Exception) { }
        try { codecSurface.release() } catch (e: Exception) { }
        ring.clear()
        listener.onReleased()
        thread.quitSafely()
    }
}
