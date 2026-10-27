package com.yegorkapomidorka.headswap.aa

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Surface → OpenGL → Surface：Android Auto 解码器绘制到 SurfaceTexture，GL 线程将帧复制到编码器
 * 输入 Surface（GPU 内复制，不经 ByteBuffer），并在 idleRedrawMs 内没有新帧时自行重绘最后一帧。
 * 这样做的原因是 Android 内置的 KEY_REPEAT_PREVIOUS_FRAME_AFTER 只会重复约 10 次帧
 * （kRepeatLastFrameCount）然后停止：Android Auto 静态画面停留一秒钟后，车机就会收不到帧。
 */
class GlRelay(
    private val outSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val idleRedrawMs: Long = 100,
    private val log: (String) -> Unit = {},
    /** Android Auto 原始帧尺寸（默认同输出）以及从其中裁剪的矩形。 */
    private val srcWidth: Int = width,
    private val srcHeight: Int = height,
    crop: VideoCrop = VideoCrop.NONE,
    /** 目标输出帧率：门控不高于此频率向编码器发送帧（0 = 不限制）。 */
    targetFps: Int = 0,
) {
    private val gate = FrameGate(targetFps)
    private val thread = HandlerThread("aa-gl-relay")
    private lateinit var handler: Handler
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var st: SurfaceTexture? = null
    private var aPos = 0
    private var aTex = 0
    private var uMatrix = 0
    private val tm = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v；裁剪 = 以中心矩形代替整张纹理（uSTMatrix 由着色器叠加应用）
        put(floatArrayOf(
            -1f, -1f, crop.u0, crop.v0, 1f, -1f, crop.u1, crop.v0,
            -1f, 1f, crop.u0, crop.v1, 1f, 1f, crop.u1, crop.v1,
        ))
        position(0)
    }
    @Volatile private var haveFrame = false

    // 占位帧 → Android Auto 的过渡（缩放溶解），仅 GL 线程
    private var introProgram = 0
    private var iPos = 0
    private var iTex = 0
    private var iMatrix = 0
    private var iT = 0
    private var iBanner = 0
    private var iVideo = 0
    private var bannerTex = 0
    private var introArmed = false
    /** 过渡第一帧的时间（0 = 尚未绘制）。 */
    private var introStartNs = 0L

    /** 门控跳过的、仍在纹理中且尚未显示的最后一帧：门控一打开，tick 就会将其送出。仅 GL 线程。 */
    private var skipped = false
    private var lastFreshNs = 0L
    @Volatile private var released = false
    private var lastDrawNs = 0L
    @Volatile private var initError: Throwable? = null

    /** 解码器将帧输出到这里。 */
    lateinit var inputSurface: Surface
        private set

    val fresh = AtomicLong()
    val redraws = AtomicLong()

    /** 门控未立即送入编码器的 Android Auto 新帧数（稍后通过重绘显示或被下一帧替换）。 */
    val gated = AtomicLong()

    val targetFps: Int get() = gate.targetFps

    /** 动态调整目标帧率，无需重建编解码器。任意线程可调用。 */
    fun setTargetFps(fps: Int) {
        gate.targetFps = fps
    }

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                init()
            } catch (t: Throwable) {
                initError = t
            }
            latch.countDown()
        }
        // 失败时释放 EGL 和线程：EGL 窗口持有编码器的 Surface，备用路径（解码器直接到编码器）将无法连接它
        if (!latch.await(5, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("GL-нить не поднялась")
        }
        initError?.let {
            stop()
            throw IllegalStateException("инициализация GL: ${it.message}", it)
        }
        handler.postDelayed(tick, 20)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (haveFrame && introArmed) {
                // 过渡：按每个门控周期绘制，否则静态 Android Auto 上的动画会以每秒 10 帧运行
                if (drawGated()) redraws.incrementAndGet()
            } else if (haveFrame) {
                val now = SystemClock.elapsedRealtimeNanos()
                // 重绘：静态画面（idleRedrawMs 内无新帧）或门控延迟的最后一帧在周期内没有新帧到来
                // （视频流播放期间，被延迟的帧会被新帧替换，tick 不会动它：输出帧率保持平稳）
                val idle = now - lastDrawNs >= idleRedrawMs * 1_000_000
                val lateSkipped = skipped && now - lastFreshNs >= gate.periodNs()
                if ((idle || lateSkipped) && drawGated()) redraws.incrementAndGet()
            }
            handler.postDelayed(this, 20)
        }
    }

    /**
     * 从占位帧过渡到 Android Auto：调用后的第一帧 = 完整占位图，随后在 INTRO_MS 期间它
     * 不断放大并淡出，其下方的 Android Auto 画面从轻微缩放「滑」到原位。Bitmap 在载入 GL 后会被回收。
     */
    fun startIntro(banner: Bitmap) {
        if (released) return
        handler.post {
            try {
                if (!released) uploadIntro(banner)
            } catch (t: Throwable) {
                introArmed = false
                log("GlRelay.startIntro: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                banner.recycle()
            }
        }
    }

    private fun uploadIntro(banner: Bitmap) {
        if (introProgram == 0) {
            introProgram = buildProgram(INTRO_VERTEX, INTRO_FRAGMENT)
            iPos = GLES20.glGetAttribLocation(introProgram, "aPosition")
            iTex = GLES20.glGetAttribLocation(introProgram, "aTextureCoord")
            iMatrix = GLES20.glGetUniformLocation(introProgram, "uSTMatrix")
            iT = GLES20.glGetUniformLocation(introProgram, "uT")
            iBanner = GLES20.glGetUniformLocation(introProgram, "sBanner")
            iVideo = GLES20.glGetUniformLocation(introProgram, "sTexture")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            bannerTex = t[0]
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, banner, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        introArmed = true
        introStartNs = 0
        log("GlRelay: переход заглушка -> AA взведён (${banner.width}x${banner.height})")
    }

    /** 新的 Android Auto 会话：在第一个新帧到来前不再重复旧帧。 */
    fun clear() {
        haveFrame = false
    }

    /** 只有门控放行时才将帧送入编码器；否则帧保留在纹理中（updateTexImage 已完成）直到下次重绘。 */
    private fun drawGated(fresh: Boolean = false): Boolean {
        if (!gate.shouldEmit(SystemClock.elapsedRealtimeNanos())) {
            skipped = true
            if (fresh) gated.incrementAndGet()      // tick 对同一帧的多次拒绝不重复计数
            return false
        }
        skipped = false
        draw()
        return true
    }

    fun stop() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(tick)
                st?.release()
                // init() 可能在 inputSurface 之前失败：不做检查的话，异常会跳过下面 EGL 的释放
                if (::inputSurface.isInitialized) inputSurface.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (t: Throwable) {
                log("GlRelay.stop: ${t.message}")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(texId)
        s.setDefaultBufferSize(srcWidth, srcHeight)
        s.setOnFrameAvailableListener({ onFrame() }, handler)
        st = s
        inputSurface = Surface(s)
        android.opengl.Matrix.setIdentityM(tm, 0)
    }

    private fun onFrame() {
        if (released) return
        try {
            st?.updateTexImage()
            st?.getTransformMatrix(tm)
            haveFrame = true
            fresh.incrementAndGet()
            lastFreshNs = SystemClock.elapsedRealtimeNanos()
            drawGated(fresh = true)
        } catch (t: Throwable) {
            log("GlRelay.onFrame: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun draw() {
        GLES20.glViewport(0, 0, width, height)
        var pos = aPos
        var texAttr = aTex
        if (introArmed) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (introStartNs == 0L) introStartNs = now
            val t = (now - introStartNs) / (INTRO_MS * 1_000_000f)
            if (t >= 1f) {
                introArmed = false
                log("GlRelay: переход заглушка -> AA завершён")
            } else {
                GLES20.glUseProgram(introProgram)
                GLES20.glUniform1f(iT, t)
                GLES20.glUniform1i(iVideo, 0)
                GLES20.glUniform1i(iBanner, 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTex)
                GLES20.glUniformMatrix4fv(iMatrix, 1, false, tm, 0)
                pos = iPos
                texAttr = iTex
            }
        }
        if (!introArmed) {
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMatrix, 1, false, tm, 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texAttr)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        lastDrawNs = SystemClock.elapsedRealtimeNanos()
        // 给编码器的帧时间戳：单调时间，与 surface 源一致
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "шейдер: ${GLES20.glGetShaderInfoLog(sh)}" }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "линковка: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** 占位帧 → Android Auto 过渡的时长。更长的话，低 CBR 比特率下溶解会碎成块。 */
        private const val INTRO_MS = 700L

        // uT 0..1，e = ease-out cubic。Android Auto：缩放 1.08 → 1（裁剪坐标向中心收缩直至 uSTMatrix）。
        // 占位帧：缩放 1 → 1.35，透明度 1 → 0（略快于缩放，避免结束时残留薄雾效果）。
        // uT 对两个着色器通用：精度必须一致（GLSL ES），顶点着色器默认 highp，而
        // 片段着色器默认 mediump——Mali（Exynos 9611）在链接时会因此失败，Adreno 则容忍。两者都显式声明 mediump。
        private const val INTRO_VERTEX = """
            uniform mat4 uSTMatrix;
            uniform mediump float uT;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            varying vec2 vScreen;
            void main() {
                gl_Position = aPosition;
                float e = 1.0 - pow(1.0 - uT, 3.0);
                float z = mix(1.08, 1.0, e);
                vec2 c = vec2(0.5, 0.5);
                vec4 tc = vec4(c + (aTextureCoord.xy - c) / z, 0.0, 1.0);
                vTextureCoord = (uSTMatrix * tc).xy;
                vScreen = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
            }
        """
        private const val INTRO_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            varying vec2 vScreen;
            uniform samplerExternalOES sTexture;
            uniform sampler2D sBanner;
            uniform float uT;
            void main() {
                vec4 aa = texture2D(sTexture, vTextureCoord);
                float e = 1.0 - pow(1.0 - uT, 3.0);
                float s = mix(1.0, 1.35, e);
                vec4 b = texture2D(sBanner, (vScreen - 0.5) / s + 0.5);
                float a = 1.0 - smoothstep(0.0, 0.85, uT);
                gl_FragColor = vec4(mix(aa.rgb, b.rgb, a), 1.0);
            }
        """
        private const val VERTEX = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
