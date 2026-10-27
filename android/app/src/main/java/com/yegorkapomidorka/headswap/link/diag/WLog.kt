package com.yegorkapomidorka.headswap.link.diag

import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog

/**
 * 连接阶段的结构化日志（框架 Э6）：形如 `WL <阶段> <事件> 键=值 … t=<自尝试开始以来的毫秒数>` 的行
 * 写入总日志（仅文件和 logcat，不上屏）。依据这些行即可分析测试者的日志，无需再向其提问。
 *
 * WL 中没有、也不应出现的内容：SSID 和密码（热点、P2P 组）、p2pNetInfo 和 scanRecord（只记录是否存在
 * 与长度）、密钥、证书、签名和握手的随机值、hex 转储。BT-MAC 只能通过 [maskMac] 输出。
 */
object WLog {
    /** 连接阶段。 */
    const val LINK = "link"
    const val TCP = "tcp"
    const val HS = "hs"
    const val VIDEO = "video"
    const val BT = "bt"
    /** 手机加入车机（ГУ）的 Wi-Fi Direct 组（分支 A）。 */
    const val P2P = "p2p"
    /** 通过汽车蓝牙自动启动（选择、ACL、服务自动模式）。 */
    const val TRIGGER = "trigger"

    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile var sink: (String) -> Unit = { AppLog.i(it, ui = false) }
    @Volatile private var t0 = 0L

    /** 连接尝试的开始：t 从此刻起算。 */
    fun begin(kind: String, vararg kv: Pair<String, Any?>) {
        t0 = clock()
        ev(LINK, "begin", "transport" to kind, *kv)
    }

    fun ev(phase: String, event: String, vararg kv: Pair<String, Any?>) {
        sink(format(phase, event, clock() - t0, kv.asList()))
    }

    fun format(phase: String, event: String, tMs: Long, kv: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("WL ").append(phase).append(' ').append(event)
        for ((k, v) in kv) sb.append(' ').append(k).append('=').append(value(v))
        return sb.append(" t=").append(tMs).toString()
    }

    /** 值压缩成单个词：空格和引号被转义，过长则截断。 */
    private fun value(v: Any?): String {
        val s = (v ?: "-").toString().replace('\n', ' ')
        val cut = if (s.length > MAX_VALUE) s.take(MAX_VALUE) + "…" else s
        return if (cut.isEmpty() || cut.any { it == ' ' || it == '"' || it == '=' }) "\"" + cut.replace("\"", "'") + "\"" else cut
    }

    /** BT-MAC：前三个八位组（厂商），其余部分隐藏。 */
    fun maskMac(mac: String?): String {
        if (mac == null) return "-"
        val p = mac.split(':')
        return if (p.size == 6) p.take(3).joinToString(":") + ":xx:xx:xx" else "?"
    }

    private const val MAX_VALUE = 120
}
