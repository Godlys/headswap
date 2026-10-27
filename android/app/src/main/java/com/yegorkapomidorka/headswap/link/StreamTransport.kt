package com.yegorkapomidorka.headswap.link

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.FrameParser
import com.yegorkapomidorka.headswap.Hex
import com.yegorkapomidorka.headswap.InnerAssembler
import com.yegorkapomidorka.headswap.ProtocolDesync
import com.yegorkapomidorka.headswap.Wire
import com.yegorkapomidorka.headswap.aa.DrainMeter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 带外部头 [chan u32][len u32] 的单一字节流：USB AOA 和 debug-TCP。
 * 读写代码从 CarLifeSession 原样移植、行为不变：所有通道共用一条 FIFO 队列，
 * 唯一写入者，头部单独 write、正文按 <= 16384 分块（Wire.writeSequence），每条消息后 flush。
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    override val caps: TransportCaps,
    override val label: String,
    /** 关闭原始描述符（pfd.close）；在 close() 中最先调用，与之前在会话中一样。 */
    private val closeFd: () -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : CarTransport {

    private class Out(val chan: Int, val inner: ByteArray)

    private val queue = LinkedBlockingQueue<Out>()
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val drain = DrainMeter()
    private val parser = FrameParser()
    private val assembler = InnerAssembler()
    private val closed = AtomicBoolean(false)
    private var reader: Thread? = null
    private var writer: Thread? = null
    @Volatile private var desync = false
    @Volatile private var rx = 0L
    @Volatile var lastRxMs = 0L
        private set

    override val rxBytes: Long get() = rx
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val drainBytesPerSec: Double get() = drain.bytesPerSec
    override fun queueSize(chan: Int): Int = queue.size

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        lastRxMs = clock()
        writer = Thread({ writeLoop(onWritten, onClosed) }, "cl-writer").also { it.start() }
        reader = Thread({ readLoop(sink, onClosed) }, "cl-reader").also { it.start() }
    }

    override fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        if (chan == Ch.VIDEO) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        queue.put(Out(chan, inner))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closeFd()
        } catch (_: Exception) {
        }
        writer?.interrupt()
        reader?.interrupt()
        try {
            input.close()
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
    }

    private fun readLoop(sink: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        val buf = ByteArray(Wire.CHUNK)
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    if (!closed.get()) onClosed("EOF чтения (ГУ отключилось)")
                    return
                }
                lastRxMs = clock()
                rx += n
                AppLog.detail("RAW rx read=$n bytes: ${Hex.compact(buf.copyOf(minOf(n, 32)), 32)}")
                if (desync) continue
                try {
                    parser.feed(buf, 0, n) { chan, inner -> assembler.feed(chan, inner, sink) }
                } catch (e: ProtocolDesync) {
                    desync = true
                    AppLog.e("РАССИНХРОН потока: ${e.message}; дальше только сырой дамп")
                    AppLog.detail(Hex.dump(buf.copyOf(n), 256))
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка чтения", e)
                onClosed("ошибка чтения: ${e.message}")
            }
        }
    }

    private fun writeLoop(onWritten: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        try {
            while (!closed.get()) {
                val o = queue.take()
                val video = o.chan == Ch.VIDEO
                if (video) {
                    videoBytes.addAndGet(-o.inner.size.toLong())
                    videoFrames.decrementAndGet()
                }
                // 头部单独 write，正文随后按 <= 16384 分块（规范 §1.4）
                val t0 = System.nanoTime()
                for (part in Wire.writeSequence(o.chan, o.inner)) output.write(part)
                output.flush()
                drain.onWrite(clock(), video, o.inner.size, System.nanoTime() - t0)
                onWritten(o.chan, o.inner)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка записи", e)
                onClosed("ошибка записи: ${e.message}")
            }
        }
    }

    companion object {
        /** USB 附件（AOA）或 debug-TCP，以 ParcelFileDescriptor 打开。 */
        fun fromPfd(pfd: ParcelFileDescriptor, caps: TransportCaps, label: String) = StreamTransport(
            FileInputStream(pfd.fileDescriptor), FileOutputStream(pfd.fileDescriptor), caps, label,
            closeFd = { pfd.close() },
        )
    }
}
