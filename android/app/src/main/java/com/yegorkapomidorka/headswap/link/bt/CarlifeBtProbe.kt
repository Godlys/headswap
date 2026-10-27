package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.IOException
import java.util.UUID

/**
 * 分支 A（Bluetooth + Wi-Fi Direct）：与百度 MD 相同的 `carlife` RFCOMM 服务器（BluetoothClient:28-29,76,119）。
 * 如果车机支持通过 Wi-Fi Direct 的无线 CarLife，它会自行连接过来。交互流程：WIRELESS_INFO_REQUEST、
 * TARGET_INFO_REQUEST，手机依据 TARGET_INFO 中的凭据加入车机的 P2P 组（[P2pJoin]），收到 IP_REQUEST
 * 时以 MD_WIFI_IP 回复组内地址。之后车机向 Tcp6Listener 建立 TCP 通道（放行 [ownsPeer]）。
 * 等待车辆和会话进行期间保持 Bluetooth 连接。不向日志写入机密信息（见 BtSummary）。
 */
class CarlifeBtProbe(
    private val ctx: Context,
    /** 车机已连接 RFCOMM（线程 bt-accept-*）。 */
    private val onAccept: () -> Unit = {},
    /** 车机发来 Wi-Fi Direct 凭据，mac 为其 Bluetooth 地址（线程 bt-accept-*）。 */
    private val onTargetInfo: (mac: String?) -> Unit = {},
    /** Wi-Fi Direct 正忙（通常是手机热点已开启），主线程。 */
    private val onP2pBusy: () -> Unit = {},
    /** 进入车机组时手机 Wi-Fi 已关闭（false，Wi-Fi Direct 无法加入）或已开启（true）（主线程）。 */
    private val onWifi: (on: Boolean) -> Unit = {},
) {
    @Volatile private var running = false
    private val servers = ArrayList<BluetoothServerSocket>()
    private val lock = Any()
    @Volatile private var accepts = 0
    @Volatile private var active: BluetoothSocket? = null
    private val p2p = P2pJoin(ctx, onBusy = { onP2pBusy() }, onWifi = { on -> onWifi(on) }, onJoined = { ip -> onP2pJoined(ip) }, onLost = { onP2pLost() })
    /** 手机在车机组中的地址（加入后）及车机的 IP 请求：两者都具备时才发送 MD_WIFI_IP。 */
    @Volatile private var p2pIp: String? = null
    @Volatile private var ipRequested = false
    private val txLock = Any()
    private val main = Handler(Looper.getMainLooper())
    /** 车机最后一次发来的凭据，即使因"已在组中"而被跳过：组解散后据此重新加入（h211）。 */
    @Volatile private var lastTarget: P2pTarget? = null
    /** 组解散后的重连代数：新的 BT 会话、加入组或停止都会使其失效。 */
    @Volatile private var rejoinGen = 0
    @Volatile private var rejoining = false
    private var adapterReceiver: BroadcastReceiver? = null

    /** 来自车机 P2P 组的入站 TCP：与信标子网同等对待，放行至 Tcp6Listener。 */
    fun ownsPeer(a: java.net.InetAddress): Boolean = p2p.owns(a)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        p2pIp = null
        ipRequested = false
        lastTarget = null
        rejoinGen++
        rejoining = false
        accepts = 0     // 本次启动：stop 行中 accepts= 统计自上次 start 以来的车机连接数
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return fail("нет адаптера")
            !hasPermission() -> return fail("нет разрешения BLUETOOTH_CONNECT")
            !adapter.isEnabled -> return fail("Bluetooth выключен")
        }
        for ((tag, uuid) in UUIDS) {
            val ss = try {
                adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid)
            } catch (e: Exception) {
                WLog.ev(WLog.BT, "listen-fail", "uuid" to tag, "err" to e.message)
                continue
            }
            synchronized(lock) { servers += ss }
            WLog.ev(WLog.BT, "listen", "uuid" to tag, "name" to SERVICE_NAME)
            Thread({ acceptLoop(tag, ss) }, "bt-accept-$tag").apply { isDaemon = true }.start()
        }
        CarLifeState.btProbe = if (synchronized(lock) { servers.isEmpty() }) "не слушает" else "слушаю carlife, ГУ не подключалась"
        watchAdapter()
    }

    /**
     * 空等：没有来自车机的 RFCOMM，也没有加入组，或监听器已死亡。h211：车机在邀请后立即关闭了 RFCOMM
     * 并在约 5 秒内恢复了 ACL；旧监听器和针对失效目标的 P2P 持续了数分钟，
     * 而车机直到干净重启后才打开 SPP。
     */
    fun idle(): Boolean = active == null && p2pIp == null || synchronized(lock) { running && servers.isEmpty() }

    /** stop + start：更新 SDP 记录，取消针对旧目标的 P2P。 */
    fun restart(why: String) {
        stop(why)
        start()
    }

    /** 将手机 Bluetooth 状态写入日志：否则仅凭日志无法判断是谁关闭了 BT（h211）。 */
    private fun watchAdapter() {
        if (adapterReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
                    BluetoothAdapter.STATE_ON -> "on"
                    BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
                    else -> "?"
                }
                WLog.ev(WLog.BT, "adapter", "state" to st)
            }
        }
        adapterReceiver = r
        ctx.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    fun stop(why: String) {
        val list = synchronized(lock) {
            if (!running) return
            running = false
            servers.toList().also { servers.clear() }
        }
        for (s in list) try { s.close() } catch (_: IOException) {}
        adapterReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        adapterReceiver = null
        rejoinGen++
        rejoining = false
        active?.let { try { it.close() } catch (_: IOException) {} }
        p2p.stop(why)
        WLog.ev(WLog.BT, "stop", "why" to why, "accepts" to accepts)
    }

    private fun fail(why: String) {
        running = false
        CarLifeState.btProbe = "не слушает: $why"
        WLog.ev(WLog.BT, "off", "why" to why)
    }

    private fun acceptLoop(tag: String, ss: BluetoothServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                // 不是 stop()：监听器自行死亡（手机的 BT 被关闭）；restart 会在下一次 ACL 时重新拉起
                if (running) {
                    synchronized(lock) { servers.remove(ss) }
                    WLog.ev(WLog.BT, "listen-dead", "uuid" to tag, "err" to e.message)
                }
                return
            }
            accepts++
            onAccept()
            talk(tag, s)
        }
    }

    @SuppressLint("MissingPermission")
    private fun talk(tag: String, s: BluetoothSocket) {
        val dev = s.remoteDevice
        val name = try { dev?.name } catch (_: SecurityException) { null }
        WLog.ev(WLog.BT, "accept", "uuid" to tag, "dev" to WLog.maskMac(dev?.address), "devName" to name)
        AppLog.i("BT carlife: ГУ подключилась по Bluetooth ($tag, ${name ?: "?"})")
        CarLifeState.btProbe = "ГУ подключилась ($tag, ${name ?: "?"})"
        val t0 = SystemClock.elapsedRealtime()
        val parser = BtFrameParser()
        var why = "?"
        var targetAsked = false
        active?.let { old -> try { old.close() } catch (_: IOException) {} }      // 车机重新连接：关闭旧通道
        active = s
        ipRequested = false
        rejoinGen++      // 车机重连了 BT：TARGET_INFO 会通过正常交互到来，取消我们自己的请求
        rejoining = false
        try {
            val inp = s.inputStream
            Thread.sleep(300)
            send(s, BtMsg.MD_WIRELESS_INFO_REQUEST)
            val buf = ByteArray(1024)
            while (running) {
                val n = inp.read(buf)
                if (n < 0) { why = "EOF"; break }
                for (f in parser.feed(buf, n)) {
                    val kv: List<Pair<String, Any?>> = when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> BtSummary.wirelessInfo(f.body)
                        BtMsg.HU_TARGET_INFO -> BtSummary.targetInfo(f.body)
                        BtMsg.HU_STATUS -> BtSummary.status(f.body)
                        else -> listOf("shape" to BtSummary.shape(f.body))
                    }
                    WLog.ev(WLog.BT, "rx", "type" to BtMsg.name(f.type), "len" to f.body.size, *kv.toTypedArray())
                    when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> {
                            CarLifeState.btProbe = "ГУ ответила: ${kv.joinToString(" ") { "${it.first}=${it.second}" }}"
                            if (!targetAsked) {
                                targetAsked = true
                                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
                            }
                        }
                        BtMsg.HU_TARGET_INFO -> {
                            CarLifeState.btProbe = "ГУ прислала реквизиты Wi-Fi Direct, вхожу в группу"
                            val t = P2pTarget.parse(f.body)
                            if (t == null) WLog.ev(WLog.P2P, "off", "why" to "TARGET_INFO не разобран")
                            else {
                                lastTarget = t
                                onTargetInfo(dev?.address)
                                p2p.start(t)
                            }
                        }
                        BtMsg.HU_IP_REQUEST -> {
                            ipRequested = true
                            CarLifeState.btProbe = "ГУ просит IP телефона" + if (p2pIp == null) ", жду вход в группу" else ""
                            sendIpIfReady()
                        }
                    }
                }
            }
            if (why == "?") why = "остановлено"
        } catch (e: Exception) {
            why = "ошибка: ${e.message}"
        } finally {
            if (active === s) active = null
            try { s.close() } catch (_: IOException) {}
            WLog.ev(WLog.BT, "close", "why" to why, "aliveMs" to SystemClock.elapsedRealtime() - t0, "skipped" to parser.skipped)
        }
    }

    private fun send(s: BluetoothSocket, type: Int, body: ByteArray? = null) {
        synchronized(txLock) {
            s.outputStream.write(BtMsg.frame(type, body))
            s.outputStream.flush()
        }
        WLog.ev(WLog.BT, "tx", "type" to BtMsg.name(type), "len" to (body?.size ?: 0))
    }

    private fun onP2pJoined(ip: java.net.Inet4Address) {
        p2pIp = ip.hostAddress
        CarLifeState.btProbe = "телефон в группе ГУ (${ip.hostAddress})" + if (ipRequested) "" else ", ГУ ещё не просила IP"
        if (rejoining) WLog.ev(WLog.P2P, "rejoin-joined", "ipRequested" to ipRequested)
        rejoining = false
        rejoinGen++
        // 与 Baidu 相同（BluetoothWirlessHelper:65-69, 289-291），MD_WIFI_IP 仅在收到 IP_REQUEST 时发送：没有请求则不发送
        sendIpIfReady()
    }

    /**
     * 车机组已解散（主线程）。如果 Bluetooth 也掉了，车机会重新连接并自行发送 TARGET_INFO。
     * 如果 BT 仍存活（h211：Mazda G6S 挂倒挡时移除 Wi-Fi Direct 但保持 RFCOMM），在车辆休眠前不会收到新的
     * TARGET_INFO：我们按最后一次的凭据重新加入，并自行请求 TARGET_INFO，直到成功加入为止。
     */
    private fun onP2pLost() {
        p2pIp = null
        val s = active
        if (!running || s == null) return
        rejoining = true
        val g = ++rejoinGen
        WLog.ev(WLog.P2P, "rejoin-arm", "cached" to (lastTarget != null))
        AppLog.i("Wi-Fi Direct: группа ГУ распалась, Bluetooth жив - сама прошу у ГУ реквизиты")
        scheduleRejoin(g, s, 0)
    }

    private fun scheduleRejoin(g: Int, s: BluetoothSocket, attempt: Int) {
        val d = P2pPolicy.rejoinDelayMs(attempt)
        if (d == null) {
            WLog.ev(WLog.P2P, "rejoin-give-up", "attempts" to attempt)
            AppLog.i("Wi-Fi Direct: ГУ не подняла группу за $attempt запросов, жду переподключения Bluetooth")
            return
        }
        main.postDelayed({ rejoinStep(g, s, attempt) }, d)
    }

    private fun rejoinStep(g: Int, s: BluetoothSocket, attempt: Int) {
        if (g != rejoinGen || !running || active !== s || p2pIp != null) return
        if (P2pPolicy.rejoinDropBt(attempt)) return dropBt(s, attempt)
        if (attempt == 0) lastTarget?.let {
            WLog.ev(WLog.P2P, "rejoin-cached")
            p2p.start(it)
        }
        Thread({
            try {
                WLog.ev(WLog.P2P, "rejoin-ask", "n" to attempt + 1)
                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
            } catch (e: IOException) {
                WLog.ev(WLog.BT, "tx-fail", "type" to "TARGET_INFO_REQUEST", "err" to e.message)
            }
        }, "bt-rejoin").apply { isDaemon = true }.start()
        scheduleRejoin(g, s, attempt + 1)
    }

    /**
     * 车机回复了 TARGET_INFO 却没有建立组（h211、h185）：只关闭活动 socket，监听器保留。
     * accept 循环接受车机的重连，TARGET_INFO 通过正常交互到来。取消加入尝试：否则同一份 TARGET_INFO 在重连后
     * 会被 SKIP"尝试正在进行中"。此处不能用 [stop]：它会中断监听器。
     */
    private fun dropBt(s: BluetoothSocket, attempts: Int) {
        rejoining = false
        rejoinGen++
        WLog.ev(WLog.P2P, "rejoin-drop-bt", "attempts" to attempts)
        AppLog.i("Wi-Fi Direct: ГУ не подняла группу за $attempts запросов, рву Bluetooth - жду, пока она переподключится")
        CarLifeState.btProbe = "Bluetooth сброшен, жду переподключения ГУ"
        p2p.stop("сброс BT")
        try { s.close() } catch (_: IOException) {}
    }

    /** 在收到 IP_REQUEST 并加入组后发送 MD_WIFI_IP；对同一地址的重复请求会再次应答。 */
    private fun sendIpIfReady() {
        val ip = p2pIp ?: return
        val s = active ?: return
        if (!ipRequested) return
        try {
            send(s, BtMsg.MD_WIFI_IP, BtMsg.wifiIp(ip))
            ipRequested = false
            CarLifeState.btProbe = "отдал ГУ IP $ip, жду TCP от ГУ"
            AppLog.i("BT carlife: отдал ГУ адрес телефона $ip")
        } catch (e: IOException) {
            WLog.ev(WLog.BT, "tx-fail", "type" to "WIFI_IP", "err" to e.message)
        }
    }

    companion object {
        const val SERVICE_NAME = "carlife"
        val UUIDS = listOf(
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "carlife" to UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )
    }
}
