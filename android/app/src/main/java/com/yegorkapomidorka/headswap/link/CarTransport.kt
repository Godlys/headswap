package com.yegorkapomidorka.headswap.link

/**
 * CarLife 传输层属性。对 USB 而言，这些值与抽象出现之前完全相同。
 * kind 以「transport=…」字符串写入日志和 WL 日志。
 */
class TransportCaps(
    /** usb-aoa、tcp-debug（单流 + 外部头部）、tcp6（6 个 Baidu socket，无外部头部）。 */
    val kind: String,
    /** 带外部头部 [chan u32][len u32] 的流（USB AOA、debug-TCP）。 */
    val outerHeader: Boolean,
    /** 每个通道一个独立 socket，写入者面向通道。 */
    val separateSockets: Boolean,
    /** 断开以系统事件（USB_ACCESSORY_DETACHED）到达，而非仅 EOF。 */
    val nativeDetach: Boolean,
) {
    companion object {
        val USB_AOA = TransportCaps("usb-aoa", outerHeader = true, separateSockets = false, nativeDetach = true)
        val TCP_DEBUG = TransportCaps("tcp-debug", outerHeader = true, separateSockets = false, nativeDetach = false)
        val TCP6 = TransportCaps("tcp6", outerHeader = false, separateSockets = true, nativeDetach = false)
    }
}

/**
 * CarLife 传输层，位于「通道 + inner 消息」层级（inner = 8/12 字节内部头部 + 正文）。
 * 协议（CarLifeSession）不知道通道在线缆上如何分布：带外部头部的单流，或每通道独立 socket。
 */
interface CarTransport {
    val caps: TransportCaps

    /** 用于日志和界面：配件或车机地址的描述。 */
    val label: String

    /**
     * 启动读取与写入。sink 的调用是串行化的（每次一个线程），onWritten 同样。
     * onClosed 最多调用一次，且仅在传输侧断开时，而非在 close() 之后。
     */
    fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    )

    /** 非阻塞地将 inner 排入通道队列。 */
    fun send(chan: Int, inner: ByteArray)

    /** 关闭一切，唤醒线程。幂等，且不会调用 onClosed。 */
    fun close()

    /** 已从车机接收的字节数（所有通道）。 */
    val rxBytes: Long

    /** 通道 chan 在队列中的消息数（流对所有通道共用一条队列）。 */
    fun queueSize(chan: Int): Int

    val queuedVideoBytes: Long
    val queuedVideoFrames: Int

    /** 视频排空速率，字节/秒（0 = 尚未测得）。 */
    val drainBytesPerSec: Double
}
