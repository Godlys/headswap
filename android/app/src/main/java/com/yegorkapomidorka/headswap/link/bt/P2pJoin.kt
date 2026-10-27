package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * A 分支，Wi-Fi Direct 步骤：手机作为客户端加入车机的 P2P 组（凭据来自 Bluetooth 上的 TARGET_INFO）。
 * 首先直接用 ssid 和密码（WifiP2pConfig.Builder）；若失败 - 按名称在对端中找到车机，
 * 并以 groupOwnerIntent=0（组主为车机）通过 PBC 连接。当 p2p* 接口有 IPv4 时，
 * 调用 [onJoined]：手机地址通过 MD_WIFI_IP 发给车机，车机连接 TCP 通道。
 * 组解散了（车机撤销了它，例如进入自己的菜单并关闭 CarLife）- 完全重置加入流程并调用
 * [onLost]：来自车机的下一个 TARGET_INFO（它重新连接 Bluetooth）会重新加入，而不会卡在「已在加入」。
 * 可从任意线程调用，状态在主线程上。
 * 对端搜索每 [DISCOVER_RETRY_MS] 重复一次，直到加入：短停后车机建组晚于冷启动，
 * 一次搜索不够（h214，Mazda G6S）。discoverPeers 的 ERROR/BUSY 也会重试：先通过 stopPeerDiscovery，
 * 连续两次失败后 - 在新通道上。热点开启时的 BUSY - 是热点干扰了 Wi-Fi Direct（Samsung）：
 * 每次尝试只报告一次 [onBusy]。
 * 在加入前收到新 TARGET_INFO 会用新凭据重新开始尝试（h126）。Wi-Fi 关闭（此时 P2P 也关闭）：
 * 调用 [onWifi]（false）并等待 WIFI_P2P_STATE_ENABLED，然后加入。代次 [gen] 会过滤掉上一次尝试的
 * 回调和延迟步骤。
 */
class P2pJoin(
    private val ctx: Context,
    private val onBusy: () -> Unit = {},
    private val onWifi: (on: Boolean) -> Unit = {},
    private val onJoined: (local: Inet4Address) -> Unit,
    private val onLost: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val mgr: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var target: P2pTarget? = null
    /** 当前尝试开始的时间：相同的 TARGET_INFO 在其开始后的前 [JOIN_TIMEOUT_MS] 内不会重启它。 */
    private var startedAt = 0L
    private var receiver: BroadcastReceiver? = null
    /** 尝试的代次：上一次（停止或重启后）的回调和延迟步骤不会与它匹配，也不会做任何事。 */
    private var gen = 0
    /** 启动时 Wi-Fi 已关闭：等 P2P 开启后再进入。 */
    private var waitingWifi = false
    /** 我们已连接（或正在连接）：stop 时移除组。 */
    private var connecting = false
    private var peerMode = false
    /** 何时发出了按对端的 connect：超过 [CONNECT_STALE_MS] 无响应的邀请被撤销并重新搜索。 */
    private var connectAt = 0L
    private var discoverRuns = 0
    private var busyReported = false
    /** 连续失败的 discoverPeers 次数：超过 [REINIT_AFTER_FAILS] 后重建通道。 */
    private var discoverFails = 0
    /** 组已组建，等待 DHCP 分配 IPv4：邀请已不再挂起。 */
    private var formed = false
    private var joinedIp: Inet4Address? = null
    @Volatile private var iface: LanIface? = null

    /** 我们所加入 P2P 子网中的地址（用于过滤入站 TCP）。 */
    fun owns(a: InetAddress): Boolean = iface?.contains(a) == true

    fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun start(t: P2pTarget) = main.post { startMain(t) }

    fun stop(why: String) = main.post { stopMain(why) }

    @SuppressLint("MissingPermission")
    private fun startMain(t: P2pTarget) {
        if (target != null && joinedIp != null && p2pIface() == null) {
            // 已进入，但接口已不存在，且 CONNECTION_CHANGED 解散通知未到达
            WLog.ev(WLog.P2P, "lost", "how" to "нет p2p-интерфейса при новом TARGET_INFO")
            lostMain()
        }
        val cur = target
        val same = cur != null && cur.sameAs(t)
        val ageMs = System.currentTimeMillis() - startedAt
        when (P2pPolicy.onTarget(active = cur != null, joined = joinedIp != null, same = same, ageMs = ageMs, freshMs = JOIN_TIMEOUT_MS)) {
            P2pOnTarget.SKIP -> {
                if (joinedIp != null) WLog.ev(WLog.P2P, "skip", "why" to "уже в группе")
                else WLog.ev(WLog.P2P, "skip", "why" to "попытка идёт", "ageMs" to ageMs)
                return
            }
            P2pOnTarget.RESTART -> {
                WLog.ev(WLog.P2P, "restart", "why" to "новый TARGET_INFO", "peerMode" to peerMode, "waitingWifi" to waitingWifi)
                stopMain("рестарт")
            }
            P2pOnTarget.START -> {}
        }
        val m = mgr ?: return fail("нет WifiP2pManager")
        if (!hasPermission()) return fail("нет разрешения «Устройства поблизости»")
        gen++
        target = t
        startedAt = System.currentTimeMillis()
        val ch = m.initialize(ctx, Looper.getMainLooper()) { WLog.ev(WLog.P2P, "channel-lost") }
        channel = ch
        registerReceiver()
        WLog.ev(WLog.P2P, "start", "bySsid" to t.canJoinBySsid, "peerName" to (t.deviceName != null), "freq" to t.freqMhz)
        AppLog.i("Wi-Fi Direct: вхожу в группу ГУ (${if (t.canJoinBySsid) "по ssid" else "по имени пира"})")
        if (!wifiEnabled()) {
            // 没有 Wi-Fi 时 P2P 关闭：discover/connect 会返回 BUSY。不启动超时和重试，等待 Wi-Fi 开启
            waitingWifi = true
            WLog.ev(WLog.P2P, "wifi-off")
            AppLog.i("Wi-Fi Direct: Wi-Fi на телефоне выключен, жду включения")
            onWifi(false)
            return
        }
        onWifi(true)      // 新 TARGET_INFO 之前 Wi-Fi 已开启：若请求还挂着则撤销
        begin(m, ch, t)
    }

    private fun wifiEnabled(): Boolean = try {
        ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled != false
    } catch (_: SecurityException) {
        true
    }

    private fun begin(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
        if (t.canJoinBySsid) joinBySsid(m, ch, t) else discoverPeers(m, ch)
        main.postDelayed(timeout, JOIN_TIMEOUT_MS)
        main.postDelayed(retry, DISCOVER_RETRY_MS)
    }

    /** 等待期间 Wi-Fi 已开启：P2P 已就绪，进入组。 */
    private fun onP2pState(enabled: Boolean) {
        val m = mgr ?: return
        val ch = channel ?: return
        val t = target ?: return
        if (!enabled || !waitingWifi) return
        waitingWifi = false
        WLog.ev(WLog.P2P, "wifi-on")
        AppLog.i("Wi-Fi Direct: Wi-Fi включён, вхожу в группу ГУ")
        onWifi(true)
        begin(m, ch, t)
    }

    private val retry = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val m = mgr ?: return
            val ch = channel ?: return
            if (target == null || joinedIp != null) return
            if (peerMode) {
                if (connecting && !formed && System.currentTimeMillis() - connectAt > CONNECT_STALE_MS) {
                    WLog.ev(WLog.P2P, "connect-stale", "ms" to CONNECT_STALE_MS)
                    m.cancelConnect(ch, null)
                    connecting = false
                }
                if (!connecting) when {
                    // 无热点的 ERROR/BUSY（h214，Samsung）：框架卡住，新通道有效，如同手动重启
                    discoverFails >= REINIT_AFTER_FAILS -> {
                        WLog.ev(WLog.P2P, "reinit", "fails" to discoverFails)
                        ch.close()
                        val fresh = m.initialize(ctx, Looper.getMainLooper()) { WLog.ev(WLog.P2P, "channel-lost") }
                        channel = fresh
                        discoverFails = 0
                        discoverPeers(m, fresh)
                    }
                    discoverFails > 0 -> m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = again()
                        override fun onFailure(reason: Int) = again()
                        private val g = gen
                        private fun again() { if (g == gen && channel === ch && target != null && joinedIp == null && !connecting) discoverPeers(m, ch) }
                    })
                    else -> discoverPeers(m, ch)
                }
            }
            main.postDelayed(this, DISCOVER_RETRY_MS)
        }
    }

    private val timeout = Runnable {
        if (target != null && joinedIp == null) {
            WLog.ev(WLog.P2P, "timeout", "ms" to JOIN_TIMEOUT_MS, "peerMode" to peerMode)
            AppLog.i("Wi-Fi Direct: за ${JOIN_TIMEOUT_MS / 1000} с в группу ГУ не вошли")
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinBySsid(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        val cfg = try {
            val b = WifiP2pConfig.Builder().setNetworkName(t.ssid!!).setPassphrase(t.passphrase!!)
            if (t.freqMhz != null) b.setGroupOperatingFrequency(t.freqMhz) else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            b.build()
        } catch (e: RuntimeException) {
            WLog.ev(WLog.P2P, "ssid-config-fail", "err" to e.javaClass.simpleName)
            return discoverPeers(m, ch)
        }
        val g = gen
        connecting = true
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g == gen) WLog.ev(WLog.P2P, "connect", "mode" to "ssid", "res" to "invited")
            }
            override fun onFailure(reason: Int) {
                if (g != gen) return
                WLog.ev(WLog.P2P, "connect", "mode" to "ssid", "res" to "fail", "reason" to reasonName(reason))
                connecting = false
                if (target != null) discoverPeers(m, ch)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (target?.deviceName == null) return fail("нет имени пира ГУ")
        peerMode = true
        val n = ++discoverRuns
        val g = gen
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g != gen) return
                discoverFails = 0
                WLog.ev(WLog.P2P, "discover", "res" to "ok", "n" to n)
            }
            override fun onFailure(reason: Int) {
                if (g != gen) return
                discoverFails++
                val hotspot = hotspotUp()
                WLog.ev(WLog.P2P, "discover", "res" to "fail", "reason" to reasonName(reason), "n" to n, "hotspot" to hotspot)
                // BUSY 也可能在没有热点时发生（h214）：那就直接重试，不显示提示
                if (reason == WifiP2pManager.BUSY && hotspot && !busyReported && target != null) {
                    busyReported = true
                    AppLog.i("Wi-Fi Direct занят (BUSY): включена точка доступа на телефоне")
                    onBusy()
                }
            }
        })
        // 第一批对端可能在新搜索之前就已到达：检查已知的列表
        if (n > 1) onPeers()
    }

    @SuppressLint("MissingPermission")
    private fun onPeers() {
        val m = mgr ?: return
        val ch = channel ?: return
        val name = target?.deviceName ?: return
        if (!peerMode || connecting || joinedIp != null) return
        val g = gen
        m.requestPeers(ch) { list ->
            if (g != gen) return@requestPeers
            val dev: WifiP2pDevice = list.deviceList.firstOrNull { it.deviceName == name } ?: return@requestPeers
            if (connecting || target == null) return@requestPeers
            connecting = true
            connectAt = System.currentTimeMillis()
            WLog.ev(WLog.P2P, "peer", "matched" to true, "status" to dev.status)
            val cfg = WifiP2pConfig().apply {
                deviceAddress = dev.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (g == gen) WLog.ev(WLog.P2P, "connect", "mode" to "peer", "res" to "invited")
                }
                override fun onFailure(reason: Int) {
                    if (g != gen) return
                    WLog.ev(WLog.P2P, "connect", "mode" to "peer", "res" to "fail", "reason" to reasonName(reason))
                    connecting = false
                }
            })
        }
    }

    private fun onConnectionChanged() {
        val m = mgr ?: return
        val ch = channel ?: return
        val g = gen
        m.requestConnectionInfo(ch) { info ->
            if (g != gen || target == null) return@requestConnectionInfo
            if (info == null || !info.groupFormed) {
                formed = false
                if (joinedIp != null) {
                    WLog.ev(WLog.P2P, "lost")
                    lostMain()
                }
                return@requestConnectionInfo
            }
            formed = true
            if (info.isGroupOwner) {
                WLog.ev(WLog.P2P, "formed", "owner" to "phone", "note" to "ждали ГУ владельцем")
            }
            awaitIp(g, 0)
        }
    }

    /** p2p 接口上的 IPv4 在 DHCP 之后出现，有时在 groupFormed 之后一两秒。 */
    private fun awaitIp(g: Int, attempt: Int) {
        if (g != gen || target == null || joinedIp != null) return
        val lan = p2pIface()
        if (lan == null) {
            if (attempt < IP_ATTEMPTS) main.postDelayed({ awaitIp(g, attempt + 1) }, IP_RETRY_MS)
            else WLog.ev(WLog.P2P, "no-ip", "waitedMs" to IP_ATTEMPTS * IP_RETRY_MS)
            return
        }
        iface = lan
        joinedIp = lan.ip
        main.removeCallbacks(timeout)
        WLog.ev(WLog.P2P, "joined", "iface" to lan.toString())
        AppLog.i("Wi-Fi Direct: вошли в группу ГУ, адрес телефона ${lan.ip.hostAddress}")
        onJoined(lan.ip)
    }

    /** 手机热点已开启：Samsung 为 swlan*，其他为 ap*，带 IPv4。 */
    private fun hotspotUp(): Boolean = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
        (ni.name.startsWith("swlan") || ni.name.startsWith("ap")) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
    }

    private fun p2pIface(): LanIface? {
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.name.startsWith("p2p") || !ni.isUp) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val b = ia.broadcast as? Inet4Address ?: a
                return LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
            }
        }
        return null
    }

    private fun registerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onP2pState(
                        i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    )
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun lostMain() {
        AppLog.i("Wi-Fi Direct: группа ГУ распалась, жду от ГУ новый TARGET_INFO")
        stopMain("группа распалась")
        onLost()
    }

    private fun stopMain(why: String) {
        gen++
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        receiver = null
        val m = mgr
        val ch = channel
        if (m != null && ch != null) {
            if (peerMode) m.stopPeerDiscovery(ch, null)
            if (connecting || joinedIp != null) m.removeGroup(ch, null)
            ch.close()
        }
        if (target != null) WLog.ev(WLog.P2P, "stop", "why" to why, "joined" to (joinedIp != null))
        channel = null
        target = null
        connecting = false
        peerMode = false
        waitingWifi = false
        discoverRuns = 0
        busyReported = false
        discoverFails = 0
        formed = false
        joinedIp = null
        iface = null
    }

    private fun fail(why: String) {
        WLog.ev(WLog.P2P, "off", "why" to why)
        AppLog.i("Wi-Fi Direct: $why")
    }

    private fun reasonName(r: Int) = when (r) {
        WifiP2pManager.ERROR -> "error"
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no_service_requests"
        else -> r.toString()
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000L
        const val DISCOVER_RETRY_MS = 15_000L
        const val CONNECT_STALE_MS = 20_000L
        const val REINIT_AFTER_FAILS = 2
        const val IP_RETRY_MS = 500L
        const val IP_ATTEMPTS = 30
    }
}
