package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** AA 的视频去向:纯 Annex-B,不带 AAP 头。 */
interface AaVideoSink {
    /** CODEC_CONFIG: SPS+PPS。 */
    fun onCodecConfig(annexB: ByteArray)

    /** DATA:一帧(8 字节 AAP timestamp 已剥离),ptsUs 取自该 timestamp。 */
    fun onFrame(annexB: ByteArray, ptsUs: Long)

    /** 手机请求 VIDEO_FOCUS NATIVE(AA 中的「退出」磁贴)。AA 应答由调用方稍后发送(AaPeer.sendVideoFocusNative)。 */
    fun onAaNativeFocusRequest() {}
}

class AapOptions(
    val host: String = "127.0.0.1",
    val port: Int = 5277,
    /** 静默看门狗只到第一个 VIDEO MEDIA_START;之后会话可保持静默(静态画面,无 Ping)。 */
    val preVideoTimeoutMs: Long = 30_000,
    val maxUnackedVideo: Int = 12,         // AapControl.kt maxUnackedFor():12 用于套接字
    val maxUnackedAudio: Int = 30,
    val sd: AapSdOptions = AapSdOptions(),
    /** 若设置,则在手机发起请求时构建 SDR(桥:声音取决于车机的 FEATURE_CONFIG)。 */
    val sdProvider: (() -> AapSdOptions)? = null,
    val tls12Only: Boolean = false,
)

class AapStats {
    @Volatile var rxFrames = 0L
    @Volatile var txFrames = 0L
    @Volatile var videoCodecConfig = 0L
    @Volatile var videoData = 0L
    @Volatile var videoBytes = 0L
    @Volatile var audioData = 0L
}

/**
 * 单个 Android Auto head unit 会话:到 AA 中车机服务器的 TCP 客户端(127.0.0.1:5277)。
 * 流程 pc-headunit/aap/session.py:Version -> plain 帧内 TLS -> AuthComplete -> 加密通信 ->
 * ByeBye / shutdownOutput / 读到 EOF / close(5277 服务器一次性,脏断开会使其沉默)。
 *
 * 独立线程;sendTouch/requestStop 可从任意线程调用。log(line, ui):视频帧流时 ui=false。
 */
/** 桥接层对 AA 会话的要求。 */
/** AA 触摸的手指:pointer_id 与 AA 帧坐标。 */
data class TouchPoint(val id: Int, val x: Int, val y: Int)

interface AaPeer {
    /** 带全部活动手指的触摸;action_index 是触发 POINTER_DOWN/UP 的手指在 [points] 中的位置。 */
    fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>)

    /** 单个 id 为 0 的手指。 */
    fun sendTouch(action: Int, x: Int, y: Int) = sendTouch(action, 0, listOf(TouchPoint(0, x, y)))

    fun requestKeyFrame()

    /** 按键(Android KeyEvent keycode):先 down,再 up。默认忽略。 */
    fun sendKey(keycode: Int) {}

    /** 旋钮旋转 delta 格(> 0 顺时针)。默认忽略。 */
    fun sendRotary(delta: Int) {}

    /** 麦克风 PCM16 单声道 16 kHz -> 发送给手机,在 AA 麦克风会话打开期间。默认忽略。 */
    fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {}

    /** VideoFocusNotification NATIVE(unsolicited = false):车机已退出投屏。默认忽略。 */
    fun sendVideoFocusNative() {}

    /** AA 夜间模式(NIGHT 传感器):先记住,等手机启动传感器后发出。默认忽略。 */
    fun sendNightMode(night: Boolean) {}
}

class AapSession(
    private val creds: AapCredentials,
    private val sink: AaVideoSink,
    private val opts: AapOptions = AapOptions(),
    private val log: (String, Boolean) -> Unit = { _, _ -> },
    private val socketFactory: (() -> Socket)? = null,
    private val onEstablished: () -> Unit = {},
    private val onFirstVideo: () -> Unit = {},
    private val onEnd: (reason: String, ok: Boolean) -> Unit = { _, _ -> },
) : AaPeer {
    val stats = AapStats()

    @Volatile private var stopReason: String? = null
    @Volatile private var userStop = false
    @Volatile var exitOk = false
        private set
    @Volatile var established = false
        private set

    /** 已建立到车机服务器的 TCP(false 在会话结束时 = connection refused / 不可达)。 */
    @Volatile var connected = false
        private set

    /** 手机已打开视频通道(VIDEO MEDIA_START)。 */
    @Volatile var videoStarted = false
        private set
    private var establishedAtNs = 0L

    private val audioSink: AaAudioSink? = sink as? AaAudioSink
    private val micSink: AaMicSink? = sink as? AaMicSink
    private val metaSink: AaMetaSink? = sink as? AaMetaSink
    private val uplink = AaMicUplink({ sid, ts, pcm ->
        outQueue.offer(MsgJob(M.micData(ts, pcm), true, "mic sid=$sid ts=${ts}us"))
    })
    private var micSeq = 0
    @Volatile private var micSid = 0
    private var thread: Thread? = null
    private lateinit var sock: Socket
    private lateinit var tls: AapTls
    private val parser = F.Parser()
    private val reasm = F.Reassembler { log("AA reassembly: $it", true) }
    private val frames = ArrayDeque<F.Frame>()
    private val sessionIds = HashMap<Int, Int>()
    private val outQueue = LinkedBlockingQueue<Job>()
    private var writer: Thread? = null
    @Volatile private var writerActive = false
    @Volatile private var fatal = false
    private val clockBaseNs = System.nanoTime()
    private val readBuf = ByteArray(65536)

    @Volatile private var peerClosed = false
    private var byeSent = false
    private var byeAcked = false
    private var lastRx = System.nanoTime()
    private val firstVideo = AtomicLong(0)

    fun start() {
        val t = Thread({ run() }, "aa-session")
        thread = t
        t.start()
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    /** 正常结束:线程自行发送 ByeBye 并关闭套接字。 */
    fun requestStop(reason: String) {
        userStop = true
        stop(reason)
    }

    private fun stop(reason: String) {
        if (stopReason == null) stopReason = reason
    }

    /** action 0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP;坐标单位为 AA 帧像素。会话就绪前忽略。 */
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (!established || stopReason != null) {
            log("AA touch($action,$actionIndex,$points) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            // uint64 纳秒,严格 > 0 且单调递增;System.nanoTime() 本身可能是负数/任意值
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.touchEvent(action, actionIndex, points, ts), false, "touch ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки touch в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** 按键:先 down,再 up(Android KeyEvent keycode)。会话就绪前忽略。 */
    override fun sendKey(keycode: Int) {
        if (!established || stopReason != null) {
            log("AA key($keycode) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.keyEvent(ts, keycode, true), false, "key down $keycode ts=${ts}ns"))
            outQueue.offer(MsgJob(M.keyEvent(ts + 1_000_000L, keycode, false), false, "key up $keycode ts=${ts + 1_000_000L}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки key в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** 旋钮旋转:relative_event KEYCODE_ROTARY_CONTROLLER。会话就绪前忽略。 */
    override fun sendRotary(delta: Int) {
        if (!established || stopReason != null) {
            log("AA rotary($delta) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.rotaryEvent(ts, delta), false, "rotary $delta ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки rotary в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /**
     * 通过 VideoFocus NATIVE -> (暂停) -> PROJECTED unsolicited 循环向手机请求关键帧
     * (headunit-revived AapTransport.sendKeyframeCycleRelease + focusCycleGainRunnable)。
     */
    override fun requestKeyFrame() {
        if (!established || !videoStarted || stopReason != null || !writerActive) {
            log("AA keyframe-цикл пропущен: сессия ${if (!established) "не готова" else if (!videoStarted) "без видео" else "завершается"}", false)
            return
        }
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "keyframe cycle: NATIVE"))
        val t = Thread({
            try {
                Thread.sleep(KEYFRAME_CYCLE_GAP_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopReason == null && writerActive) {
                outQueue.offer(MsgJob(M.videoFocusNotification(projected = true, unsolicited = true), false, "keyframe cycle: PROJECTED"))
            }
        }, "aa-keyframe")
        t.isDaemon = true
        t.start()
    }

    /** 单个 VideoFocus NATIVE 消息(不回到 PROJECTED):车机在「退出」后离开了 CarLife。 */
    override fun sendVideoFocusNative() {
        if (!established || stopReason != null || !writerActive) {
            log("AA VideoFocus NATIVE пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA VideoFocus NATIVE (ГУ вышел из CarLife)", true)
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "exit: NATIVE"))
    }

    @Volatile private var nightMode = false
    @Volatile private var nightSensorStarted = false

    override fun sendNightMode(night: Boolean) {
        val changed = night != nightMode
        nightMode = night
        if (!nightSensorStarted) {
            if (changed) log("AA night=$night запомнила, сенсор NIGHT ещё не запущен телефоном", true)
            return
        }
        if (!established || stopReason != null || !writerActive) {
            log("AA night=$night пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA SENSOR night=$night (тема ГУ)", true)
        outQueue.offer(MsgJob(M.nightModeEvent(night), false, "night=$night"))
    }

    /** 麦克风 PCM:按 128 ms 切成帧并进入 maxUnacked 窗口;没有打开的麦克风会话则忽略。 */
    override fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {
        if (stopReason != null || !writerActive) return
        if (uplink.feed(pcm, capturedAtUs) == AaMicUplink.Feed.OVERFLOW) micAbort("телефон не подтверждает кадры микрофона (очередь переполнена)")
    }

    /** 由 HU 主动关闭麦克风会话:向手机发送错误,向音频源发送 close。 */
    private fun micAbort(why: String) {
        val sum = uplink.close() ?: return
        log("AA MIC прервана: $why; $sum", true)
        if (writerActive) outQueue.offer(MsgJob(M.micResponse(false, sum.sessionId), false, "mic abort"))
        micSink?.onMicClose(why)
    }

    private fun onMicRequest(payload: ByteArray) {
        val open = (firstVarint(payload, 1) ?: 0L) != 0L
        val window = (firstVarint(payload, 4) ?: 2L).toInt()
        val ms = micSink
        if (open) {
            if (ms == null) {
                send(M.micResponse(success = false))                    // 无采集:拒绝打开
                return
            }
            if (uplink.isOpen) {                                         // 重复 Open:同一会话
                send(M.micResponse(success = true, sessionId = micSid))
                return
            }
            if (window !in 1..4096) {
                log("AA MIC open: maxUnacked=$window вне 1..4096, отказ", true)
                send(M.micResponse(success = false))
                return
            }
            val sid = micSeq + 1
            uplink.open(sid, window)
            val accepted = try {
                ms.onMicOpen()
            } catch (e: Throwable) {
                log("AA MIC open: источник звука упал: ${e.javaClass.simpleName}: ${e.message}", true)
                false
            }
            if (!accepted) {
                uplink.close()
                log("AA MIC open отклонён (нет источника звука)", true)
                send(M.micResponse(success = false))                    // 同以前:以 session_id 0 拒绝
                return
            }
            micSeq = sid
            micSid = sid
            log("AA MIC open принят: session=$sid, окно maxUnacked=$window, кадры по ${AaMicFormat.CHUNK_BYTES} Б", true)
            send(M.micResponse(success = true, sessionId = sid))
            uplink.activate()                                            // 仅在应答入队后发送 DATA
        } else {
            val sid = micSid
            val sum = uplink.close()
            if (sum != null) {
                log("AA MIC close: $sum", true)
                ms?.onMicClose("AA закрыла микрофон")
            }
            send(M.micResponse(success = true, sessionId = sid))
        }
    }

    private fun onMicAck(payload: ByteArray) {
        val f = PbReader.parse(payload)
        val sid = f?.let { PbReader.int(it, 1) } ?: 0
        val stamps = f?.count { it.number == 3 } ?: 0
        val count = if (stamps > 0) stamps else (f?.let { PbReader.int(it, 2) } ?: 0)
        if (!uplink.ack(sid, count)) log("AA MIC ack проигнорирован: session=$sid count=$count", false)
    }

    // ---------------------------------------------------------------------------------------------
    private class SessionError(msg: String) : Exception(msg)
    private class PeerClosed(msg: String) : Exception(msg)

    private fun run() {
        try {
            try {
                sock = (socketFactory ?: {
                    Socket().also { it.connect(InetSocketAddress(opts.host, opts.port), 5000) }
                }).invoke()
            } catch (e: IOException) {
                // 车机服务器未监听:静默退出,不发 ByeBye 和总结,桥接层会自行重试
                log("AA сервер ${opts.host}:${opts.port} недоступен: ${e.javaClass.simpleName}: ${e.message}", false)
                stopReason = "$UNAVAILABLE_REASON${e.javaClass.simpleName}"
                return
            }
            connected = true
            sock.tcpNoDelay = true
            log("AA TCP подключено ${opts.host}:${opts.port}", true)
            tls = AapTls(creds, serverMode = false, tls12Only = opts.tls12Only)
            versionExchange()
            tlsHandshake()
            sendAuthComplete()
            startWriter()
            established = true
            establishedAtNs = System.nanoTime()
            lastRx = System.nanoTime()
            log("AA сессия установлена (${tls.describe()}), ждём телефон", true)
            onEstablished()
            serve()
        } catch (e: PeerClosed) {
            peerClosed = true
            if (stopReason == null) stopReason = "peer closed: ${e.message}"
            log("AA ${e.message}", true)
        } catch (e: SessionError) {
            if (stopReason == null) stopReason = e.message
            log("AA ${e.message}", true)
        } catch (e: javax.net.ssl.SSLException) {
            if (stopReason == null) stopReason = "TLS: ${e.message}"
            log("AA TLS ошибка: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: IOException) {
            peerClosed = true
            if (stopReason == null) stopReason = "ошибка сокета: ${e.javaClass.simpleName}: ${e.message}"
            log("AA ошибка сокета: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: Throwable) {
            if (stopReason == null) stopReason = "${e.javaClass.simpleName}: ${e.message}"
            log("AA сбой: ${e.javaClass.simpleName}: ${e.message}", true)
        } finally {
            if (!connected) {
                onEnd(stopReason ?: "не подключено", false)
                return
            }
            if (uplink.close() != null) micSink?.onMicClose("сессия AA закрыта")
            try {
                shutdown()
                if (userStop && byeAcked) exitOk = true
            } catch (e: Throwable) {
                log("AA сбой в shutdown: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val s = stats
            log(
                "AA итог: кадров rx=${s.rxFrames} tx=${s.txFrames}; видео config=${s.videoCodecConfig} data=${s.videoData} " +
                    "байт=${s.videoBytes}; аудио data=${s.audioData}; причина: $stopReason", true
            )
            onEnd(stopReason ?: "конец", exitOk)
        }
    }

    // --- 套接字 / 帧 ---
    private fun sendRaw(data: ByteArray) {
        stats.txFrames++
        sock.getOutputStream().write(data)
        sock.getOutputStream().flush()
    }

    private fun readMore(timeoutMs: Int) {
        sock.soTimeout = maxOf(timeoutMs, 1)
        val n = try {
            sock.getInputStream().read(readBuf)
        } catch (_: SocketTimeoutException) {
            return
        } catch (e: java.net.SocketException) {
            throw PeerClosed("соединение сброшено телефоном (${e.message})")
        }
        if (n < 0) throw PeerClosed("телефон закрыл соединение (EOF)")
        lastRx = System.nanoTime()
        for (fr in parser.feed(readBuf, 0, n)) {
            stats.rxFrames++
            frames.add(fr)
        }
    }

    private fun nextFrame(deadlineNs: Long): F.Frame? {
        while (frames.isEmpty()) {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) return null
            readMore(minOf(leftMs, POLL_MS.toLong()).toInt())
            stopReason?.let { throw SessionError("прервано во время handshake: $it") }
        }
        return frames.removeFirst()
    }

    // --- 出站队列 ---
    // handshake 之后,所有出站帧(消息、touch、TLS 层应答)都经过同一个队列和同一条线程:
    // wrap 和 write 严格原子,否则编号 N 的 TLS 记录可能在 N+1 之后发出(或根本不发出),
    // 手机会断开 TLS。handshake 之前由会话线程直接写入。
    private sealed interface Job
    private class MsgJob(val out: AapOut, val media: Boolean, val note: String) : Job
    private object TlsFlushJob : Job
    private class MarkerJob(val latch: CountDownLatch) : Job
    private object PoisonJob : Job

    private fun startWriter() {
        writerActive = true
        val t = Thread({ writerLoop() }, "aa-writer")
        t.isDaemon = true
        writer = t
        t.start()
    }

    private fun writerLoop() {
        while (true) {
            val job = outQueue.take()
            try {
                when (job) {
                    is PoisonJob -> return
                    is MarkerJob -> job.latch.countDown()
                    is TlsFlushJob -> {
                        val data = tls.drainOutgoing()
                        if (data.isNotEmpty()) {
                            log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
                            sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
                        }
                    }
                    is MsgJob -> {
                        logMsg("TX", job.out.chan, job.out.type, job.out.payload, job.media, job.note)
                        val plaintext = F.typed(job.out.type, job.out.payload)
                        for (fr in F.splitMessage(plaintext, job.out.chan, job.out.type)) {
                            val record = tls.encrypt(fr.chunk)
                            sendRaw(F.buildFrame(job.out.chan, fr.flags, record, fr.total))
                        }
                    }
                }
            } catch (e: Throwable) {
                // wrap 可能已成功而写入失败:TLS 流已失步,不能继续
                fatal = true
                peerClosed = true
                if (stopReason == null) stopReason = "сбой исходящей записи: ${e.javaClass.simpleName}: ${e.message}"
                log("AA СБОЙ ЗАПИСИ, TLS-поток рассинхронизирован, закрываю сессию: ${e.javaClass.simpleName}: ${e.message}", true)
                outQueue.clear()
                (job as? MarkerJob)?.latch?.countDown()
                writerActive = false
                return
            }
        }
    }

    /** 将消息放入出站队列(绝不在调用线程中写套接字)。 */
    private fun send(out: AapOut, media: Boolean = false, note: String = "") {
        if (!writerActive) {
            if (fatal) return                       // writer 已死:会话已在关闭
            throw IllegalStateException("writer не запущен")
        }
        outQueue.offer(MsgJob(out, media, note))
    }

    private fun flushTls() {
        if (tls.wantsWrap()) outQueue.offer(TlsFlushJob)
    }

    /** 等待 writer 发完所有积压数据(或退出)。 */
    private fun flushWriter(timeoutMs: Long) {
        if (!writerActive) return
        val latch = CountDownLatch(1)
        outQueue.offer(MarkerJob(latch))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun stopWriter() {
        val t = writer ?: return
        if (writerActive) outQueue.offer(PoisonJob)
        t.join(3000)
        writerActive = false
    }

    private fun sendSsl(data: ByteArray) {
        if (data.isEmpty()) return
        log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
    }

    private fun logMsg(dir: String, chan: Int, type: Int, payload: ByteArray, media: Boolean, note: String = "", text: String? = null) {
        val body = text ?: M.describe(chan, type, payload)
        log(
            "AA $dir ${M.channelName(chan)}($chan) ${M.messageName(chan, type)} 0x%04x len=${payload.size}".format(type) +
                (if (body.isNotEmpty()) " | $body" else "") + (if (note.isNotEmpty()) "  [$note]" else ""),
            !media
        )
    }

    // --- 握手 ---
    private fun versionExchange() {
        val body = M.versionRequestBody()
        val frame = F.buildFrame(F.CHANNEL_CONTROL, F.FLAGS_PLAIN_BULK, body)
        log("AA TX CONTROL(0) VERSION_REQUEST: ${frame.joinToString(" ") { "%02x".format(it) }}", true)
        sendRaw(frame)
        val deadline = System.nanoTime() + VERSION_TIMEOUT_MS * 1_000_000
        while (true) {
            val fr = nextFrame(deadline) ?: break
            if (fr.chan == 0 && !fr.encrypted && fr.body.size >= 2 && u16(fr.body, 0) == M.VERSION_RESPONSE) {
                val v = M.parseVersionResponse(fr.body.copyOfRange(2, fr.body.size))
                    ?: throw SessionError("VERSION_RESPONSE слишком короткий")
                val extra = if (v.options.isNotEmpty()) " options=" + v.options.joinToString(" ") { "%02x".format(it) } else ""
                log("AA RX CONTROL(0) VERSION_RESPONSE | телефон выбрал ${v.major}.${v.minor} (просили ${M.VERSION_MAJOR}.${M.VERSION_MINOR}) status=${v.status}$extra", true)
                if (v.status != 0) throw SessionError("VERSION_RESPONSE status ${v.status}, телефон отказал протоколу ${v.major}.${v.minor}")
                return
            }
            log("AA игнорирую кадр до version response: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
        }
        throw SessionError(
            "$NO_VERSION_RESPONSE_REASON. Если соединение открыто, но тишина: сервер ГУ завис на прошлой сессии, " +
                "перезапусти его в настройках разработчика Android Auto (или force-stop AA)."
        )
    }

    private fun tlsHandshake() {
        val deadline = System.nanoTime() + TLS_TIMEOUT_MS * 1_000_000
        sendSsl(tls.handshakeStep())
        while (!tls.handshakeDone) {
            val fr = nextFrame(deadline) ?: throw SessionError("TLS handshake: таймаут")
            if (fr.encrypted || fr.body.size < 2) {
                log("AA неожиданный кадр во время TLS handshake: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
                continue
            }
            val type = u16(fr.body, 0)
            when (type) {
                M.ENCAPSULATED_SSL -> {
                    log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} plain frame", false)
                    val out = try {
                        tls.handshakeStep(fr.body.copyOfRange(2, fr.body.size))
                    } catch (e: javax.net.ssl.SSLException) {
                        throw SessionError("TLS handshake не прошёл: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    sendSsl(out)
                }
                M.VERSION_RESPONSE -> log("AA отброшен запоздавший VERSION_RESPONSE", true)
                else -> log("AA неожиданное plain-сообщение во время TLS handshake: type=0x%04x".format(type), true)
            }
        }
    }

    private fun sendAuthComplete() {
        log("AA TX CONTROL(0) AUTH_COMPLETE | AuthResponse{status: 0}", true)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
    }

    // --- 主循环 ---
    private fun serve() {
        while (stopReason == null) {
            readMore(POLL_MS)
            while (frames.isNotEmpty() && stopReason == null) onFrame(frames.removeFirst())
            if (stopReason == null && !videoStarted && (System.nanoTime() - establishedAtNs) / 1_000_000 > opts.preVideoTimeoutMs) {
                stop("видео не началось за ${opts.preVideoTimeoutMs / 1000} с после AuthComplete")
            }
        }
    }

    private fun onFrame(fr: F.Frame) {
        if (!fr.encrypted) {
            onPlainFrame(fr)
            return
        }
        val d = try {
            tls.decrypt(fr.body, drain = false)
        } catch (e: javax.net.ssl.SSLException) {
            throw SessionError("TLS-ошибка от телефона: ${e.javaClass.simpleName}: ${e.message} (ch${fr.chan} flags=0x%02x)".format(fr.flags))
        }
        flushTls()
        if (tls.closed) throw PeerClosed("телефон прислал TLS close_notify")
        if (d.plaintext.isEmpty()) {
            log("AA RX ch${fr.chan} TLS-запись без данных приложения flags=0x%02x".format(fr.flags), false)
            return
        }
        val whole = reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) ?: return
        if (whole.size < 2) {
            log("AA ch${fr.chan}: сообщение короче поля type", true)
            return
        }
        dispatch(fr.chan, u16(whole, 0), whole.copyOfRange(2, whole.size))
    }

    private fun onPlainFrame(fr: F.Frame) {
        if (fr.body.size < 2) {
            log("AA plain-кадр без type: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
            return
        }
        val type = u16(fr.body, 0)
        if (type == M.ENCAPSULATED_SSL) {
            // handshake 之后的 TLS 记录(TLS 1.3 ticket)可能以 plain 帧到达;它们必须经过 TLS 层:
            // TLS 层会计数记录,跳过会破坏之后所有记录的解密。
            tls.decrypt(fr.body.copyOfRange(2, fr.body.size), drain = false)
            log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} post-handshake TLS (plain frame)", false)
            flushTls()
            return
        }
        log("AA RX ch${fr.chan} type=0x%04x неожиданный plain-кадр после handshake flags=0x%02x".format(type, fr.flags), true)
    }

    private fun dispatch(chan: Int, type: Int, payload: ByteArray) {
        val isMediaData = (chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS) && (type == M.MEDIA_DATA || type == M.MEDIA_CODEC_CONFIG)
        if (isMediaData) {
            onMediaData(chan, type, payload)
            return
        }
        // 麦克风 ack 每秒 8 次,播放器状态和导航距离每秒 1 次:写入文件,不进 UI
        logMsg("RX", chan, type, payload, media = (chan == M.CH_MIC && type == M.MEDIA_ACK) || M.isPeriodicMeta(chan, type))

        if (type == M.CHANNEL_OPEN_REQUEST) {
            send(M.channelOpenResponse(chan))
            if (chan == M.CH_SENSOR) send(M.drivingStatusEvent(0))          // UNRESTRICTED
            return
        }
        when {
            chan == M.CH_CONTROL -> onControl(type, payload)
            chan == M.CH_SENSOR -> onSensor(type, payload)
            chan == M.CH_INPUT -> if (type == M.INPUT_BINDING_REQUEST) send(M.bindingResponse(chan))
            chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS || chan == M.CH_MIC -> onMediaControl(chan, type, payload)
            // MEDIA_PLAYBACK / NAVIGATION:手机只发送状态,无需应答;元数据 -> 桥接层
            chan == M.CH_MEDIA_PLAYBACK || chan == M.CH_NAVIGATION -> AaMeta.dispatch(chan, type, payload, metaSink)?.let {
                log("AA META ${M.channelName(chan)} ${M.messageName(chan, type)}: $it", !M.isPeriodicMeta(chan, type))
            }
        }
    }

    private fun firstVarint(payload: ByteArray, field: Int): Long? =
        PbReader.parse(payload)?.firstOrNull { it.number == field && it.wire == 0 }?.num

    private fun onControl(type: Int, payload: ByteArray) {
        when (type) {
            M.SERVICE_DISCOVERY_REQUEST -> send(M.serviceDiscoveryResponse(opts.sdProvider?.invoke() ?: opts.sd))
            M.PING_REQUEST -> send(M.pingResponse(firstVarint(payload, 1) ?: 0L))
            M.NAV_FOCUS_REQUEST -> send(M.navFocusNotification())
            M.AUDIO_FOCUS_REQUEST -> M.audioFocusResponse((firstVarint(payload, 1) ?: 0L).toInt())?.let { send(it) }
            M.BYEBYE_REQUEST -> {
                send(M.byebyeResponse())
                Thread.sleep(500)                                  // AapControl.kt:378
                byeAcked = true
                stop("телефон прислал ByeByeRequest")
                exitOk = true
            }
            M.BYEBYE_RESPONSE -> {
                byeAcked = true
                stop("получен ByeByeResponse")
                exitOk = true
            }
            M.VOICE_SESSION_NOTIFICATION -> audioSink?.onVoiceSession((firstVarint(payload, 1) ?: 0L).toInt())
            // CHANNEL_CLOSE_NOTIFICATION 等:仅记录日志
        }
    }

    private fun onSensor(type: Int, payload: ByteArray) {
        if (type != M.SENSOR_START_REQUEST) return
        val sensorType = firstVarint(payload, 1)?.toInt()
        send(M.sensorStartResponse())
        if (sensorType == 10) {                                            // NIGHT
            nightSensorStarted = true                                      // 在 send 之前:此刻主题切换会随后发出
            send(M.nightModeEvent(nightMode))
            log("AA SENSOR NIGHT запущен, night=$nightMode", true)
        }
    }

    private fun onMediaControl(chan: Int, type: Int, payload: ByteArray) {
        when {
            type == M.MEDIA_SETUP -> {
                val unacked = if (chan == M.CH_VIDEO) opts.maxUnackedVideo else opts.maxUnackedAudio
                send(M.mediaConfig(chan, unacked))
                if (chan == M.CH_VIDEO) send(M.videoFocusNotification(projected = true, unsolicited = true))
                else if (chan in M.AUDIO_CHANNELS) send(M.audioFocusGainUnsolicited())
            }
            type == M.MEDIA_START -> {
                sessionIds[chan] = (firstVarint(payload, 1) ?: 0L).toInt()
                if (chan == M.CH_VIDEO) videoStarted = true
                if (chan in M.AUDIO_CHANNELS) audioSink?.onAudioStart(chan)
            }
            type == M.MEDIA_STOP && chan in M.AUDIO_CHANNELS -> audioSink?.onAudioStop(chan)
            type == M.MEDIA_VIDEO_FOCUS_REQUEST && chan == M.CH_VIDEO -> {
                val mode = PbReader.parse(payload)?.firstOrNull { it.number == 2 && it.wire == 0 }?.num?.toInt()
                if (mode == 1) send(M.videoFocusNotification(projected = true, unsolicited = false))
                else if (mode == 2) {
                    log("AA VIDEO_FOCUS_REQUEST NATIVE («Выйти»)", true)
                    sink.onAaNativeFocusRequest()      // 不回复:NATIVE 将在车机真正退出 CarLife 时发出
                }
            }
            type == M.MEDIA_MIC_REQUEST && chan == M.CH_MIC -> onMicRequest(payload)
            type == M.MEDIA_ACK && chan == M.CH_MIC -> onMicAck(payload)
            // 其余通道的 MEDIA_STOP、MEDIA_ACK、UPDATE_UI_CONFIG_*:仅记录日志
        }
    }

    private fun onMediaData(chan: Int, type: Int, payload: ByteArray) {
        val isData = type == M.MEDIA_DATA
        val body: ByteArray
        var ptsUs = 0L
        var note: String
        if (isData) {
            if (payload.size < 8) {
                log("AA ch$chan: DATA короче своего timestamp (${payload.size} Б)", true)
                return
            }
            for (i in 0 until 8) ptsUs = (ptsUs shl 8) or (payload[i].toLong() and 0xFF)
            body = payload.copyOfRange(8, payload.size)
            note = "ts=${ptsUs}us"
        } else {
            body = payload
            note = "codec config"
        }

        if (chan == M.CH_VIDEO) {
            stats.videoBytes += body.size
            if (isData) {
                stats.videoData++
                sink.onFrame(body, ptsUs)
                if (firstVideo.compareAndSet(0, System.nanoTime())) onFirstVideo()
            } else {
                stats.videoCodecConfig++
                sink.onCodecConfig(body)
            }
        } else if (isData) {
            stats.audioData++
            audioSink?.onAudioData(chan, body, ptsUs)
        }

        logMsg("RX", chan, type, body, media = true, note = note, text = "")
        // 视频同时确认 DATA 和 CODEC_CONFIG(AapTransport.kt dispatchVideo),音频只确认 DATA。
        if (chan == M.CH_VIDEO || isData) send(M.mediaAck(chan, sessionIds[chan] ?: 0), media = true)
    }

    // --- 结束 ---
    /**
     * 发送 ByeBye,等待应答,以服务器能看到干净结尾的方式关闭:ByeByeRequest(如果手机未自行结束),
     * 等待 ByeByeResponse 或 EOF 最多 2 秒,shutdownOutput,读到 EOF 最多 1.5 秒
     * (带未读数据关闭会发送 RST),最后才 close。
     */
    private fun shutdown() {
        log("AA завершаю: ${stopReason ?: "конец"}", true)
        if (!::sock.isInitialized) return
        try {
            if (::tls.isInitialized && tls.handshakeDone && !peerClosed && !byeAcked && !byeSent) {
                byeSent = true
                send(M.byebyeRequest())
                flushWriter(2000)
                Thread.sleep(150)                                  // AapTransport.kt:706
                waitBye(BYE_WAIT_MS)
            }
        } catch (e: Exception) {
            log("AA при отправке ByeBye: ${e.javaClass.simpleName}: ${e.message}", true)
        }
        stopWriter()                                               // 所有积压数据在 shutdownOutput 前发出
        try {
            try {
                sock.shutdownOutput()
            } catch (_: IOException) {
            }
            if (!peerClosed) drainUntilEof(DRAIN_WAIT_MS)
        } finally {
            try {
                sock.close()
            } catch (_: IOException) {
            }
            log("AA сокет закрыт", true)
        }
    }

    /** 读取(并应答手机的 bye)直到 ByeByeResponse、EOF 或超时。 */
    private fun waitBye(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (!byeAcked && System.nanoTime() < deadline) {
                readMore(minOf(POLL_MS.toLong(), maxOf((deadline - System.nanoTime()) / 1_000_000, 1)).toInt())
                while (frames.isNotEmpty()) {
                    val fr = frames.removeFirst()
                    if (!fr.encrypted) continue
                    val d = tls.decrypt(fr.body, drain = false)
                    flushTls()
                    val whole = if (d.plaintext.isNotEmpty()) reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) else null
                    if (whole == null || whole.size < 2) continue
                    val t = u16(whole, 0)
                    if (fr.chan == M.CH_CONTROL && (t == M.BYEBYE_RESPONSE || t == M.BYEBYE_REQUEST)) {
                        logMsg("RX", fr.chan, t, whole.copyOfRange(2, whole.size), false)
                        if (t == M.BYEBYE_REQUEST) send(M.byebyeResponse())
                        byeAcked = true
                    }
                }
            }
        } catch (_: PeerClosed) {
            peerClosed = true
            log("AA телефон закрыл соединение после ByeBye", true)
        }
        if (!byeAcked && !peerClosed) log("AA нет ByeByeResponse за ${timeoutMs / 1000.0} с, закрываю всё равно", true)
    }

    private fun drainUntilEof(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            sock.soTimeout = maxOf(((deadline - System.nanoTime()) / 1_000_000).toInt(), 1)
            try {
                if (sock.getInputStream().read(readBuf) < 0) return
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    companion object {
        /** 会话结束原因的开头,用于 TCP 未能连上车机服务器时(后面接异常名;ConnectException = 拒绝)。 */
        const val UNAVAILABLE_REASON = "сервер недоступен: "

        /** 会话结束原因的开头,用于 TCP 已连接但未收到 VERSION_RESPONSE。 */
        const val NO_VERSION_RESPONSE_REASON = "нет VERSION_RESPONSE"

        private const val VERSION_TIMEOUT_MS = 10_000L      // AapTransport.kt HANDSHAKE_TIMEOUT_MS
        private const val TLS_TIMEOUT_MS = 15_000L          // AapSslContext.kt SSL_HANDSHAKE_TIMEOUT_MS
        private const val POLL_MS = 100
        private const val BYE_WAIT_MS = 2_000L
        private const val DRAIN_WAIT_MS = 1_500L
        private const val KEYFRAME_CYCLE_GAP_MS = 500L
    }
}
