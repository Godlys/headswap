package com.yegorkapomidorka.headswap.link.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.link.diag.WLog

/** 车机网络请求的状态 (报告中显示为 [label])。 */
enum class CarNetState(val label: String) {
    IDLE("idle"),
    REQUESTING("requesting"),
    AVAILABLE("available"),
    UNAVAILABLE("unavailable"),
}

/**
 * 手机通过 WifiNetworkSpecifier 接入车机 Wi-Fi: 是无互联网的本地网络, 互联网仍走主网络
 * (不使用 bindProcessToNetwork)。回调在主线程触发。请求会一直保持, 直到调用 [release]
 * (或网络消失: 此时 [state] 重置为 IDLE, 由调用方决定是否重新请求)。
 * 第一次尝试 WPA2, 带密码被拒时重试一次 WPA3; 无密码则为开放网络。
 * SSID 和密码不会进入 WL 日志, 异常只记录类名。
 *
 * [onState] - 用于上报的状态镜像: 只有服务实例会写入, 设置页的检查不会传入。
 */
class CarNetwork(ctx: Context, private val onState: (CarNetState) -> Unit = {}) {
    private val app = ctx.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var state: CarNetState = CarNetState.IDLE
        private set(v) {
            field = v
            onState(v)
        }

    /** 车机网络, 当 [state] == AVAILABLE 时有效。 */
    @Volatile var current: Network? = null
        private set

    /** 车机网络的接口 (wlan0/wlan1): 据此筛选 beacon 的地址。与 [current] 同时已知。 */
    @Volatile var ifaceName: String? = null
        private set

    private var cb: Cb? = null

    private enum class Sec(val label: String) { OPEN("open"), WPA2("wpa2"), WPA3("wpa3") }

    private class Params(
        val ssid: String,
        val pass: String,
        val timeoutMs: Long,
        val onAvailable: (Network) -> Unit,
        val onUnavailable: (String) -> Unit,
        val onLost: () -> Unit,
    )

    /**
     * 请求网络。已在请求或已获取 - 不做任何事。当网络和接口名都已知时调用 [onAvailable];
     * [onUnavailable] - 网络未找到、对话框内被拒或请求出错 (reason - 简短代码)。
     */
    fun request(
        ssid: String,
        pass: String,
        timeoutMs: Long,
        onAvailable: (Network) -> Unit,
        onUnavailable: (String) -> Unit,
        onLost: () -> Unit,
    ) {
        if (state == CarNetState.REQUESTING || state == CarNetState.AVAILABLE) return
        logStaConcurrency()
        val first = CarSecPolicy.first(rememberedSec(ssid), pass.isNotEmpty())
        start(Params(ssid, pass, timeoutMs, onAvailable, onUnavailable, onLost), Sec.entries.first { it.label == first })
    }

    /** 来自 [CarWifi] 的成功加密方式, 仅当请求的正是已保存的车机网络时。 */
    private fun rememberedSec(ssid: String): String? = CarWifi(app).let { if (it.ssid == ssid) it.sec else null }

    /** 取消请求 (如果网络仅由该请求持有, 也一并释放)。可任意次调用。 */
    fun release(why: String) {
        val c = cb
        cb = null
        if (c != null) {
            c.unregister()
            WLog.ev(NET, "car-release", "why" to why)
        }
        current = null
        ifaceName = null
        if (state != CarNetState.IDLE) state = CarNetState.IDLE
    }

    private fun start(p: Params, sec: Sec) {
        val req = try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(p.ssid).apply {
                when (sec) {
                    Sec.OPEN -> {}
                    Sec.WPA2 -> setWpa2Passphrase(p.pass)
                    Sec.WPA3 -> setWpa3Passphrase(p.pass)
                }
            }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        } catch (e: Exception) {      // SSID 过长、密码过短: message 可能含 SSID, 日志只记类名
            cb = null
            fail(p, sec, e.javaClass.simpleName)
            return
        }
        val c = Cb(p, sec)
        cb = c
        state = CarNetState.REQUESTING
        WLog.ev(NET, "car-request", "sec" to sec.label, "timeoutMs" to p.timeoutMs)
        try {
            cm.requestNetwork(req, c, handler, p.timeoutMs.toInt())
            c.registered = true
        } catch (e: Exception) {
            cb = null
            fail(p, sec, e.javaClass.simpleName)
        }
    }

    private fun fail(p: Params, sec: Sec, err: String) {
        WLog.ev(NET, "car-unavailable", "sec" to sec.label, "err" to err)
        state = CarNetState.UNAVAILABLE
        p.onUnavailable("error:$err")
    }

    private inner class Cb(val p: Params, val sec: Sec) : ConnectivityManager.NetworkCallback() {
        var registered = false
        private var net: Network? = null
        private var ready = false

        /** 来自上一次尝试的回调, 或请求已被取消。 */
        private fun stale() = cb !== this

        fun unregister() {
            if (!registered) return
            registered = false
            try {
                cm.unregisterNetworkCallback(this)
            } catch (_: IllegalArgumentException) {      // 超时: 系统已自动注销
            }
        }

        override fun onAvailable(network: Network) {
            if (!stale()) net = network
        }

        /** 接口名从这里获取: onAvailable 时 LinkProperties 还没有。 */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale() || ready || network != net) return
            val name = linkProperties.interfaceName ?: return
            ready = true
            current = network
            ifaceName = name
            state = CarNetState.AVAILABLE
            CarWifi(app).let { if (it.ssid == p.ssid) it.rememberSec(sec.label) }
            WLog.ev(NET, "car-available", "iface" to name, "sec" to sec.label,
                "addr" to linkProperties.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress)
            p.onAvailable(network)
        }

        override fun onUnavailable() {
            if (stale()) return
            unregister()
            WLog.ev(NET, "car-unavailable", "sec" to sec.label)
            if (CarSecPolicy.retryWpa3(rememberedSec(p.ssid), sec.label, p.pass.isNotEmpty())) {
                start(p, Sec.WPA3)      // 网络可能是 WPA3: 重试一次
                return
            }
            cb = null
            state = CarNetState.UNAVAILABLE
            p.onUnavailable("no-network")
        }

        override fun onLost(network: Network) {
            if (stale() || network != net) return
            unregister()
            cb = null
            current = null
            ifaceName = null
            state = CarNetState.IDLE
            WLog.ev(NET, "car-lost")
            p.onLost()
        }
    }

    private fun logStaConcurrency() {
        if (staLogged) return
        staLogged = true
        val v = if (Build.VERSION.SDK_INT >= 31) {
            try {
                app.getSystemService(WifiManager::class.java).isStaConcurrencyForLocalOnlyConnectionsSupported.toString()
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        } else "n/a"
        WLog.ev(NET, "sta-concurrency", "local" to v)
    }

    private companion object {
        const val NET = "net"
        @Volatile var staLogged = false
    }
}
