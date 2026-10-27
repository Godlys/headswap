package com.yegorkapomidorka.headswap.link.bt

/** 如何处理来自车机的下一条 TARGET_INFO。 */
enum class P2pOnTarget { START, RESTART, SKIP }

/** 进入车机 P2P 组的纯逻辑，不依赖 android.*：由 JVM 测试验证。 */
object P2pPolicy {
    /**
     * 收到新的 TARGET_INFO（车机在每次蓝牙连接时都会发送）：已在组内——跳过，IP 会通过
     * IP_REQUEST 重新下发；尝试进行中但未进入（BUSY、未找到对端、Wi-Fi 开启较晚、车机改名）——用新凭据
     * 重新开始（h126）。凭据相同且尝试时间小于 [freshMs]——不做处理：rejoin 每 5-10 秒询问一次
     * TARGET_INFO，对每个应答都重启会中断搜索和挂起的邀请（h211，Ramil 21.10）。
     */
    fun onTarget(active: Boolean, joined: Boolean, same: Boolean = false, ageMs: Long = Long.MAX_VALUE, freshMs: Long = 30_000L): P2pOnTarget = when {
        joined -> P2pOnTarget.SKIP
        active && same && ageMs < freshMs -> P2pOnTarget.SKIP
        active -> P2pOnTarget.RESTART
        else -> P2pOnTarget.START
    }

    /**
     * 车机组已解散而蓝牙仍存活（h211、Mazda G6S：倒车超过约 25 秒）：车机自己不发送 TARGET_INFO，
     * 直到重新连接蓝牙，而这只会在汽车休眠后发生。我们自己请求它：2 秒后、5 秒后，此后
     * 每 10 秒一次；null——够了（约 10 分钟未进入，车机显然不愿意）。
     */
    fun rejoinDelayMs(attempt: Int): Long? = when {
        attempt < 0 -> null
        attempt == 0 -> 2_000L
        attempt == 1 -> 5_000L
        attempt < REJOIN_ATTEMPTS -> 10_000L
        else -> null
    }

    /**
     * 在第 2、7、17 和 27 秒的 TARGET_INFO 请求中车机都有响应，但对端始终未在空中出现（第一次 30 秒
     * 进入尝试在约 32 秒时结束）：Desay G6S 只在新的蓝牙连接上启动 Wi-Fi Direct（h211、
     * S23 23.10：2 分钟 rejoin 无对端，BT 重连后 1.5 秒对端出现；h185）。约 37 秒时断开 RFCOMM。
     */
    fun rejoinDropBt(attempt: Int): Boolean = attempt >= REJOIN_DROP_BT_AFTER

    const val REJOIN_ATTEMPTS = 60
    const val REJOIN_DROP_BT_AFTER = 4
}
