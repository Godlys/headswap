package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.hardware.usb.UsbManager
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 一次车机服务器（127.0.0.1:5277）探测的结果。 */
enum class ProbeResult {
    /** 完整握手已通过，会话正常关闭。 */
    ALIVE,

    /** ECONNREFUSED：没有进程监听该端口。 */
    REFUSED,

    /** TCP 已接受，但 VERSION_RESPONSE 或 TLS 未到达（或端口不接受连接）。 */
    HUNG,

    /** 其他：没有证书、服务器立即关闭连接、TLS 被拒绝、套接字故障。 */
    ERROR,
}

/** 一次探测尝试。真实实现是 AaSessionProbe，在测试中会被替换。 */
fun interface ServerProbe {
    fun probeOnce(): ProbeResult
}

/**
 * 车机服务器存活探测：真实的 AA 会话（AapSession，与桥接器和 AaProbe 相同），而非裸 connect。
 * 不带握手的裸 connect/close 会挂起 5277 服务器，因此 TCP 之后探测会走完全程：
 * VERSION、TLS、AuthComplete、对 SERVICE_DISCOVERY_REQUEST 的响应，随即正常关闭（ByeBye、EOF、close）。
 * 不打开视频和通道：SDR 之后手机会发送通道请求，但会话已经停止。
 * 仅在会话完全关闭（onEnd）后才返回：否则桥接器就会连接到它上面。
 * 不会自动运行：即使短暂的探测也会干扰 Android Auto，汽车下次连接时会收到拒绝或挂起。
 */
class AaSessionProbe(
    private val loadCreds: () -> AapCredentials,
    private val sd: () -> AapSdOptions,
    private val log: (String, Boolean) -> Unit,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val sdrWaitMs: Long = SDR_WAIT_MS,
) : ServerProbe {
    private object NoSink : AaVideoSink {
        override fun onCodecConfig(annexB: ByteArray) {}
        override fun onFrame(annexB: ByteArray, ptsUs: Long) {}
    }

    /** 上次尝试如何结束（连接错误或会话结束原因）：用于 ERROR 时的 toast 提示。 */
    @Volatile var lastDetail: String = ""
        private set

    override fun probeOnce(): ProbeResult {
        lastDetail = ""
        val creds = try {
            loadCreds()
        } catch (e: Throwable) {
            lastDetail = "${e.javaClass.simpleName}: ${e.message}"
            log("SRVCHECK сертификаты не загрузились: $lastDetail", true)
            return ProbeResult.ERROR
        }
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val connectError = AtomicReference<IOException?>(null)
        val ref = AtomicReference<AapSession?>(null)
        val session = AapSession(
            creds, NoSink, AapOptions(host = host, port = port, sdProvider = sd),
            log = { line, _ ->
                // "AA " -> "aa "：LogDiag/logdiag.py 在整个字符串中按子串搜索桥接器标记（如「AA TCP 已连接」「AA 服务器...不可用」）
                log("SRVCHECK | " + (if (line.startsWith("AA ")) "aa " + line.substring(3) else line), false)
                // 手机询问车机能力：这是最后一步，接下来是正常关闭。SDR 的响应
                // 早于日志钩子（dispatch 在 onControl 之前记录日志），但在队列中它会排在 ByeBye 之前
                if ("RX CONTROL(0) SERVICE_DISCOVERY_REQUEST" in line) ref.get()?.requestStop("проба: SDR получен")
            },
            socketFactory = {
                Socket().also { s ->
                    try {
                        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    } catch (e: IOException) {
                        connectError.set(e)
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                        throw e
                    }
                }
            },
            onEnd = { reason, _ ->
                endReason.set(reason)
                ended.countDown()
            },
        )
        ref.set(session)
        session.start()
        // TCP 一旦被接受就不能中断：TLS 之前的 stop 会在没有 ByeBye 的情况下关闭套接字。会话自身受
        // 超时限制（version 10 秒、TLS 15 秒、bye 和 EOF 还有几秒），因此等待它结束。
        var establishedAtMs = 0L
        var interrupted = false
        var polls = 0
        while (true) {
            try {
                if (ended.await(50, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true      // 不丢弃线程：会话仍持有套接字
            }
            if (session.established) {
                val nowMs = System.nanoTime() / 1_000_000
                if (establishedAtMs == 0L) establishedAtMs = nowMs
                else if (nowMs - establishedAtMs > sdrWaitMs) session.requestStop("проба: SDR не пришёл за ${sdrWaitMs / 1000} с")
            }
            if (++polls > MAX_POLLS) {
                log("SRVCHECK сессия не закрылась за ${MAX_POLLS * 50 / 1000} с, выхожу не дожидаясь", true)
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val result = classify(connectError.get(), session.connected, session.established, endReason.get())
        lastDetail = connectError.get()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: endReason.get()
        log(
            "SRVCHECK сессия: connected=${session.connected} established=${session.established} закрытие чистое=${session.exitOk} " +
                "rx=${session.stats.rxFrames} tx=${session.stats.txFrames}, причина: ${endReason.get()}",
            true,
        )
        return result
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val SDR_WAIT_MS = 3000L
        private const val MAX_POLLS = 120 * 20      // 每 50 毫秒轮询一次，共 120 秒

        /** 根据会话进行到哪一步得出的结果。握手超时 = HUNG，其他一切异常 = ERROR。 */
        fun classify(connectError: Throwable?, connected: Boolean, established: Boolean, endReason: String): ProbeResult = when {
            !connected -> when {
                connectError is ConnectException && connectError.message?.contains("refused", ignoreCase = true) == true -> ProbeResult.REFUSED
                connectError is SocketTimeoutException -> ProbeResult.HUNG       // backlog 已满：端口不接受连接
                else -> ProbeResult.ERROR
            }
            established -> ProbeResult.ALIVE
            endReason.startsWith(AapSession.NO_VERSION_RESPONSE_REASON) || endReason.startsWith("TLS handshake: таймаут") -> ProbeResult.HUNG
            else -> ProbeResult.ERROR
        }
    }
}

/**
 * “开发者选项”中的手动探测：后台线程中尝试一次，无系列和重试。
 * 在桥接器活跃且 USB 已连接时不会启动（此时一次性 5277 服务器是它们需要的），也不会与另一次并行。
 * 到达 TCP 的尝试无法中断，因此桥接器在连接前会等待它结束（awaitIdle）。
 * 结果不影响主屏幕状态。
 */
class ManualServerCheck(
    private val probe: ServerProbe,
    private val busy: () -> Refusal?,
    private val log: (String) -> Unit,
) {
    /** 为什么没有启动探测。 */
    enum class Refusal { BRIDGE, USB, RUNNING }

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var running = false

    /** null = 探测已启动，onDone 将从工作线程调用（detail - 如果探测因异常失败，则为故障文本）；否则为拒绝原因。 */
    fun start(onDone: (ProbeResult, String?) -> Unit): Refusal? {
        lock.withLock {
            if (running) return Refusal.RUNNING
            busy()?.let { return it }
            running = true
        }
        val t = Thread({
            var result = ProbeResult.ERROR
            var detail: String? = null
            try {
                log("SRVCHECK ручная проба: старт")
                result = probe.probeOnce()
            } catch (e: Throwable) {
                detail = "${e.javaClass.simpleName}: ${e.message}"
                log("SRVCHECK сбой: $detail")
            } finally {
                lock.withLock {
                    running = false
                    idle.signalAll()
                }
            }
            log("SRVCHECK ИТОГ: $result")
            onDone(result, detail)
        }, "aa-srvcheck")
        t.isDaemon = true
        t.start()
        return null
    }

    /** 等待进行中的探测结束；false = 在 timeoutMs 内未结束。 */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        while (running && left > 0) {
            try {
                left = idle.awaitNanos(left)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        !running
    }
}

/**
 * 基于桥接器（AaConnector）真实连接的被动式车机服务器诊断，本身不访问服务器：
 * 它连续拒绝（ECONNREFUSED）了多久，以及连续多少次接受了 TCP 但没有发送 VERSION_RESPONSE。
 * 短暂拒绝（ByeBye 后约 6 秒，直到 AA 重新监听端口）是正常的：由主屏幕（HomeLogic）按阈值决定。
 * 序列属于连接器的一个周期：开始和结束时 reset()，旧拒绝不会进入新行程。
 */
class ServerFailures(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var refusedSince = NONE

    /** 连续接受 TCP 连接但没有 VERSION_RESPONSE 的次数。 */
    @Volatile var versionHangs = 0
        private set

    /** 进程运行期间至少建立过一次真正的 AA 会话：服务器工作过。不会重置。 */
    @Volatile var worked = false
        private set

    /** 服务器连续拒绝了多少毫秒（0 = 未拒绝）。 */
    val refusedForMs: Long get() = refusedSince.let { if (it == NONE) 0L else now() - it }

    fun onRefused() {
        if (refusedSince == NONE) refusedSince = now()
        versionHangs = 0
    }

    /** TCP 已接受：端口不再拒绝（接下来由 VERSION_RESPONSE 决定）。 */
    fun onTcpAccepted() {
        refusedSince = NONE
    }

    fun onNoVersionResponse() {
        refusedSince = NONE
        versionHangs++
    }

    /** 会话已建立：服务器工作正常，序列清零。 */
    fun onEstablished() {
        reset()
        worked = true
    }

    /**
     * 该请求用户开启服务器了（车机上的占位文本）：连续挂起、本进程中尚未工作过的服务器被拒绝，
     * 或曾工作过的服务器被拒绝超过 [START_HINT_REFUSED_MS]（如 HomeLogic.REFUSED_DOWN_MS）。
     */
    fun needsStart(): Boolean =
        versionHangs >= START_HINT_HANGS || (refusedSince != NONE && (!worked || refusedForMs >= START_HINT_REFUSED_MS))

    /** 任何其他结果（裸 EOF、TLS 等），连接器周期的开始和结束。 */
    fun reset() {
        refusedSince = NONE
        versionHangs = 0
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val START_HINT_REFUSED_MS = 15_000L
        const val START_HINT_HANGS = 2
    }
}

/** “开发者选项”中按钮（Actions.checkServer）的手动探测，以及在桥接器连接前等待其结束。 */
object AaServerCheck {
    @Volatile private var manual: ManualServerCheck? = null
    @Volatile private var probe: AaSessionProbe? = null

    /** 桥接器在连接前等待手动探测结束的时间。尝试本身约需 35 秒。 */
    private const val AWAIT_MS = 45_000L

    private fun checker(ctx: Context): ManualServerCheck {
        manual?.let { return it }
        synchronized(this) {
            manual?.let { return it }
            val app = ctx.applicationContext
            val usb = app.getSystemService(UsbManager::class.java)
            val p = AaSessionProbe(
                loadCreds = { AaCreds.load(app, AaPrefs(app).certSet).creds },
                sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false, carMake = AaPrefs(app).carMake) },
                log = { line, ui -> AppLog.i(line, ui) },
            )
            probe = p
            val c = ManualServerCheck(
                probe = p,
                busy = {
                    when {
                        CarLifeState.running -> ManualServerCheck.Refusal.BRIDGE
                        usb.accessoryList?.isNotEmpty() == true -> ManualServerCheck.Refusal.USB
                        else -> null
                    }
                },
                log = { AppLog.i(it) },
            )
            manual = c
            return c
        }
    }

    /** “开发者选项”中的按钮。null = 探测已开始，onDone 将从工作线程调用（detail - ERROR 时的原因）。 */
    fun start(ctx: Context, onDone: (ProbeResult, String?) -> Unit): ManualServerCheck.Refusal? =
        checker(ctx).start { result, err -> onDone(result, err ?: if (result == ProbeResult.ERROR) probe?.lastDetail else null) }

    /** AaConnector 每次连接到车机服务器前（连接器线程，非 main）：手动探测不应占用服务器。 */
    fun beforeBridgeConnect() {
        val c = manual ?: return
        if (!c.awaitIdle(AWAIT_MS)) AppLog.i("SRVCHECK проба не закончилась за $AWAIT_MS мс, подключаюсь всё равно")
    }
}
