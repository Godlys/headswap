package com.yegorkapomidorka.headswap.aa

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 桥接器 Android Auto 侧的生命周期（实现 AaConnector）。 */
interface AaLifecycle {
    fun start()

    /** 正确关闭 Android Auto（ByeBye、flush、EOF、close）；全部关闭完成后在独立线程中调用 onDone。 */
    fun stop(onDone: () -> Unit)
}

/**
 * 桥接器的 Android Auto 侧，其生命周期是「服务」的生命周期，而非单个 CarLife USB 会话。USB 物理断开（EIO）
 * 会关闭 CarLife 会话，但 Android Auto 会话在 graceMs 内保持「热」状态：若 USB 恢复，新的
 * CarLife 会话会接管存活的 Android Auto（core.attach + START → keyframe）；若未恢复或按下停止，
 * 则正确关闭 Android Auto。一次性服务器 5277 在前一个连接存活期间不应接受新连接：
 * 每个 host 一个连接器，新 host 等待前一个完全关闭（previous）。
 */
class BridgeHost(
    val core: AaBridgeCore,
    private val connector: AaLifecycle,
    private val scheduler: ScheduledExecutorService,
    private val log: (String, Boolean) -> Unit,
    private val graceMs: Long = GRACE_MS,
    private val previous: BridgeHost? = null,
    private val onTornDown: () -> Unit = {},
) {
    private var started = false
    private var tearingDown = false
    private var grace: ScheduledFuture<*>? = null
    private val tornDownLatch = CountDownLatch(1)

    /** Android Auto 已完全关闭（ByeBye、EOF、close）。 */
    val isTornDown: Boolean get() = tornDownLatch.count == 0L

    /** host 正在关闭或已关闭：新的 CarLife 会话必须挂到新 host 上。 */
    @get:Synchronized
    val isDead: Boolean get() = tearingDown

    @get:Synchronized
    val isInGrace: Boolean get() = grace != null

    fun awaitTornDown(timeoutMs: Long): Boolean = tornDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** 新的 CarLife 会话：取消 grace；第一次时启动 Android Auto。false = host 已在关闭。 */
    @Synchronized
    fun onUsbSessionStarted(): Boolean {
        if (tearingDown) return false
        grace?.let {
            it.cancel(false)
            grace = null
            log("BRIDGE USB вернулся в grace-окно: AA-сессия жива, переиспользую", true)
        }
        if (!started) {
            started = true
            val prev = previous
            if (prev != null && !prev.isTornDown) {
                // 服务器 5277 是一次性的：只有在前一个连接完全关闭后才能建立新连接
                log("BRIDGE жду полного закрытия предыдущей AA-сессии перед новым подключением", true)
                Thread({
                    prev.awaitTornDown(15_000)
                    synchronized(this) { if (!tearingDown) connector.start() }
                }, "aa-host-start").start()
            } else {
                connector.start()
            }
        }
        return true
    }

    /** CarLife 会话已关闭。immediate（停止按钮/关闭桥接器）时立即关闭 Android Auto，否则保持 graceMs。 */
    @Synchronized
    fun onUsbSessionClosed(immediate: Boolean) {
        if (tearingDown) return
        if (immediate) {
            tearDown("остановлено пользователем")
            return
        }
        grace?.cancel(false)
        log("BRIDGE USB закрыт: держу AA тёплой ${graceMs} мс", true)
        AaState.status = "мост: USB потерян, AA жива ${graceMs / 1000} с"
        grace = scheduler.schedule({ onGraceExpired() }, graceMs, TimeUnit.MILLISECONDS)
    }

    private fun onGraceExpired() {
        synchronized(this) {
            if (tearingDown || grace == null) return
            grace = null
            tearDown("USB не вернулся за ${graceMs} мс")
        }
    }

    /** 立即关闭 Android Auto（停止、服务被销毁、切换模式）。 */
    @Synchronized
    fun shutdown(why: String) {
        if (tearingDown) return
        tearDown(why)
    }

    private fun tearDown(why: String) {
        tearingDown = true
        grace?.cancel(false)
        grace = null
        log("BRIDGE закрываю AA ($why): ByeBye, flush, EOF, close", true)
        if (!started) {
            tornDownLatch.countDown()
            onTornDown()
            return
        }
        connector.stop {
            tornDownLatch.countDown()
            onTornDown()
        }
    }

    companion object {
        /** USB 丢失后保持 Android Auto 会话存活的时间。 */
        const val GRACE_MS = 8_000L
    }
}
