package com.yegorkapomidorka.headswap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface

/**
 * H.264 编码器（Surface 输入）和桥接占位画面的绘制器（AA 未连接时显示文本）。
 * 帧以 Annex-B 格式、带 4 字节起始码输出；codec config 单独通过 onConfig 回调给出。
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** 占位画面文本：大号文字和动态指示器，代替 AA 视频。 */
    @Volatile var banner: String? = null

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var s: Surface? = null
        try {
            var fmt = format(withExtras = true)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                AppLog.e("configure с profile/CBR не прошёл, пробую минимальный формат", e)
                c.reset()
                fmt = format(withExtras = false)
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            s = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec 还是 null：stop() 会立即返回，在这里释放硬件编码器
            try {
                s?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = s
        codec = c
        running = true
        drainThread = Thread({ drainLoop(c) }, "video-enc-drain").also { it.start() }
        renderThread = Thread({ renderLoop() }, "video-render").also { it.start() }
    }

    private fun format(withExtras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withExtras) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        return f
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.e("requestKeyFrame", e)
        }
    }

    /** 动态调整比特率（无线：比特率策略同样作用于占位画面）。false = 没有编解码器或参数未被接受。 */
    fun setBitrate(bps: Int): Boolean {
        val c = codec ?: return false
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: Exception) {
            AppLog.e("битрейт заглушки", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        renderThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(arr)
                            val data = AnnexB.normalize(arr)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                listener.onConfig(data)
                            } else {
                                listener.onFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        AppLog.i("энкодер: формат вывода $encoderName: ${c.outputFormat}", ui = false)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onError("drain энкодера", e)
        }
    }

    private fun renderLoop() {
        val s = surface ?: return
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (running) {
                // 软件 Canvas：在小米 vndk v30 上，对编码器 Surface 调用 lockHardwareCanvas 会弄崩 media.codec
                // （Redmi 9T/9S、POCO X3 Pro：FenceTime::Snapshot::getFlattenedSize 中 SIGSEGV，随后我们的进程 abort）
                val canvas = s.lockCanvas(null)
                try {
                    draw(canvas)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val d = next - SystemClock.elapsedRealtime()
                if (d > 0) Thread.sleep(d) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) listener.onError("рендер на Surface энкодера", e)
        }
    }

    /** 占位画面的当前帧转成 Bitmap：转码器从它开始淡入 AA 画面（缩放溶解）。任意线程可调用。 */
    fun snapshot(): Bitmap? {
        val msg = banner ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), width, height, msg, framesRendered, Paint(text), Paint(fill))
        return bmp
    }

    private fun drawBanner(c: Canvas, msg: String) = drawBanner(c, width, height, msg, framesRendered, text, fill)

    private fun drawBanner(c: Canvas, width: Int, height: Int, msg: String, n: Long, text: Paint, fill: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 纯色：在 CBR 4 Mbps 下，弱编码器的渐变会出现色带
        c.drawColor(BG)
        drawLogo(c, w / 2, h * 0.34f, minOf(h * 0.36f, w * 0.3f), n / fps.toFloat(), fill)

        // 文字标志：Head 白色，swap 琥珀色
        text.textAlign = Paint.Align.LEFT
        text.typeface = WORDMARK
        text.textSize = h * 0.085f
        val head = "Head"
        val swap = "swap"
        val wHead = text.measureText(head)
        var x = w / 2 - (wHead + text.measureText(swap)) / 2
        val yWord = h * 0.66f
        text.color = Color.WHITE
        c.drawText(head, x, yWord, text)
        x += wHead
        text.color = AMBER
        c.drawText(swap, x, yWord, text)

        // 桥接状态：可以看到正在等待什么（车机服务器、解锁、帧）
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.CENTER
        text.color = STATUS
        text.textSize = h * 0.065f
        val lh = text.textSize * 1.25f
        var y = h * 0.81f
        for (l in wrapBalanced(msg, w * 0.88f, text)) {
            c.drawText(l, w / 2, y, text)
            y += lh
        }
    }

    /**
     * 按单词换行，使行数最少但各行宽度大致相等：
     * 在 4:3 屏幕上「…请重启车机服务器」否则会把「车机」单独留在第二行。
     */
    private fun wrapBalanced(msg: String, maxW: Float, p: Paint): List<String> {
        val words = msg.split(' ')
        fun greedy(limit: Float): List<String> {
            val lines = ArrayList<String>()
            var cur = ""
            for (word in words) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > limit && cur.isNotEmpty()) {
                    lines.add(cur)
                    cur = word
                } else {
                    cur = t
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
        val base = greedy(maxW)
        if (base.size < 2) return base
        // 不断收窄限制直到行数不再增加：最后一行会从前面各行吸收单词
        var best = base
        var limit = p.measureText(msg) / base.size
        while (limit < maxW) {
            val l = greedy(limit)
            if (l.size == base.size) {
                best = l
                break
            }
            limit += maxW * 0.02f
        }
        return best
    }

    /**
     * 图标字形（ic_launcher_foreground.xml，48×48）：弧形箭头每隔 [SWAP_S] 通过围绕屏幕旋转 180° 交换位置，
     * 每次交换时从标志向外扩散一个琥珀色圆环。
     * 状态仅由 t 决定：snapshot() 从任意线程绘制出相同的帧。
     */
    private fun drawLogo(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, p: Paint) {
        val phase = t % SWAP_S
        val k = (phase / SPIN_S).coerceAtMost(1f)
        val ease = k * k * (3 - 2 * k)
        val angle = (t / SWAP_S).toInt() * 180f + ease * 180f

        val s = size / 48f
        c.save()
        c.translate(cx - 24f * s, cy - 24f * s)
        c.scale(s, s)

        // 交换波纹：圆环从弧的半径向外扩散，在循环结束时淡出
        val r = phase / SWAP_S
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = AMBER
        p.alpha = ((1 - r) * 110).toInt()
        c.drawCircle(24f, 24f, 15f + r * 9f, p)
        p.alpha = 255

        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.save()
        c.rotate(angle, 24f, 24f)
        drawArrow(c, ARC_WHITE, HEAD_WHITE, Color.WHITE, p)
        drawArrow(c, ARC_AMBER, HEAD_AMBER, AMBER, p)
        c.restore()

        // 中央屏幕保持不动
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = 2f
        c.drawRoundRect(16.5f, 19.25f, 31.5f, 29.25f, 2.2f, 2.2f, p)
        p.color = AMBER
        p.strokeWidth = 1.8f
        c.drawLine(19.6f, 25.4f, 28.4f, 25.4f, p)
        c.restore()
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL
    }

    private fun drawArrow(c: Canvas, arc: Path, head: Path, color: Int, p: Paint) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.2f
        c.drawPath(arc, p)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = 1f
        c.drawPath(head, p)
    }

    private fun draw(c: Canvas) {
        drawBanner(c, banner ?: "")
    }

    companion object {
        const val MIME = "video/avc"

        private val BG = Color.rgb(0x0E, 0x14, 0x1A) // 图标背景
        private val AMBER = Color.rgb(0xF2, 0xB3, 0x3D)
        private val STATUS = Color.rgb(0xB8, 0xC4, 0xCC)
        private val WORDMARK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

        /** 箭头交换的周期及其中旋转本身的持续时间，单位秒。 */
        private const val SWAP_S = 2.2f
        private const val SPIN_S = 0.8f

        // 弧线 — 围绕 (24,24) 半径 r=15 的圆：195°..318° 及其镜像 15°..138°
        private val ARC_WHITE = Path().apply { addArc(RectF(9f, 9f, 39f, 39f), 195f, 123f) }
        private val ARC_AMBER = Path().apply { addArc(RectF(9f, 9f, 39f, 39f), 15f, 123f) }
        private val HEAD_WHITE = triangle(39.24f, 10.28f, 40.5f, 19.9f, 31.06f, 17.64f)
        private val HEAD_AMBER = triangle(8.76f, 37.72f, 7.5f, 28.1f, 16.94f, 30.36f)

        private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x3, y3)
            close()
        }
    }
}