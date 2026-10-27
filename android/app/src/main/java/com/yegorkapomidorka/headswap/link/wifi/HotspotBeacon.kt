package com.yegorkapomidorka.headswap.link.wifi

import android.net.Network
import android.os.SystemClock
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 发送信标的本地网络 IPv4 接口：手机热点（分支 B）或手机已加入的车机网络（C′）。 */
class LanIface(val name: String, val ip: Inet4Address, val prefix: Int, val broadcast: Inet4Address) {
    /** 地址是否位于接口子网内（车机 socket 也据此判断放行）。 */
    fun contains(a: InetAddress): Boolean {
        if (a !is Inet4Address) return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (toInt(a) and mask) == (toInt(ip) and mask)
    }

    /** 子网主机地址（排除自身地址、网络地址和 broadcast）；仅限 /24 及掩码更长的前缀（否则数量过多）。 */
    fun hosts(): List<Inet4Address> {
        if (prefix < 24 || prefix > 30) return emptyList()
        val mask = -1 shl (32 - prefix)
        val net = toInt(ip) and mask
        val n = 1 shl (32 - prefix)
        val me = toInt(ip)
        return (1 until n - 1).map { net + it }.filter { it != me }.map { fromInt(it) }
    }

    override fun toString() = "$name ${ip.hostAddress}/$prefix"

    companion object {
        fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun fromInt(v: Int): Inet4Address = InetAddress.getByAddress(
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
        ) as Inet4Address

        /**
         * 接口是否适合发送信标。移动数据（rmnet、ccmni）、loopback、隧道和 dummy 不适用；
         * p2p 不适用（那是分支 A，同 Baidu 的 "mix" 模式）。其余带 broadcast 地址的 IPv4 适用。
         */
        fun eligibleName(name: String): Boolean =
            EXCLUDED_PREFIXES.none { name.startsWith(it) }

        /** 热点接口（而非 STA）：仅向这些接口以 unicast 扫发信标，不向 wlan0 家庭网络发送。 */
        fun sweepName(name: String): Boolean = name != "wlan0"

        private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "lo", "dummy", "tun", "ppp", "p2p", "ip6tnl", "sit", "v4-", "clat")

        /** 当前合适的接口。 */
        fun scan(): List<LanIface> {
            val out = ArrayList<LanIface>()
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                try {
                    if (!ni.isUp || ni.isLoopback || !eligibleName(ni.name)) continue
                } catch (_: SocketException) {
                    continue
                }
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast as? Inet4Address ?: continue
                    out += LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
                }
            }
            return out
        }
    }
}

/** 分支 B 的数据字节：手机信标和对车机询问的应答。 */
object BeaconCodec {
    const val BEACON_PORT = 7999
    const val PROBE_PORT = 8999

    /** Baidu MD 8.6.7 发送的内容（ConnectManager.sendHotspotUdp）：车机只取发送方地址。 */
    val BEACON: ByteArray = "CarlifeHost".toByteArray(Charsets.US_ASCII)

    /** 对 8999 端口询问的应答（FT/CL2AA）（为个别车机做的扩展，官方 MD 没有该监听器）。 */
    val READY: ByteArray = """{"carlifehost":"carlife","status":"ready"}""".toByteArray(Charsets.US_ASCII)

    /** 车机询问：包含 carlifehost 字符串的数据报（大小写敏感，同 FT）。 */
    fun isProbe(data: ByteArray, len: Int): Boolean = String(data, 0, len, Charsets.ISO_8859_1).contains("carlifehost")
}

/**
 * 分支 B：手机开启热点，车机作为客户端接入。每 2 秒向每个合适接口的 broadcast 发送 "CarlifeHost" 信标（UDP 7999），
 * 每 12 秒向热点子网内每个地址 unicast 发送（部分车机收不到 broadcast：FT、CL2AA）；
 * 同时在 UDP 8999 上应答。直到车机连满 6 个 socket（stop()）前持续工作。全部记入 WL 日志，不含车机数据包内容。
 */
class HotspotBeacon(
    private val ifaces: () -> List<LanIface> = { LanIface.scan() },
    private val intervalMs: Long = 2_000,
    private val sweepMs: Long = 12_000,
    private val beaconPort: Int = BeaconCodec.BEACON_PORT,
    private val probePort: Int = BeaconCodec.PROBE_PORT,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** 网络集合发生变化（热点开启/关闭）：用于屏幕上的状态显示。 */
    private val onIfaces: (List<LanIface>) -> Unit = {},
    /**
     * 手机加入的车辆网络（WifiNetworkSpecifier）：信标与应答 socket 绑定到该网络，以免数据包流入主网络。
     * 同时关闭 unicast 扫发：车辆网络可能位于 wlan1 而非热点接口上。调用方自行将 [ifaces] 列表收窄到该网络的接口。
     * null - 同之前一致。
     */
    private val bindNetwork: Network? = null,
) {
    private val running = AtomicBoolean(false)
    private var sender: Thread? = null
    private var responder: Thread? = null
    @Volatile private var probeSocket: DatagramSocket? = null
    @Volatile private var known: Set<String>? = null   // null：尚未查看过，首次刷新总是上报

    @Volatile var sent = 0L
        private set
    @Volatile var failed = 0L
        private set
    @Volatile var probes = 0L
        private set

    /** 应答器实际端口（测试中 0 = 任意端口）。打开前为 -1。 */
    val probePortBound: Int get() = probeSocket?.localPort ?: -1

    /** 当前信标发往的子网：Tcp6Listener 据此放行车机。 */
    @Volatile var current: List<LanIface> = emptyList()
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender = Thread({ sendLoop() }, "wl-beacon").apply { isDaemon = true; start() }
        responder = Thread({ probeLoop() }, "wl-probe-8999").apply { isDaemon = true; start() }
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        probeSocket?.close()
        sender?.interrupt()
        WLog.ev("udp", "beacon-stop", "why" to why, "sent" to sent, "failed" to failed, "probes" to probes)
    }

    private fun refresh(): List<LanIface> {
        val list = try {
            ifaces()
        } catch (e: Exception) {
            WLog.ev("net", "iface-fail", "err" to e.javaClass.simpleName)
            current
        }
        val keys = list.map { it.toString() }.toSet()
        if (keys != known) {
            val was = known.orEmpty()
            for (k in keys - was) WLog.ev("net", "iface-up", "iface" to k)
            for (k in was - keys) WLog.ev("net", "iface-down", "iface" to k)
            if (keys.isEmpty()) WLog.ev("net", "no-lan", "hint" to "хотспот выключен или нет подходящего интерфейса")
            known = keys
            try {
                onIfaces(list)
            } catch (_: Exception) {
            }
        }
        current = list
        return list
    }

    private fun sendLoop() {
        val firstOk = HashSet<String>()
        val firstErr = HashSet<String>()
        val firstSweep = HashSet<String>()
        var lastSweep = Long.MIN_VALUE / 2
        var lastSummary = clock()
        try {
            openSender().use { s ->
                s.broadcast = true
                while (running.get()) {
                    val now = clock()
                    val list = refresh()
                    val sweep = bindNetwork == null && now - lastSweep >= sweepMs
                    if (sweep) lastSweep = now
                    for (i in list) {
                        val targets = ArrayList<InetAddress>()
                        targets += i.broadcast
                        if (sweep && LanIface.sweepName(i.name)) targets += i.hosts()
                        for (t in targets) {
                            if (!running.get()) return   // 车机已连接：不再补发扫发
                            try {
                                s.send(DatagramPacket(BeaconCodec.BEACON, BeaconCodec.BEACON.size, t, beaconPort))
                                sent++
                                if (firstOk.add(i.name)) WLog.ev("udp", "beacon-first", "iface" to i.toString(), "to" to t.hostAddress)
                            } catch (e: Exception) {
                                failed++
                                if (firstErr.add(i.name)) WLog.ev("udp", "beacon-fail", "iface" to i.toString(), "err" to "${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                        if (sweep && LanIface.sweepName(i.name) && i.hosts().isNotEmpty() && firstSweep.add(i.name)) {
                            WLog.ev("udp", "sweep", "iface" to i.name, "hosts" to i.hosts().size)
                        }
                    }
                    if (now - lastSummary >= SUMMARY_MS) {
                        lastSummary = now
                        WLog.ev("udp", "beacon", "sent" to sent, "failed" to failed, "probes" to probes, "ifaces" to list.joinToString(",") { it.name })
                    }
                    Thread.sleep(intervalMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            WLog.ev("udp", "beacon-dead", "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 信标 socket；设置了 [bindNetwork] 时在首次发送前绑定到车辆网络。 */
    private fun openSender(): DatagramSocket {
        val net = bindNetwork ?: return DatagramSocket()
        val s = DatagramSocket(null)
        bindToNetwork(s, net, "beacon")
        s.bind(InetSocketAddress(0))
        return s
    }

    /** 将 socket 绑定到车辆网络；失败不致命（数据包会走系统路由），但会记入日志。 */
    private fun bindToNetwork(s: DatagramSocket, net: Network, what: String) {
        try {
            net.bindSocket(s)
        } catch (e: Exception) {
            WLog.ev("udp", "bind-net-fail", "sock" to what, "err" to e.javaClass.simpleName)
        }
    }

    private fun probeLoop() {
        try {
            val s = DatagramSocket(null)
            probeSocket = s
            if (bindNetwork != null) bindToNetwork(s, bindNetwork, "probe")
            s.reuseAddress = true
            s.bind(InetSocketAddress(probePort))
            WLog.ev("udp", "probe-listen", "port" to s.localPort)
            val buf = ByteArray(1500)
            s.use {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val hit = BeaconCodec.isProbe(p.data, p.length)
                    probes++
                    var res = "skip"
                    if (hit) {
                        res = try {
                            s.send(DatagramPacket(BeaconCodec.READY, BeaconCodec.READY.size, p.socketAddress))
                            "replied"
                        } catch (e: Exception) {
                            e.javaClass.simpleName
                        }
                    }
                    if (probes <= 5 || probes % 50 == 0L) {
                        WLog.ev("udp", "probe", "from" to p.address.hostAddress, "len" to p.length, "carlifehost" to hit, "res" to res, "n" to probes)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) WLog.ev("udp", "probe-fail", "port" to probePort, "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val SUMMARY_MS = 30_000L
    }
}
