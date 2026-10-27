package com.yegorkapomidorka.headswap.link.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网调试探测（命令 `cmd=net_probe`）：检查平台是否放行 B 分支的 beacon 和应答端。
 * 向 7999 端口发送 UDP “CarlifeHost”（向每个带 broadcast 地址的 IPv4 接口发广播，若指定了 unicast 也发送），
 * 监听 UDP 8999 并回复就绪 JSON。全部写入 WL 日志：ACCESS_LOCAL_NETWORK 状态、接口、
 * 每次发送的结果（ok 或异常类名）、收到的数据报（地址、长度、是否含 carlifehost 字符串）。
 * 入站 TCP 单独检查：用 `--es debug_tcp6_bind 0.0.0.0` 调试 tcp6。
 */
object NetProbe {
    const val BEACON_PORT = 7999
    const val REPLY_PORT = 8999
    private val BEACON = "CarlifeHost".toByteArray()
    private val READY = """{"carlifehost":"carlife","status":"ready"}""".toByteArray()

    /** 是否已授予 ACCESS_LOCAL_NETWORK（Android 17 之前没有该权限，可访问 LAN）。 */
    fun localNetworkGranted(ctx: Context): String =
        if (Build.VERSION.SDK_INT < 37) "n/a(api ${Build.VERSION.SDK_INT})"
        else if (ctx.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) "granted"
        else "denied"

    fun run(ctx: Context, seconds: Int, unicast: String?) {
        Thread({ probe(ctx, seconds, unicast) }, "net-probe").start()
    }

    private fun probe(ctx: Context, seconds: Int, unicast: String?) {
        WLog.begin("net-probe", "api" to Build.VERSION.SDK_INT, "targetSdk" to ctx.applicationInfo.targetSdkVersion,
            "ACCESS_LOCAL_NETWORK" to localNetworkGranted(ctx))
        val targets = ArrayList<Pair<String, InetAddress>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast
                    WLog.ev("net", "iface", "name" to ni.name, "ip" to a.hostAddress, "bcast" to b?.hostAddress)
                    if (b != null) targets += "bcast:${ni.name}" to b
                }
            }
        } catch (e: Exception) {
            WLog.ev("net", "iface-fail", "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        if (unicast != null) targets += "unicast" to InetAddress.getByName(unicast)

        val listener = Thread({ listen(seconds) }, "net-probe-8999").also { it.start() }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val rounds = maxOf(1, seconds / 2)
                for (i in 1..rounds) {
                    for ((label, addr) in targets) {
                        val res = try {
                            s.send(DatagramPacket(BEACON, BEACON.size, addr, BEACON_PORT))
                            "ok"
                        } catch (e: Exception) {
                            "${e.javaClass.simpleName}: ${e.message}"
                        }
                        if (i == 1 || res != "ok") WLog.ev("udp", "beacon", "to" to label, "addr" to addr.hostAddress, "round" to i, "res" to res)
                    }
                    Thread.sleep(2000)
                }
                WLog.ev("udp", "beacon-done", "rounds" to rounds, "targets" to targets.size)
            }
        } catch (e: Exception) {
            WLog.ev("udp", "beacon-fail", "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        listener.join()
    }

    private fun listen(seconds: Int) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var n = 0
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(REPLY_PORT))
                WLog.ev("udp", "listen", "port" to REPLY_PORT)
                val buf = ByteArray(1500)
                while (System.currentTimeMillis() < deadline) {
                    s.soTimeout = maxOf(1, (deadline - System.currentTimeMillis()).toInt())
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    n++
                    val hit = String(p.data, 0, p.length, Charsets.ISO_8859_1).contains("carlifehost")
                    val res = if (!hit) "skip" else try {
                        s.send(DatagramPacket(READY, READY.size, p.socketAddress))
                        "replied"
                    } catch (e: Exception) {
                        "${e.javaClass.simpleName}: ${e.message}"
                    }
                    WLog.ev("udp", "rx", "port" to REPLY_PORT, "from" to p.address.hostAddress, "len" to p.length, "carlifehost" to hit, "res" to res)
                }
            }
        } catch (e: Exception) {
            WLog.ev("udp", "listen-fail", "port" to REPLY_PORT, "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        WLog.ev("udp", "listen-done", "rx" to n)
    }
}
