package com.yegorkapomidorka.headswap.link

import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.InnerAssembler
import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.Wire
import com.yegorkapomidorka.headswap.aa.DrainMeter
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 无线 CarLife 的端口（Baidu MD 8.6.7，CommonParams）：车机作为 TCP 客户端连接手机。 */
object Tcp6Ports {
    val DEFAULT: Map<Int, Int> = linkedMapOf(
        Ch.CMD to 7240, Ch.VIDEO to 8240, Ch.MEDIA to 9240, Ch.TTS to 9241, Ch.VR to 9242, Ch.TOUCH to 9340,
    )

    /** Data 通道：接受并忽略，不计入 6/6 就绪。 */
    const val DATA = 9440
}

class BadInnerHeader(msg: String) : IOException(msg)

/**
 * 来自单通道 socket 的 inner 消息，无外部头：头部为 8 字节（CMD、TOUCH：长度 u16）
 * 或 12 字节（VIDEO、MEDIA、TTS、VR：长度 u32），随后按 declared len 读取正文（同 Baidu 的 ConnectSocket.readData）。
 * 对类型没有弹性：非法的头 = 带原因断开（计划中的 U5，按 H4 解决）。
 */
object InnerCodec {
    fun hdrLen(chan: Int) = if (Ch.isCmdStyle(chan)) 8 else 12

    /** 下一条完整的 inner；null = 在消息边界处干净的 EOF。EOFException = 消息中途断开。 */
    fun read(chan: Int, din: DataInputStream): ByteArray? {
        val hl = hdrLen(chan)
        val hdr = ByteArray(hl)
        val first = din.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        din.readFully(hdr, 1, hl - 1)
        val cmd = Ch.isCmdStyle(chan)
        val declared = if (cmd) Wire.u16(hdr, 0) else Wire.u32(hdr, 0)
        val type = Wire.u32(hdr, if (cmd) 4 else 8)
        val limit = if (cmd) InnerAssembler.MAX_CMD_DECLARED else Wire.MAX_OUTER_LEN
        if (declared !in 0..limit) throw BadInnerHeader("длина $declared вне 0..$limit (тип ${Msg.hex(type)}, заголовок ${hexBytes(hdr)})")
        // VW CNS3（Tiguan L Pro，h129）以 CMD 帧向 CMD socket 发送 VIDEO_HEARTBEAT：8 字节，长度 0
        val cmdHb = chan == Ch.CMD && type == Msg.VIDEO_HEARTBEAT && declared == 0
        if (!cmdHb && type ushr 16 != chan) {
            throw BadInnerHeader("тип ${Msg.hex(type)} не канала ${Ch.name(chan)}, длина $declared, заголовок ${hexBytes(hdr)}")
        }
        val inner = ByteArray(hl + declared)
        System.arraycopy(hdr, 0, inner, 0, hl)
        din.readFully(inner, hl, declared)
        if (cmdHb) skipHeartbeatTail(din)
        return inner
    }

    /**
     * CMD socket 中的 heartbeat 也可能以 12 字节视频头 [长度 u32][类型 u32][标记 u32] 到来：那么读完 8 字节后
     * 还挂着 4 字节标记。只有 12 字节已在缓冲区时才向前看（不阻塞）：下一个 CMD 类型头在 4 字节之后而非紧接，
     * 说明有尾巴，跳过它。
     */
    private fun skipHeartbeatTail(din: DataInputStream) {
        if (!din.markSupported() || din.available() < 12) return
        val p = ByteArray(12)
        din.mark(12)
        din.readFully(p)
        din.reset()
        fun cmdType(t: Int) = t ushr 16 == Ch.CMD || t == Msg.VIDEO_HEARTBEAT
        if (!cmdType(Wire.u32(p, 4)) && cmdType(Wire.u32(p, 8))) {
            din.skipBytes(4)
            WLog.ev(WLog.TCP, "hb-cmd-tail", "skipped" to 4)
        }
    }

    private fun hexBytes(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

class LinkSetupException(msg: String) : Exception(msg)

/**
 * 无线 CarLife 的服务端：在同一地址上监听 6 个通道端口（+ Data 9440）并等待车机。
 * 接收规则：端口上的第一个连接获胜，同一端口的重复连接被关闭（Baidu 相反，最后一个获胜）；
 * 所有 socket 必须来自第一个被接受者的地址。监听器存活到传输关闭为止，以挡掉多余连接。
 */
class Tcp6Listener(
    private val bind: InetAddress,
    private val ports: Map<Int, Int> = Tcp6Ports.DEFAULT,
    private val dataPort: Int? = Tcp6Ports.DATA,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** 绑定 0.0.0.0 时放行谁：B 分支只放行信标所发往子网中的地址。 */
    private val peerOk: (InetAddress) -> Boolean = { true },
) {
    private val lock = Object()
    private val servers = LinkedHashMap<Int, ServerSocket>()   // 通道 -> 服务器；DATA_CHAN = Data 端口
    private val accepted = LinkedHashMap<Int, Socket>()
    private var dataSocket: Socket? = null
    private var peer: InetAddress? = null
    private var closed = false

    /** 打开所有端口。六个中任何一个未打开：抛出带原因的异常，此时一切已关闭。 */
    fun open(): Tcp6Listener {
        val all = LinkedHashMap(ports)
        if (dataPort != null) all[DATA_CHAN] = dataPort
        try {
            for ((chan, port) in all) {
                val s = ServerSocket()
                s.reuseAddress = true
                try {
                    s.bind(InetSocketAddress(bind, port), 4)
                } catch (e: IOException) {
                    s.close()
                    WLog.ev(WLog.TCP, "bind-fail", "chan" to chanName(chan), "port" to port, "err" to e.message)
                    if (chan == DATA_CHAN) continue   // 没有 Data 也能活
                    throw LinkSetupException("tcp6: порт ${chanName(chan)} $port не открылся: ${e.message}")
                }
                synchronized(lock) { servers[chan] = s }
                WLog.ev(WLog.TCP, "bind", "chan" to chanName(chan), "port" to s.localPort, "addr" to bind.hostAddress)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        for ((chan, s) in synchronized(lock) { servers.toMap() }) {
            Thread({ acceptLoop(chan, s) }, "tcp6-accept-${chanName(chan)}").apply { isDaemon = true }.start()
        }
        return this
    }

    /** 第一个被接受的对端的地址（6/6 之后为车机）；在首次连接之前为 null。 */
    val peerAddress: InetAddress? get() = synchronized(lock) { peer }

    /** 通道的实际端口（测试中端口为 0）。 */
    fun boundPort(chan: Int): Int = synchronized(lock) { servers[chan]?.localPort ?: -1 }

    /** 等待全部 6 个 socket。未等到：全部关闭，抛出带原因 k/6 及缺失列表的异常。 */
    fun await(timeoutMs: Long, label: String): MultiSocketTransport {
        val start = clock()
        synchronized(lock) {
            while (!closed && accepted.size < ports.size) {
                val left = timeoutMs - (clock() - start)
                if (left <= 0) break
                lock.wait(minOf(left, 250L))
            }
            if (closed) {
                dropAccepted()
                throw LinkSetupException("tcp6: слушатель закрыт до 6/6 (${accepted.size}/${ports.size})")
            }
            if (accepted.size < ports.size) {
                val missing = ports.filterKeys { it !in accepted }.map { (c, p) -> "${Ch.name(c)}($p)" }
                val k = accepted.size
                WLog.ev(WLog.TCP, "timeout", "ready" to "$k/${ports.size}", "waitedMs" to clock() - start, "missing" to missing.joinToString(","))
                dropAccepted()
                closeLocked()
                throw LinkSetupException("tcp6: за ${timeoutMs / 1000} с подключилось $k/${ports.size}, нет ${missing.joinToString(", ")}")
            }
            WLog.ev(WLog.TCP, "ready", "ready" to "${ports.size}/${ports.size}", "peer" to peer?.hostAddress, "ms" to clock() - start)
            return MultiSocketTransport(LinkedHashMap(accepted), ports, "$label, ГУ ${peer?.hostAddress}", { close() }, clock)
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        for (s in servers.values) quiet { s.close() }
        // 已接受但未移交给传输层的 socket 在这里关闭；已移交的由传输层自行关闭
        dataSocket?.let { quiet { it.close() } }
        lock.notifyAll()
    }

    /** 同时关闭通道 socket（建立未成功）。 */
    private fun dropAccepted() {
        for (s in accepted.values) quiet { s.close() }
    }

    private fun acceptLoop(chan: Int, server: ServerSocket) {
        while (true) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return   // 服务器已关闭
            }
            onAccept(chan, s)
        }
    }

    private fun onAccept(chan: Int, s: Socket) {
        val addr = s.inetAddress
        val port = s.localPort
        synchronized(lock) {
            val reject = when {
                closed -> "closed"
                chan == DATA_CHAN && dataSocket != null -> "dup"
                chan != DATA_CHAN && accepted.containsKey(chan) -> "dup"
                peer != null && addr != peer -> "peer"
                peer == null && !peerOk(addr) -> "subnet"
                else -> null
            }
            if (reject != null) {
                WLog.ev(WLog.TCP, "reject", "chan" to chanName(chan), "port" to port, "peer" to addr.hostAddress, "why" to reject)
                quiet { s.close() }
                return
            }
            if (peer == null) peer = addr
            setOptions(s)
            if (chan == Ch.VIDEO) {
                // 较小的发送缓冲区：否则队列藏在内核里（多达 MiB），写入不阻塞、排空无法测量，
                // 无线环境下的码率策略会失明（VW 15.10 日志：排空 6-19 MiB/s，码率从未变动）
                s.sendBufferSize = VIDEO_SNDBUF
                WLog.ev(WLog.TCP, "sndbuf", "chan" to "VIDEO", "asked" to VIDEO_SNDBUF, "got" to s.sendBufferSize)
            }
            if (chan == DATA_CHAN) {
                dataSocket = s
                WLog.ev(WLog.TCP, "accept", "chan" to "DATA", "port" to port, "peer" to addr.hostAddress)
                Thread({ drainData(s) }, "tcp6-data").apply { isDaemon = true }.start()
                return
            }
            accepted[chan] = s
            WLog.ev(WLog.TCP, "accept", "chan" to chanName(chan), "port" to port, "peer" to addr.hostAddress, "ready" to "${accepted.size}/${ports.size}")
            if (accepted.size == ports.size) lock.notifyAll()
        }
    }

    /** 我们不需要 Baidu 的 Data 通道（9440）：将其读出，以免车机的接收缓冲区被塞满。 */
    private fun drainData(s: Socket) {
        val buf = ByteArray(4096)
        var total = 0L
        try {
            val inp = s.getInputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
        WLog.ev(WLog.TCP, "data-closed", "rx" to total)
    }

    /** 在 6/6 之前取消建立（服务已停止）：关闭一切，包括已接受的 socket。 */
    fun abort() {
        synchronized(lock) {
            dropAccepted()
            closeLocked()
        }
    }

    private fun chanName(chan: Int) = if (chan == DATA_CHAN) "DATA" else Ch.name(chan)

    companion object {
        private const val DATA_CHAN = 0

        /** 视频 socket 的 SO_SNDBUF（内核会加倍）：4 Mbit/s 时约 250 ms。 */
        const val VIDEO_SNDBUF = 64 * 1024

        fun setOptions(s: Socket) {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.setSoLinger(true, 0)
        }

        internal inline fun quiet(f: () -> Unit) {
            try {
                f()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * 六个 Baidu 格式的 TCP socket：每个通道一个 socket，无外部头。每个通道有一个读线程和一个写线程。
 * 车机可能向 video socket 发送 VIDEO_HEARTBEAT（0x20002，12 字节），VW CNS3 将其发到 CMD socket（8 字节）：
 * 我们读出并计数，不送入协议。
 * 任一 socket 断开都会关闭所有（恰好一次 onClosed，原因中含通道名）。
 */
class MultiSocketTransport internal constructor(
    private val sockets: Map<Int, Socket>,
    private val ports: Map<Int, Int>,
    override val label: String,
    private val closeListener: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : CarTransport {

    override val caps = TransportCaps.TCP6

    private val queues = sockets.keys.associateWith { LinkedBlockingQueue<ByteArray>() }
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val drain = DrainMeter()
    private val closed = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()
    private val dispatchLock = Any()
    private val txLock = Any()
    private val rx = AtomicLong()
    private val t0 = clock()
    @Volatile private var onClosedCb: ((String) -> Unit)? = null

    // 车机在 video socket 上的 heartbeat（VW CNS3 将其发到 CMD socket，此时还要统计 hbCmd）
    @Volatile private var hbCount = 0L
    @Volatile private var hbCmd = 0L
    private var hbFirstMs = 0L
    private var hbLastMs = 0L
    @Volatile private var hbMaxGapMs = 0L

    override val rxBytes: Long get() = rx.get()
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val drainBytesPerSec: Double get() = drain.bytesPerSec
    override fun queueSize(chan: Int): Int = queues[chan]?.size ?: 0

    val heartbeats: Long get() = hbCount

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        onClosedCb = onClosed
        for ((chan, s) in sockets) {
            val n = Ch.name(chan)
            threads += Thread({ writeLoop(chan, s, onWritten) }, "cl-w-$n")
            threads += Thread({ readLoop(chan, s, sink) }, "cl-r-$n")
        }
        for (t in threads) t.start()
        WLog.ev(WLog.TCP, "start", "sockets" to sockets.size)
    }

    override fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        val q = queues[chan] ?: return
        if (chan == Ch.VIDEO) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        q.put(inner)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        WLog.ev(WLog.TCP, "close", "by" to "session", *endStats())
        closeAll()
    }

    private fun fail(chan: Int, why: String) {
        if (!closed.compareAndSet(false, true)) return
        val reason = "tcp6 ${Ch.name(chan)}(${ports[chan]}): $why"
        WLog.ev(WLog.TCP, "close", "by" to "link", "chan" to Ch.name(chan), "why" to why, *endStats())
        closeAll()
        onClosedCb?.invoke(reason)
    }

    private fun endStats(): Array<Pair<String, Any?>> = arrayOf(
        "aliveMs" to clock() - t0, "rx" to rx.get(), "hb" to hbCount, "hbCmd" to hbCmd, "hbMaxGapMs" to hbMaxGapMs,
    )

    private fun closeAll() {
        Tcp6Listener.quiet { closeListener() }
        for (s in sockets.values) Tcp6Listener.quiet { s.close() }
        // 不中断自己的线程：fail() 之后整个 CarLifeSession.close() 会在该线程上执行（停止视频、onClosed）
        val me = Thread.currentThread()
        for (t in threads) if (t !== me) t.interrupt()
    }

    private fun readLoop(chan: Int, s: Socket, sink: (Int, ByteArray) -> Unit) {
        try {
            val din = DataInputStream(BufferedInputStream(s.getInputStream(), READ_BUF))
            while (!closed.get()) {
                val inner = InnerCodec.read(chan, din)
                if (inner == null) {
                    fail(chan, "EOF (ГУ закрыла сокет)")
                    return
                }
                rx.addAndGet(inner.size.toLong())
                if ((chan == Ch.VIDEO || chan == Ch.CMD) && Wire.u32(inner, InnerCodec.hdrLen(chan) - 4) == Msg.VIDEO_HEARTBEAT) {
                    onHeartbeat(chan)
                    continue
                }
                synchronized(dispatchLock) { sink(chan, inner) }
            }
        } catch (e: BadInnerHeader) {
            AppLog.e("tcp6 ${Ch.name(chan)}: плохой заголовок inner: ${e.message}")
            fail(chan, "плохой заголовок: ${e.message}")
        } catch (e: EOFException) {
            fail(chan, "обрыв посреди сообщения")
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка чтения: ${e.message}")
        }
    }

    private fun onHeartbeat(chan: Int) {
        val now = clock()
        if (chan == Ch.CMD) hbCmd++
        if (hbCount == 0L) {
            hbFirstMs = now
            WLog.ev(WLog.TCP, "hb-first", "afterMs" to now - t0, "chan" to Ch.name(chan))
        } else {
            val gap = now - hbLastMs
            if (gap > hbMaxGapMs) hbMaxGapMs = gap
        }
        hbLastMs = now
        hbCount++
    }

    private fun writeLoop(chan: Int, s: Socket, onWritten: (Int, ByteArray) -> Unit) {
        val q = queues.getValue(chan)
        val video = chan == Ch.VIDEO
        try {
            val out = s.getOutputStream()
            while (!closed.get()) {
                val inner = q.take()
                if (video) {
                    videoBytes.addAndGet(-inner.size.toLong())
                    videoFrames.decrementAndGet()
                }
                val t = System.nanoTime()
                out.write(inner)
                out.flush()
                if (video) drain.onWrite(clock(), true, inner.size, System.nanoTime() - t)
                synchronized(txLock) { onWritten(chan, inner) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка записи: ${e.message}")
        }
    }

    companion object {
        private const val READ_BUF = 64 * 1024
    }
}

/** 供测试和诊断使用：从流中逐个读取 inner（InnerCodec 的包装）。 */
fun readInners(chan: Int, input: InputStream): List<ByteArray> {
    val din = DataInputStream(input)
    val out = ArrayList<ByteArray>()
    while (true) out += InnerCodec.read(chan, din) ?: return out
}
