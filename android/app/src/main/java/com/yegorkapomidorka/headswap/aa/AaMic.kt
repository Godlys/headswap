package com.yegorkapomidorka.headswap.aa

import java.util.ArrayDeque

/** Android Auto 麦克风的音频来源。 */
enum class MicMode(val key: String, val label: String) {
    /** 仅车机麦克风（通过 CarLife 请求录音）；无数据则继续等待。 */
    CAR("car", "машина"),

    /** 仅手机麦克风。 */
    PHONE("phone", "телефон"),

    /** 先用车机；若 HU_TIMEOUT_MS 内无数据 - 改用手机麦克风。 */
    AUTO("auto", "авто");

    companion object {
        fun parse(key: String?): MicMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** Android Auto 麦克风格式（headunit-revived 中的 MicCaptureFormat）：PCM 16 kHz 16 位单声道，每帧 2048 个采样。 */
object AaMicFormat {
    const val SAMPLE_RATE = 16000
    const val BITS = 16
    const val CHANNELS = 1
    const val BYTES_PER_SEC = SAMPLE_RATE * 2
    const val CHUNK_FRAMES = 2048
    const val CHUNK_BYTES = CHUNK_FRAMES * 2

    /** n 字节 PCM 的时长（微秒）。 */
    fun usForBytes(n: Int): Long = n * 1_000_000L / BYTES_PER_SEC

    /** MIC 通道上的 MEDIA_DATA 消息体：[timestamp u64 BE, 微秒][PCM]。 */
    fun dataPayload(tsUs: Long, pcm: ByteArray): ByteArray {
        val out = ByteArray(8 + pcm.size)
        for (i in 0 until 8) out[i] = (tsUs ushr (56 - 8 * i)).toByte()
        System.arraycopy(pcm, 0, out, 8, pcm.size)
        return out
    }
}

/** 手机向车机请求麦克风（MicrophoneRequest）。由桥接器核心实现。 */
interface AaMicSink {
    /** true = 已接受，PCM 将通过 AaPeer.sendMicPcm 发送。false = 拒绝（手机将收到错误）。 */
    fun onMicOpen(): Boolean

    fun onMicClose(why: String)
}

/** 手机上的音频源（AudioRecord）。 */
interface PhoneMic {
    /** 开始录音；sink 接收 PCM16 单声道 16 kHz。false = 未能启动（无权限等）。 */
    fun start(sink: (ByteArray) -> Unit): Boolean

    fun stop()
}

/**
 * 麦克风流向手机：将 PCM 切分为 4096 字节的帧（2048 个采样，恰好 128 毫秒，符合 Google 指南
 * 及 headunit-revived 的做法），窗口为 MicrophoneRequest 中的 maxUnacked，通过 Ack 确认。
 *
 * 发送帧不阻塞：emit 只是将消息放入 AAP 队列。activate() 之前帧会累积
 * （MicrophoneResponse 响应必须先于第一批 DATA 发出）。关闭时不足 4096 字节的尾部被丢弃
 * （不补零）。超出窗口的多余帧在队列中最多等待 maxPending，再超出则返回 OVERFLOW：
 * 不为卡死的手机堆积数据，会话由调用方关闭。
 */
class AaMicUplink(
    private val emit: (sessionId: Int, tsUs: Long, pcm: ByteArray) -> Unit,
    private val maxPending: Int = MAX_PENDING_CHUNKS,
) {
    enum class Feed { OK, CLOSED, OVERFLOW }

    class Summary(val sessionId: Int, val bytesIn: Long, val chunksSent: Long, val chunksAcked: Long, val discardedBytes: Long) {
        override fun toString() =
            "сессия $sessionId: принято ${bytesIn} Б, кадров отправлено $chunksSent, подтверждено $chunksAcked, отброшено ${discardedBytes} Б"
    }

    private class Chunk(val tsUs: Long, val pcm: ByteArray)

    private val lock = Any()
    private var open = false
    private var ready = false
    private var sid = 0
    private var window = 0
    private var inFlight = 0
    private val pending = ArrayDeque<Chunk>()
    private val partial = ByteArray(AaMicFormat.CHUNK_BYTES)
    private var fill = 0
    private var partialTs = 0L
    private var bytesIn = 0L
    private var sent = 0L
    private var acked = 0L
    private var discarded = 0L

    val isOpen: Boolean get() = synchronized(lock) { open }
    val sessionId: Int get() = synchronized(lock) { sid }

    fun open(sessionId: Int, window: Int) {
        require(window in 1..4096) { "maxUnacked $window вне 1..4096" }
        synchronized(lock) {
            check(!open) { "микрофонная сессия уже открыта" }
            open = true
            ready = false
            sid = sessionId
            this.window = window
            inFlight = 0
            pending.clear()
            fill = 0
            bytesIn = 0
            sent = 0
            acked = 0
            discarded = 0
        }
    }

    /** MicrophoneResponse（成功）已入队：可以发送数据了。 */
    fun activate() {
        synchronized(lock) {
            if (!open) return
            ready = true
            pump()
        }
    }

    /** pcm = PCM16 LE 单声道；capturedAtUs = 第一个字节的捕获时刻（微秒，手机单调时钟）。 */
    fun feed(pcm: ByteArray, capturedAtUs: Long): Feed {
        synchronized(lock) {
            if (!open) return Feed.CLOSED
            bytesIn += pcm.size
            var i = 0
            while (i < pcm.size) {
                if (fill == 0) partialTs = capturedAtUs + AaMicFormat.usForBytes(i)
                val take = minOf(partial.size - fill, pcm.size - i)
                System.arraycopy(pcm, i, partial, fill, take)
                fill += take
                i += take
                if (fill == partial.size) {
                    fill = 0
                    if (pending.size >= maxPending) {
                        discarded += partial.size + (pcm.size - i)
                        return Feed.OVERFLOW
                    }
                    pending.addLast(Chunk(partialTs, partial.copyOf()))
                    pump()
                }
            }
            return Feed.OK
        }
    }

    /** 手机的 Ack：sessionId 会话中已接收的 count 条消息。false = 不是我们的/多余的 ack。 */
    fun ack(sessionId: Int, count: Int): Boolean {
        synchronized(lock) {
            if (!open || sessionId != sid || count <= 0 || count > inFlight) return false
            inFlight -= count
            acked += count
            pump()
            return true
        }
    }

    /** 关闭会话；若从未打开则返回 null。未发出的数据被丢弃。 */
    fun close(): Summary? {
        synchronized(lock) {
            if (!open) return null
            open = false
            ready = false
            discarded += fill + pending.sumOf { it.pcm.size.toLong() }
            pending.clear()
            fill = 0
            return Summary(sid, bytesIn, sent, acked, discarded)
        }
    }

    private fun pump() {
        while (open && ready && inFlight < window && pending.isNotEmpty()) {
            val c = pending.removeFirst()
            inFlight++
            sent++
            emit(sid, c.tsUs, c.pcm)
        }
    }

    companion object {
        /** 4 帧 × 128 毫秒：卡住的手机使会话中断，而不是让延迟增长。 */
        const val MAX_PENDING_CHUNKS = 4
    }
}

/** 可取消的延迟任务（用于 fallback 定时器）。 */
fun interface MicTimerHandle {
    fun cancel()
}

/**
 * Android Auto 麦克风路由：谁提供声音、从哪里提供。
 *  - 来自 Android Auto 的 open：CAR/AUTO 模式向车机请求录音（CarLife MIC_RECORD_RECOG_START），PHONE 模式立即从手机录音；
 *  - 车机数据（VR 0x58001）送往 Android Auto；AUTO 模式下 huTimeoutMs 内无数据 - fallback 到手机麦克风；
 *  - Android Auto 的 close 或 CarLife 丢失：停止车机（MIC_RECORD_END）/手机上的录音。
 * 不依赖 Android：所有外部交互通过 lambda 传入。日志前缀：MIC。
 */
class AaMicRouter(
    private val log: (String, Boolean) -> Unit,
    private val mode: () -> MicMode,
    /** 存在存活的 CarLife 会话（START 之后），可以向车机请求录音。 */
    private val huLinkUp: () -> Boolean,
    /** 向车机请求录音：true = 开始，false = 停止。返回 false = 发送失败。 */
    private val huRecord: (start: Boolean) -> Boolean,
    private val phone: () -> PhoneMic?,
    /** PCM → 手机（AapSession.sendMicPcm）。 */
    private val toAa: (pcm: ByteArray, capturedAtUs: Long) -> Unit,
    private val schedule: (delayMs: Long, task: Runnable) -> MicTimerHandle,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
    private val huTimeoutMs: Long = HU_TIMEOUT_MS,
    /** 百度 MD（ReceiveDataThread.FILTER_PAKCAGE_NUM）会在录音开始后丢弃前 20 个 VR 包。 */
    private val huSkipPackets: Int = HU_SKIP_PACKETS,
) {
    enum class State { IDLE, HU_WAIT, HU, PHONE }

    private val lock = Any()
    private var state = State.IDLE
    private var openMode = MicMode.AUTO
    private var gen = 0
    private var timer: MicTimerHandle? = null
    private var openedAt = 0L
    private var huReqAt = 0L
    private var huBytes = 0L
    private var huPackets = 0L
    private var huSkipped = 0
    private var huIgnoredLogged = false
    private var huLastLogAt = 0L
    private var huLastLogBytes = 0L
    private var phoneBytes = 0L
    private var phoneStarted = false
    private var fellBack = false
    private var huSilentLogged = false

    val current: State get() = synchronized(lock) { state }
    val bytesFromHu: Long get() = synchronized(lock) { huBytes }
    val bytesFromPhone: Long get() = synchronized(lock) { phoneBytes }

    /** 手机打开了麦克风。false = 拒绝（CAR 模式下没有 CarLife，PHONE 模式下 AudioRecord 未启动）。 */
    fun open(): Boolean {
        synchronized(lock) {
            if (state != State.IDLE) {
                log("MIC open при уже открытом (${state}): закрываю прошлый", true)
                stopLocked("повторный open")
            }
            val m = mode()
            openMode = m
            gen++
            openedAt = nowMs()
            huBytes = 0
            huPackets = 0
            huSkipped = 0
            huIgnoredLogged = false
            huLastLogAt = 0
            huLastLogBytes = 0
            phoneBytes = 0
            phoneStarted = false
            fellBack = false
            huSilentLogged = false
            val link = huLinkUp()
            log("MIC open от AA: режим=${m.label}, CarLife ${if (link) "жив" else "не готов"}", true)
            when (m) {
                MicMode.PHONE -> return startPhoneLocked("режим «телефон»")
                MicMode.CAR -> {
                    if (!link) {
                        log("MIC отказ: режим «машина», а CarLife-сессии нет", true)
                        return false
                    }
                    if (!requestHuLocked()) {
                        log("MIC отказ: запрос записи к ГУ не ушёл", true)
                        return false
                    }
                    return true
                }
                MicMode.AUTO -> {
                    if (link && requestHuLocked()) return true
                    return startPhoneLocked("авто: ГУ недоступен")
                }
            }
        }
    }

    fun close(why: String) {
        synchronized(lock) {
            if (state == State.IDLE) return
            stopLocked(why)
        }
    }

    /** CarLife 会话已关闭（USB 断开）：车机录音丢失，AUTO 模式切换到手机。 */
    fun onCarlifeLost() {
        synchronized(lock) {
            if (state != State.HU_WAIT && state != State.HU) return
            log("MIC: CarLife закрыт во время записи с ГУ (получено $huBytes Б)", true)
            timer?.cancel()
            timer = null
            if (openMode == MicMode.AUTO) {
                fellBack = true
                state = State.IDLE
                startPhoneLocked("ГУ отключился")
            }
        }
    }

    /** 来自车机的 PCM（VR 0x58001，消息体）。 */
    fun onHuData(pcm: ByteArray) {
        val captured: Long
        synchronized(lock) {
            when (state) {
                State.HU_WAIT -> {
                    state = State.HU
                    timer?.cancel()
                    timer = null
                    huLastLogAt = nowMs()
                    log("MIC: ГУ прислал первые данные: ${pcm.size} Б спустя ${nowMs() - huReqAt} мс после запроса", true)
                }
                State.HU -> {}
                else -> {
                    if (!huIgnoredLogged) {
                        huIgnoredLogged = true
                        log("MIC: данные ГУ (${pcm.size} Б) при состоянии $state игнорирую", false)
                    }
                    return
                }
            }
            huPackets++
            if (huSkipped < huSkipPackets) {
                huSkipped++
                return
            }
            huBytes += pcm.size
            val t = nowMs()
            if (t - huLastLogAt >= 1000) {
                val dt = (t - huLastLogAt) / 1000.0
                log("MIC: с ГУ ${huBytes} Б (+%.0f Б/с, ждём ${AaMicFormat.BYTES_PER_SEC}), пакетов $huPackets, последний ${pcm.size} Б".format((huBytes - huLastLogBytes) / dt), true)
                huLastLogAt = t
                huLastLogBytes = huBytes
            }
            captured = nowUs() - AaMicFormat.usForBytes(pcm.size)
        }
        toAa(pcm, captured)
    }

    private fun onPhoneData(pcm: ByteArray) {
        val captured: Long
        synchronized(lock) {
            if (state != State.PHONE) return
            phoneBytes += pcm.size
            captured = nowUs() - AaMicFormat.usForBytes(pcm.size)
        }
        toAa(pcm, captured)
    }

    private fun requestHuLocked(): Boolean {
        if (!huRecord(true)) return false
        state = State.HU_WAIT
        huReqAt = nowMs()
        val g = gen
        log("MIC: запросила запись у ГУ (MIC_RECORD_RECOG_START), жду данные ${huTimeoutMs} мс", true)
        timer = schedule(huTimeoutMs, Runnable { onHuTimeout(g) })
        return true
    }

    private fun onHuTimeout(g: Int) {
        synchronized(lock) {
            if (g != gen || state != State.HU_WAIT) return
            if (openMode == MicMode.AUTO) {
                log("MIC: ГУ молчит ${huTimeoutMs} мс (данных 0 Б, пакетов $huPackets): fallback на микрофон телефона", true)
                huRecord(false)
                fellBack = true
                state = State.IDLE
                startPhoneLocked("fallback: ГУ не прислал данных")
            } else if (!huSilentLogged) {
                huSilentLogged = true
                log("MIC: ГУ молчит ${huTimeoutMs} мс, режим «машина»: fallback выключен, жду дальше", true)
            }
        }
    }

    private fun startPhoneLocked(reason: String): Boolean {
        val p = phone()
        if (p == null) {
            log("MIC: микрофон телефона недоступен ($reason)", true)
            return false
        }
        state = State.PHONE
        phoneStarted = true
        val ok = try {
            p.start { onPhoneData(it) }
        } catch (e: Exception) {
            log("MIC: микрофон телефона не запустился: ${e.javaClass.simpleName}: ${e.message}", true)
            false
        }
        if (!ok) {
            state = State.IDLE
            phoneStarted = false
            log("MIC: микрофон телефона не запустился ($reason)", true)
            return false
        }
        log("MIC: источник = микрофон телефона ($reason)", true)
        return true
    }

    private fun stopLocked(why: String) {
        val was = state
        timer?.cancel()
        timer = null
        gen++
        if (was == State.HU_WAIT || was == State.HU) {
            log("MIC: останавливаю запись на ГУ (MIC_RECORD_END)", true)
            huRecord(false)
        }
        if (phoneStarted) {
            try {
                phone()?.stop()
            } catch (_: Exception) {
            }
            phoneStarted = false
        }
        state = State.IDLE
        log(
            "MIC close ($why): был $was, с ГУ ${huBytes} Б ($huPackets пакетов), с телефона ${phoneBytes} Б, " +
                "длительность ${nowMs() - openedAt} мс${if (fellBack) ", был fallback" else ""}", true
        )
    }

    companion object {
        const val HU_TIMEOUT_MS = 2000L

        /** 0：不丢弃车机数据包。百度为 20，但 Honda 车机的包大小未知（按 4096 字节计约 2.5 秒语音）。 */
        const val HU_SKIP_PACKETS = 0
    }
}
