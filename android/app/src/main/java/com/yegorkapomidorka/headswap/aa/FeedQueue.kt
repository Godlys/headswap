package com.yegorkapomidorka.headswap.aa

/** 转码器喂帧队列的元素：Android Auto 帧或喂帧线程的命令。 */
sealed class FeedItem {
    class Frame(val annexB: ByteArray, val ptsUs: Long, val idr: Boolean, val atNs: Long) : FeedItem()
    class Config(val annexB: ByteArray) : FeedItem()
    object Reset : FeedItem()
    object Stop : FeedItem()
}

enum class Offer { ACCEPTED, DROPPED_FULL, DROPPED_GATED }

/**
 * Android Auto 线程与解码器喂帧线程之间的队列（纯 JVM 逻辑）。入口从不阻塞。
 * 帧数受 [maxFrames] 限制：溢出时帧被丢弃，队列「关闭」直到下一个 IDR
 * （没有参考帧的 P 帧解码器不需要）。IDR 自给自足：它驱逐所有等待中的帧
 * （命令保持原位），解除关闭状态并始终通过。命令（[post]）不会被拒绝。
 * 单一消费者（[take]），多个生产者。
 */
class FeedQueue(private val maxFrames: Int = DEFAULT_MAX_FRAMES) {
    private val items = ArrayDeque<FeedItem>()
    private var frames = 0
    private var gated = false
    private var maxSeen = 0
    private val mon = Object()

    val pendingFrames: Int get() = synchronized(mon) { frames }

    /** 自上次 [resetMaxPending] 以来的最大帧深度（不重置）。 */
    val maxPendingSeen: Int get() = synchronized(mon) { maxSeen }

    /** 返回自上次调用以来的最大深度，并从当前深度重新开始统计窗口。 */
    fun resetMaxPending(): Int = synchronized(mon) {
        val m = maxSeen
        maxSeen = frames
        m
    }

    fun offerFrame(f: FeedItem.Frame): Offer = synchronized(mon) {
        if (gated && !f.idr) return Offer.DROPPED_GATED
        if (f.idr) {
            if (frames > 0) {
                items.removeAll { it is FeedItem.Frame }
                frames = 0
            }
            gated = false
        } else if (frames >= maxFrames) {
            gated = true
            return Offer.DROPPED_FULL
        }
        items.addLast(f)
        frames++
        if (frames > maxSeen) maxSeen = frames
        mon.notifyAll()
        Offer.ACCEPTED
    }

    /** Config/Reset/Stop：无论队列满否，始终放入队尾。 */
    fun post(cmd: FeedItem) {
        require(cmd !is FeedItem.Frame) { "кадры - через offerFrame" }
        synchronized(mon) {
            items.addLast(cmd)
            mon.notifyAll()
        }
    }

    /** 等待元素最多 [timeoutMs] 毫秒；null = 超时（或线程被中断）。 */
    fun take(timeoutMs: Long): FeedItem? = synchronized(mon) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (items.isEmpty()) {
            val leftNs = deadline - System.nanoTime()
            if (leftNs <= 0) return null
            try {
                mon.wait(leftNs / 1_000_000L, (leftNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val x = items.removeFirst()
        if (x is FeedItem.Frame) frames--
        x
    }

    /** 清空全部并解除关闭状态。 */
    fun clear() = synchronized(mon) {
        items.clear()
        frames = 0
        gated = false
    }

    companion object {
        /**
         * 30 fps 时约 0.5 秒。解码器创建需要 80-300 毫秒（60 fps 时最多 18 帧），这些帧必须等待解码器就绪，
         * 而不是在每次启动时向 Android Auto 触发 IDR 请求。在 4 帧容量下，10 帧的一批数据会在
         * createDecoder 期间直接溢出队列（transcode_test burst，2026.10.23）。
         */
        const val DEFAULT_MAX_FRAMES = 16
    }
}
