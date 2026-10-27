package com.yegorkapomidorka.headswap.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AA H.264 -> MediaCodec 解码器（输出到 Surface）-> AVC Baseline 编码器输入 Surface -> CarLife。
 * 通过 Surface 实现零拷贝。编码器从 start() 存活到 stop()（整个 CarLife 会话），并会在静态画面上
 * 每 100 毫秒自行重复最后一帧（KEY_REPEAT_PREVIOUS_FRAME_AFTER，同 MD Baidu：core/screen/video/Recorder.java:1364），
 * 因此车机不会没有帧。解码器在第一个 AA IDR 上惰性创建，并在新的 SPS 或 resetDecoder() 时重新创建。
 *
 * feedFrame/feedConfig/resetDecoder 不会阻塞调用线程（桥接器监视器下的 AA 线程）：它们被放入
 * [FeedQueue]，而 createDecoder、dequeueInputBuffer 和 queueInputBuffer 由 aa-trans-feed 线程执行。
 * 队列容量为 [FeedQueue.DEFAULT_MAX_FRAMES] 帧（30 fps 时约 0.5 秒：创建解码器期间 80-300 毫秒，帧会累积而不丢失）；
 * 溢出时帧会丢失直到下一个 IDR，桥接器收到 onNeedAaKeyFrame。
 * 回调 [out] 不会在 lock 下调用。tuning 中的 "nofeedthread" 是在调用者线程上的旧同步路径。
 */
class AaTranscoder(
    private val width: Int,
    private val height: Int,
    /** 初始目标帧率：编码器的 KEY_FRAME_RATE 和 GL 中继的闸门；之后可通过 [setTargetFps] 更改。 */
    private val fps: Int,
    private val bitrate: Int,
    private val out: TranscodeOutput,
    private val log: (String) -> Unit = { AppLog.i(it, false) },
    /** 实验选项："vbr"、"repeat=NNN"（微秒）、"norealtime"、"nolowlat"、"nobase"、"nofeedthread"。 */
    private val tuning: String = "",
    /** 输入 AA 帧的尺寸（给解码器的提示）以及从中裁剪的部分；默认输入 = 输出，无裁剪。 */
    private val srcWidth: Int = width,
    private val srcHeight: Int = height,
    private val crop: VideoCrop = VideoCrop.NONE,
) : VideoTranscoder {
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var relay: GlRelay? = null
    private var lastRedraws = 0L
    private var lastGated = 0L
    @Volatile private var target = fps
    @Volatile private var curBitrate = bitrate
    private var bitrateMode = -1                       // 配置编码器时使用的模式（-1 = 未设置/未知）
    @Volatile private var bitrateFailed = false
    private var encThread: Thread? = null
    @Volatile private var running = false
    private var encoderNote = ""
    var encoderName = "?"
        private set

    private val lock = Any()
    private var decoder: Dec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var decoderCfg: ByteArray? = null          // SPS+PPS，当前解码器就是用它们创建的
    private var cfgStale = false
    private var needIdr = true

    // 喂帧线程：所有解码器操作都从 AA 线程移到这里
    private val threaded = !tuning.contains("nofeedthread")
    private val queue = FeedQueue()
    private var feedThread: Thread? = null

    /** 在 lock 下处理帧的结果；回调 [out] 由调用方代码在退出 lock 后调用。 */
    private sealed class Outcome {
        object None : Outcome()
        object Skip : Outcome()                      // 帧被跳过，无需回调
        object NeedIdr : Outcome()
        class Error(val msg: String) : Outcome()
    }

    // 窗口统计
    private val inTimes = ConcurrentHashMap<Long, Long>()
    private val winIn = AtomicLong()
    private val winDecoded = AtomicLong()
    private val winOut = AtomicLong()
    private val winDrop = AtomicLong()
    private val winDecLatUs = AtomicLong()
    private val winEncLatUs = AtomicLong()
    private val winEncLatN = AtomicLong()
    private var winStartMs = SystemClock.elapsedRealtime()
    val totalIn = AtomicLong()
    val totalDecoded = AtomicLong()
    val totalOut = AtomicLong()

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var configured = false
        var lastErr: Exception? = null
        for (level in 0..2) {
            try {
                if (level > 0) c.reset()
                c.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderNote = when (level) {
                    0 -> "baseline+CBR+noB+repeat100ms+realtime"
                    1 -> "baseline+CBR+noB+repeat100ms"
                    else -> "минимальный формат+repeat100ms"
                }
                configured = true
                break
            } catch (e: Exception) {
                lastErr = e
                log("транскодер: configure уровня $level не прошёл: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!configured) {
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw lastErr ?: IllegalStateException("encoder configure")
        }
        var inSurface: Surface? = null
        try {
            inSurface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // 编码器和 Surface 尚未写入字段：stop() 不会看到它们，在此释放硬件编码器
            try {
                inSurface?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = inSurface
        encoder = c
        // 比特率模式：CBR/VBR 接受运行时设置 PARAMETER_KEY_VIDEO_BITRATE，CQ 不接受；级别 2 未设置模式
        bitrateMode = try {
            c.inputFormat.getInteger(MediaFormat.KEY_BITRATE_MODE)
        } catch (_: Exception) {
            -1
        }
        if (!tuning.contains("nogl")) {
            // 解码器 -> GL 中继 -> 编码器 Surface：中继在静态画面时重绘最后一帧（>= 10 fps）
            try {
                relay = GlRelay(surface!!, width, height, REDRAW_IDLE_MS, log, srcWidth, srcHeight, crop, target).also { it.start() }
            } catch (e: Exception) {
                log("транскодер: GL-реле не поднялось (${e.message}), декодер пишет прямо в энкодер")
                relay = null
            }
        }
        if (relay == null && !crop.isNone) log("транскодер: !!!!!! без GL-реле кроп невозможен, кадр AA будет сжат целиком")
        running = true
        encThread = Thread({ encodeLoop(c) }, "aa-trans-enc").also { it.start() }
        if (threaded) {
            queue.clear()
            feedThread = Thread({ feedLoop() }, "aa-trans-feed").also { it.start() }
        }
        log("транскодер: энкодер $encoderName ${width}x$height (вход AA ${srcWidth}x$srcHeight${if (crop.isNone) "" else ", кроп $crop"}) ${fps}fps ${bitrate / 1000}kbps ($encoderNote)")
    }

    override val targetFps: Int get() = target

    /** 运行时调整目标帧率：只调整 GL 中继的闸门，编解码器不会重建（KEY_FRAME_RATE 保持初始值）。 */
    override fun setTargetFps(fps: Int) {
        val old = target
        target = fps
        val r = relay
        if (r != null) r.setTargetFps(fps) else log("транскодер: без GL-реле частоту не ограничить, целевой fps $fps только в статистике")
        log("транскодер: целевой fps $old -> $fps")
    }

    override val currentBitrate: Int get() = curBitrate

    /** 通过 setParameters 运行时调整比特率（不重建）。CQ 或编解码器异常：记入日志一次，之后不再尝试。 */
    override fun setBitrate(bps: Int) {
        if (bitrateFailed || bps == curBitrate) return
        val c = encoder ?: return
        if (bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
            bitrateFailed = true
            log("транскодер: энкодер в BITRATE_MODE_CQ, битрейт на лету не меняется")
            return
        }
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            curBitrate = bps
        } catch (e: Exception) {
            bitrateFailed = true
            log("транскодер: setParameters(VIDEO_BITRATE=$bps) не прошёл, битрейт больше не меняю: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun format(level: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // 同 Baidu（Recorder.java:1364, MediaFormatUtils.java:48）：静态屏幕上每 100 毫秒重复一帧
        val repeat = Regex("""repeat=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLong() ?: REPEAT_AFTER_US
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeat)
        if (level <= 1 && !tuning.contains("nobase")) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // VBR：在 AA 徽标淡入地图时，CBR 下 c2.qti 会输出高达 129 KB 的 P 帧（约为 AA 源文件的 4 倍，0.3 秒内约 850 KB），
            // 车机会出现条纹；同一码流下 VBR 在相同画面时峰值保持在约 40 KB 以内（transcode_test，2026.10.12）
            f.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (tuning.contains("cbr")) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        if (level == 0) {
            if (!tuning.contains("norealtime")) f.setInteger(MediaFormat.KEY_PRIORITY, 0)   // realtime（实时）
            if (Build.VERSION.SDK_INT >= 30 && !tuning.contains("nolowlat")) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return f
    }

    // ---------------- 输入 AA ----------------

    /** 不阻塞：在线程模式下放入队列，SPS/PPS 解析由喂帧线程完成。 */
    override fun feedConfig(annexB: ByteArray) {
        if (threaded) queue.post(FeedItem.Config(annexB)) else processConfig(annexB)
    }

    private fun processConfig(annexB: ByteArray) {
        val (s, p) = H264Stream.spsPps(annexB)
        if (s == null || p == null) return
        synchronized(lock) {
            val changed = sps?.contentEquals(s) != true || pps?.contentEquals(p) != true
            sps = s
            pps = p
            if (changed && decoder != null) cfgStale = true     // 解码器将在下一个 IDR 时重建
        }
    }

    /**
     * 不阻塞（O(1)）：在线程模式下放入队列。队列已满 -> 帧丢失直到 IDR 到来，
     * 桥接器每个片段收到一次 onNeedAaKeyFrame。回调在调用者线程上调用，不在 lock 下。
     */
    override fun feedFrame(annexB: ByteArray, ptsUs: Long) {
        if (!running) return
        winIn.incrementAndGet()
        totalIn.incrementAndGet()
        val idr = 5 in AnnexB.nalTypes(annexB)
        val f = FeedItem.Frame(annexB, ptsUs, idr, SystemClock.elapsedRealtimeNanos())
        if (!threaded) {
            report(processFrame(f))
            return
        }
        when (queue.offerFrame(f)) {
            Offer.ACCEPTED -> {}
            Offer.DROPPED_FULL -> {
                winDrop.incrementAndGet()
                log("транскодер: очередь кормления полна (декодер не успевает), кадры до следующего IDR теряются")
                out.onNeedAaKeyFrame()
            }
            Offer.DROPPED_GATED -> winDrop.incrementAndGet()
        }
    }

    /** 根据处理结果调用回调；仅在 lock 之外。 */
    private fun report(o: Outcome) {
        when (o) {
            Outcome.NeedIdr -> out.onNeedAaKeyFrame()
            is Outcome.Error -> out.onTranscodeError(o.msg)
            Outcome.None, Outcome.Skip -> {}
        }
    }

    /** 用一帧喂给解码器（在 IDR 时创建它）。在 lock 下，不调用回调 [out] - 返回结果。 */
    private fun processFrame(f: FeedItem.Frame): Outcome = synchronized(lock) {
        val annexB = f.annexB
        val ptsUs = f.ptsUs
        val idr = f.idr
        if (decoder == null || (cfgStale && idr)) {
            if (!idr) {
                winDrop.incrementAndGet()
                return Outcome.Skip                  // 解码器需要 IDR 才能启动
            }
            val created = createDecoder(annexB)
            if (created !== Outcome.None) return created
        }
        if (needIdr && !idr) {
            winDrop.incrementAndGet()
            return Outcome.Skip
        }
        val d = decoder ?: return Outcome.Skip
        val idx = try {
            d.codec.dequeueInputBuffer(15_000)
        } catch (e: Exception) {
            return Outcome.Error("декодер: dequeueInputBuffer: ${e.message}")
        }
        if (idx < 0) {
            // 解码器来不及：帧丢失，后续 P 帧不可用 - 等待来自 AA 的 IDR
            winDrop.incrementAndGet()
            needIdr = true
            return Outcome.NeedIdr
        }
        val buf = d.codec.getInputBuffer(idx)
        if (buf == null || buf.capacity() < annexB.size) {
            d.codec.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            // 帧丢失，与没有输入缓冲区时一样：其后的 P 帧会损坏解码器 - 等待来自 AA 的 IDR
            winDrop.incrementAndGet()
            needIdr = true
            return Outcome.NeedIdr
        }
        buf.clear()
        buf.put(annexB)
        inTimes[ptsUs] = f.atNs                      // 从 AA 到达起算：在喂帧队列中的等待计入解码器延迟
        if (inTimes.size > 256) inTimes.keys.take(128).forEach { inTimes.remove(it) }
        try {
            d.codec.queueInputBuffer(idx, 0, annexB.size, ptsUs, 0)
            if (idr) needIdr = false
            Outcome.None
        } catch (e: Exception) {
            Outcome.Error("декодер: queueInputBuffer: ${e.message}")
        }
    }

    /** 喂帧线程：从队列中取出帧和命令，所有解码器操作都在此进行。 */
    private fun feedLoop() {
        try {
            while (true) {
                val item = queue.take(500) ?: if (running) continue else break
                if (item is FeedItem.Stop) break
                if (!running) continue               // 停止：不再碰解码器，通过 Stop 或超时退出
                try {
                    when (item) {
                        is FeedItem.Frame -> report(processFrame(item))
                        is FeedItem.Config -> processConfig(item.annexB)
                        FeedItem.Reset -> processReset()
                        FeedItem.Stop -> {}
                    }
                } catch (e: Exception) {
                    out.onTranscodeError("нить кормления: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } finally {
            // 任何退出路径（Stop、running=false 后的超时、未处理的异常）：解码器由拥有它的线程释放
            synchronized(lock) { releaseDecoderLocked() }
        }
    }

    /** 根据 SPS/PPS（来自 CODEC_CONFIG 或 IDR 本身）创建解码器。在 lock 下。None = 已创建。 */
    private fun createDecoder(idrFrame: ByteArray): Outcome {
        releaseDecoderLocked()
        var s = sps
        var p = pps
        if (s == null || p == null) {
            val (s2, p2) = H264Stream.spsPps(idrFrame)
            s = s2
            p = p2
        }
        if (s == null || p == null) {
            log("транскодер: нет SPS/PPS для декодера, жду")
            return Outcome.Skip
        }
        val surf = relay?.inputSurface ?: surface ?: return Outcome.Skip
        return try {
            val f = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            val c = MediaCodec.createDecoderByType(MIME)
            try {
                try {
                    c.configure(f, surf, null, 0)
                } catch (e: Exception) {
                    // 不带 low-latency/priority（并非所有解码器都接受）
                    c.reset()
                    val f2 = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
                    f2.setByteBuffer("csd-0", ByteBuffer.wrap(s))
                    f2.setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    c.configure(f2, surf, null, 0)
                }
                c.start()
            } catch (e: Exception) {
                // 解码器尚未写入 decoder 字段：releaseDecoderLocked() 不会释放它
                try {
                    c.release()
                } catch (_: Exception) {
                }
                throw e
            }
            val d = Dec(c)
            d.thread = Thread({ decodeLoop(d) }, "aa-trans-dec").also { it.start() }
            decoder = d
            decoderCfg = s + p
            cfgStale = false
            needIdr = true
            log("транскодер: декодер ${c.name} создан (SPS ${s.size} Б, PPS ${p.size} Б)")
            Outcome.None
        } catch (e: Exception) {
            Outcome.Error("декодер не создан: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private class Dec(val codec: MediaCodec) {
        @Volatile var alive = true
        var thread: Thread? = null
    }

    private fun decodeLoop(d: Dec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (d.alive && running) {
                val idx = d.codec.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    val render = info.size > 0
                    val inAt = inTimes.remove(info.presentationTimeUs)
                    d.codec.releaseOutputBuffer(idx, render)
                    if (render) {
                        winDecoded.incrementAndGet()
                        totalDecoded.incrementAndGet()
                        if (inAt != null) winDecLatUs.addAndGet((SystemClock.elapsedRealtimeNanos() - inAt) / 1000)
                    }
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("транскодер: формат вывода декодера ${d.codec.outputFormat}")
                }
            }
        } catch (e: Exception) {
            if (d.alive && running) out.onTranscodeError("цикл декодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 不阻塞：在线程模式下命令排在已接受帧之后，它们会在重置前被处理。 */
    override fun resetDecoder() {
        if (threaded) queue.post(FeedItem.Reset) else processReset()
    }

    private fun processReset() {
        synchronized(lock) {
            releaseDecoderLocked()
            relay?.clear()
            sps = null
            pps = null
            needIdr = true
        }
    }

    private fun releaseDecoderLocked() {
        val d = decoder ?: return
        decoder = null
        d.alive = false
        try {
            d.thread?.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            d.codec.stop()
        } catch (_: Exception) {
        }
        try {
            d.codec.release()
        } catch (_: Exception) {
        }
        inTimes.clear()
    }

    // ---------------- 编码器输出 ----------------

    private fun encodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(arr)
                        val data = AnnexB.normalize(arr)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            out.onEncoderConfig(data)
                        } else {
                            val lat = System.nanoTime() - info.presentationTimeUs * 1000
                            if (lat in 0..2_000_000_000L) {
                                winEncLatUs.addAndGet(lat / 1000)
                                winEncLatN.incrementAndGet()
                            }
                            winOut.incrementAndGet()
                            totalOut.incrementAndGet()
                            out.onEncoderFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (running) out.onTranscodeError("цикл энкодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun startIntro(banner: android.graphics.Bitmap) {
        relay?.startIntro(banner)
    }

    override fun requestSyncFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            log("транскодер: requestSyncFrame: ${e.message}")
        }
    }

    override fun statsLine(): String {
        val now = SystemClock.elapsedRealtime()
        val sec = maxOf((now - winStartMs) / 1000.0, 0.001)
        winStartMs = now
        val inN = winIn.getAndSet(0)
        val dec = winDecoded.getAndSet(0)
        val outN = winOut.getAndSet(0)
        val drop = winDrop.getAndSet(0)
        val decLat = winDecLatUs.getAndSet(0)
        val encLat = winEncLatUs.getAndSet(0)
        val encN = winEncLatN.getAndSet(0)
        val decMs = if (dec > 0) decLat / dec / 1000.0 else 0.0
        val encMs = if (encN > 0) encLat / encN / 1000.0 else 0.0
        val r = relay
        val redrawsNow = r?.redraws?.get() ?: 0L
        val repeats = if (r != null) redrawsNow - lastRedraws else maxOf(outN - dec, 0)
        lastRedraws = redrawsNow
        val gatedNow = r?.gated?.get() ?: 0L
        val gatedWin = gatedNow - lastGated
        lastGated = gatedNow
        val queueNote = if (threaded) ", очередь кормления макс ${queue.resetMaxPending()}" else ""
        return ("транскод за %.1f с: вход AA %d, декодировано %d, выход энкодера %d (%.1f fps), повторов (перерисовок GL) %d, задержано гейтом fps %d, потеряно %d; задержка декодер %.1f мс + энкодер %.1f мс = %.1f мс"
            .format(sec, inN, dec, outN, outN / sec, repeats, gatedWin, drop, decMs, encMs, decMs + encMs)) + queueNote
    }

    @Synchronized
    override fun stop() {
        if (!running && encoder == null) return
        running = false
        var decoderReleased = false
        val ft = feedThread
        feedThread = null
        if (ft != null) {
            queue.post(FeedItem.Stop)
            if (ft !== Thread.currentThread()) {
                try {
                    ft.join(1500)
                } catch (_: InterruptedException) {
                }
            }
            decoderReleased = !ft.isAlive
            if (ft.isAlive) log("транскодер: !!!!!! нить кормления не завершилась за 1,5 с, освобождаю декодер сама")
        }
        if (!decoderReleased) synchronized(lock) { releaseDecoderLocked() }
        try {
            relay?.stop()
        } catch (_: Exception) {
        }
        relay = null
        try {
            encThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        encoder = null
        surface = null
    }

    companion object {
        const val MIME = "video/avc"
        const val REPEAT_AFTER_US = 100_000L

        /** 如果这么长时间没有新帧，中继会重绘最后一帧（类似 Baidu 的 repeat-previous-frame-after）。 */
        const val REDRAW_IDLE_MS = 100L
    }
}
