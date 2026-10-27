package com.yegorkapomidorka.headswap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.EqLive
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneCall
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.LinkSetupException
import com.yegorkapomidorka.headswap.link.StreamTransport
import com.yegorkapomidorka.headswap.link.Tcp6Listener
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.bt.CarBtState
import com.yegorkapomidorka.headswap.link.bt.CarlifeBtProbe
import com.yegorkapomidorka.headswap.link.car.AutoOffDebounce
import com.yegorkapomidorka.headswap.link.car.BT_HINT_MS
import com.yegorkapomidorka.headswap.link.car.BtReconnectHint
import com.yegorkapomidorka.headswap.link.car.CAR_NET_DELAY_MS
import com.yegorkapomidorka.headswap.link.car.CAR_NET_TIMEOUT_MS
import com.yegorkapomidorka.headswap.link.car.CarConnMode
import com.yegorkapomidorka.headswap.link.car.CarMac
import com.yegorkapomidorka.headswap.link.car.CarNetEvent
import com.yegorkapomidorka.headswap.link.car.CarNetPolicy
import com.yegorkapomidorka.headswap.link.car.CarNetState
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.HotspotBeacon
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import com.yegorkapomidorka.headswap.ui.HeadswapNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * 前台服务（connectedDevice）：持有传输层（USB 配件或 debug-TCP）和与车机的会话。
 * 桥接的 AA 侧（BridgeHost）存活于整个服务生命周期，而非单次 USB 会话：USB 断开会关闭
 * CarLife 会话，AA 保持存活 BridgeHost.GRACE_MS 并被下一次 USB 会话接管。
 * 服务的所有状态（session、host、stopRequested）只由主线程访问。
 */
class BridgeService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: CarLifeSession? = null
    private var host: BridgeHost? = null
    private var stopRequested = false
    /** debug tcp6：监听器等待 6/6（debug-tcp6 线程）；停止会取消它。 */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    /** tcp6/无线正在建立中（从启动到 6/6 或失败）；此时不关闭服务。仅主线程。 */
    private var linkWaiting = false
    /** 用户已启用无线（测试版，分支 B）：断开后再次等待车辆。主线程写入，连接器也读取。 */
    @Volatile private var wifiBMode = false
    /**
     * 自动模式：选定的车辆通过蓝牙在附近（ACL_CONNECTED），只要它在附近就保持 Wi-Fi 等待。
     * 主线程写入，状态回调读取。镜像到 CarLifeState.carAuto 用于报告。
     */
    @Volatile private var autoMode = false
        set(v) {
            field = v
            CarLifeState.carAuto = v
        }
    /** 等待由自动模式启动/接管：车辆离开后不再恢复。仅主线程。 */
    private var autoStarted = false
    /** 车辆在附近时 USB 会话结束：经过 [USB_TO_AIR_MS] 后开始通过无线等待。仅主线程。 */
    private var usbToAirPending = false
    private val usbToAirTick = Runnable { usbToAir() }
    private val autoOff = AutoOffDebounce(AUTO_OFF_DEBOUNCE_MS) { SystemClock.elapsedRealtime() }
    private val autoOffTick = Runnable { if (autoOff.fire()) applyAutoOff() }
    /** "将手机连接到车辆 Wi-Fi"的提示已显示（每次进入自动模式一次）。 */
    private var hintShown = false
    private val hintTick = Runnable { showWifiHint() }
    /** 加入车辆 Wi-Fi（WifiNetworkSpecifier）；在首次请求时创建：没有车辆网络时（以及在 USB 上时）完全不存在。 */
    private var carNetImpl: CarNetwork? = null
    private val carNet: CarNetwork
        get() = carNetImpl ?: CarNetwork(this) { CarLifeState.carNet = it.label }.also { carNetImpl = it }
    private val carNetBusy: Boolean
        get() = carNetImpl?.state.let { it == CarNetState.REQUESTING || it == CarNetState.AVAILABLE }
    /** Wi-Fi 等待的开始（自动或手动）：从此时起计算 [CAR_NET_DELAY_MS]。仅主线程。 */
    private var waitSince = 0L
    /** 车辆最后一次在线的时间（USB/无线会话、车辆网络）；0 = 从未在线。仅主线程。 */
    private var carSeenAt = 0L
    /** 在本次等待中，选定的车辆已通过蓝牙连接。仅主线程。 */
    private var carBtNear = false
    /** 已收到 6/6，会话即将开始：不再需要请求车辆网络。仅主线程。 */
    private var sixReady = false
    /** 等待被有意中断，以便在车辆网络中重新发送信标：startTcp6 中的失败分支不算放弃。仅主线程。 */
    private var restartingForCarNet = false
    private val carNetTick = Runnable { requestCarNet() }
    /** 在本次等待中，车机通过蓝牙发来 TARGET_INFO：Wi-Fi Direct，无需车辆网络。仅主线程。 */
    private var p2pThisWait = false
    /** Wi-Fi Direct 车辆（本次等待或配置文件中有 TARGET_INFO）：不请求车辆网络，不显示 Wi-Fi 提示。 */
    private val wifiDirectCar: Boolean
        get() = p2pThisWait || CarProfile(this).connMode == CarConnMode.BT_CARLIFE
    /** 本次等待中已记录 `car-skip`。仅主线程。 */
    private var carSkipLogged = false
    /** 本次等待中车机连接 RFCOMM `carlife` 的次数，以及是否已显示"重新连接蓝牙"提示。仅主线程。 */
    private var btAcceptsThisWait = 0
    private var btHintShown = false
    /** "请开启 Wi-Fi"通知正在显示（Wi-Fi Direct 因 Wi-Fi 关闭而受阻）。仅主线程。 */
    private var wifiOffShown = false
    private val btHintTick = Runnable { checkBtHint() }
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** 分支 A，诊断：等待车辆 Wi-Fi 期间监听 RFCOMM `carlife`。将回调从蓝牙线程切到主线程。 */
    private val btProbe by lazy {
        CarlifeBtProbe(
            this,
            onAccept = { main.post { onBtAccept() } },
            onTargetInfo = { mac -> main.post { onBtTargetInfo(mac) } },
            onP2pBusy = { main.post { onP2pBusy() } },
            onWifi = { on -> main.post { onP2pWifi(on) } },
        )
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                // USB 配件断开不影响无线会话
                session?.takeIf { it.transportKind == TransportCaps.USB_AOA.kind }?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        AppLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 要求在 5 秒内调用 startForeground，立即执行
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        startForegroundTyped(n)

        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            wifiBMode = false
            autoMode = false      // 直到下一次 ACL_CONNECTED
            autoStarted = false
            cancelAutoOff()
            cancelHint()
            stopCarNet(CarNetEvent.STOP, "stop")
            CarLifeState.wireless = null
            btProbe.stop("стоп")
            pendingTcp6?.abort()
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed 会看到 stopRequested：AA 立即关闭
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return START_NOT_STICKY
        }
        // 根据车辆蓝牙自动启动：在检查 session 之前处理，会话进行中时只需要标志
        if (intent?.action == ACTION_WIFI_B_AUTO) {
            onCarAuto()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WIFI_B_AUTO_OFF) {
            onCarAutoOff()
            return START_NOT_STICKY
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            return START_NOT_STICKY
        }

        // 仅 debug 构建：代替 USB 配件，通过 TCP 连接电脑上的车机模拟器（adb reverse），release 中 port() 始终为 null
        val dbgPort = DebugTransport.port(intent)
        if (dbgPort != null) {
            AppLog.i("сервис: debug-транспорт, подключаюсь к 127.0.0.1:$dbgPort")
            Thread({
                val pfd = DebugTransport.connect(dbgPort)
                main.post {
                    if (pfd == null) {
                        CarLifeState.status = "debug TCP: нет соединения"
                        failStart()
                    } else if (session != null) {
                        pfd.close()
                    } else {
                        startSession(StreamTransport.fromPfd(pfd, TransportCaps.TCP_DEBUG, "TCP 127.0.0.1:$dbgPort"))
                    }
                }
            }, "debug-tcp").start()
            return START_NOT_STICKY
        }

        // 仅 debug 构建：无线格式，127.0.0.1 上的 6 个 TCP 套接字（adb forward），车机自行连接
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return START_NOT_STICKY
        }
        // 无线，分支 B（手机热点 + 信标）：测试版构建设置中的按钮或 debug 入口
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (!BuildConfig.WIRELESS_BETA && !DebugTransport.wifiB(intent)) {
                AppLog.i("сервис: беспровод в этой сборке выключен")
                failStart()
                return START_NOT_STICKY
            }
            if (wifiBMode && linkWaiting && !stopRequested) {
                // 重复点击，等待已在进行：不重置 accept/TARGET_INFO 计数器（否则会误报 bt-hint，h126）
                autoStarted = false
                WLog.ev(WLog.TRIGGER, "manual-mark", "why" to "waiting")
                return START_NOT_STICKY
            }
            wifiBMode = true
            autoStarted = false      // 手动启动：车辆蓝牙断开不会将其关闭
            stopRequested = false
            beginWait()
            startWifiB()
            btProbe.start()      // 在 startWifiB 的 WLog.begin 之后：WL bt 行中的 t= 从尝试开始时起算
            return START_NOT_STICKY
        }

        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return START_NOT_STICKY
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return START_NOT_STICKY
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            AppLog.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            AppLog.i("сервис: openAccessory вернул null")
            CarLifeState.status = "openAccessory: ошибка"
            failStart()
            return START_NOT_STICKY
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        if (wifiBMode || pendingTcp6 != null) {
            // 有线优先于无线：取消按 Wi-Fi 等待车辆；USB 会话结束后，仅当
            // 车辆在蓝牙附近且没有线缆时才恢复（usbToAir）
            AppLog.i("сервис: кабель подключён, ожидание по Wi-Fi отменяю (USB побеждает)")
            WLog.ev(WLog.LINK, "usb-wins")
            wifiBMode = false
            autoStarted = false
            cancelHint()
            stopCarNet(CarNetEvent.USB_WINS, "usb")
            CarLifeState.wireless = null
            btProbe.stop("кабель")
            pendingTcp6?.abort()
        }
        startSession(StreamTransport.fromPfd(pfd, TransportCaps.USB_AOA, MainActivity.describe(acc)))
        return START_NOT_STICKY
    }

    /** debug tcp6：在 loopback 上监听 6 个端口，等待车机（pc-headunit --transport tcp6），然后进入普通会话。 */
    private fun startTcp6Debug(bind: String) = startTcp6("tcp6", bind, TCP6_WAIT_MS, beacon = null)

    /**
     * 分支 B（手机热点）：7999 上的「CarlifeHost」信标和 8999 应答器，6 个端口绑定 0.0.0.0，只接收
     * 信标所发往的子网地址。热点由用户手动开启。目前仅限 debug 入口（`--ez debug_wifi_b true`）。
     */
    private fun startWifiB() {
        CarLifeState.wireless = waitingText("…")
        // 手机已在车辆网络中（WifiNetworkSpecifier）：信标和应答器只在该网络中，套接字绑定到该网络
        val carIface = carNetImpl?.ifaceName
        val carNetwork = if (carIface != null) carNetImpl?.current else null
        val beacon = HotspotBeacon(
            ifaces = { LanIface.scan().let { all -> if (carNetwork != null) all.filter { it.name == carIface } else all } },
            bindNetwork = carNetwork,
            onIfaces = { list ->
                // Wi-Fi Direct 车辆不需要热点，而开启的热点会占用 Wi-Fi Direct（h214）：不要求开启
                CarLifeState.wireless = if (list.isEmpty()) {
                    if (wifiDirectCar) waitingText("Wi-Fi Direct") else getString(R.string.wl_state_no_hotspot)
                }
                else waitingText(list.joinToString(", ") { it.name })
            },
        )
        startTcp6("wifi-b", "0.0.0.0", WIFI_B_WAIT_MS, beacon)
        if (linkWaiting) WLog.ev(WLog.LINK, "car-wifi", "carWifi" to if (CarWifi(this).isSet) "set" else "unset", "bound" to (carNetwork != null))
        if (autoMode && !hintShown && linkWaiting) {
            main.removeCallbacks(hintTick)
            main.postDelayed(hintTick, AUTO_HINT_MS)
        }
        if (linkWaiting) scheduleCarNet()
    }

    /** 新的 Wi-Fi 等待（自动或手动）：重置计数器和蓝牙提示定时器。 */
    private fun beginWait() {
        waitSince = SystemClock.elapsedRealtime()
        carBtNear = false
        p2pThisWait = false
        carSkipLogged = false
        btAcceptsThisWait = 0
        btHintShown = false
        main.removeCallbacks(btHintTick)
        main.postDelayed(btHintTick, BT_HINT_MS)
    }

    private fun markCarSeen() {
        carSeenAt = SystemClock.elapsedRealtime()
    }

    private fun sinceCarMs(): Long? = if (carSeenAt == 0L) null else SystemClock.elapsedRealtime() - carSeenAt

    /**
     * 请求车辆网络：从等待开始经过 [CAR_NET_DELAY_MS] 后（如果已设置网络），而如果车辆刚刚
     * 在线 - 则立即（[CarNetPolicy.delayMs]）。Wi-Fi Direct 车辆不需要。
     */
    private fun scheduleCarNet() {
        main.removeCallbacks(carNetTick)
        if (!CarWifi(this).isSet) return
        if (wifiDirectCar) {
            logCarSkip()
            return
        }
        val since = sinceCarMs()
        val proven = CarWifi(this).proven
        val delay = CarNetPolicy.delayMs(since, carBtNear, proven)
        if (delay == 0L) WLog.ev(WLog.LINK, "car-net-now", "sinceCarMs" to since, "carBt" to carBtNear, "proven" to proven)
        main.postDelayed(carNetTick, (delay - (SystemClock.elapsedRealtime() - waitSince)).coerceAtLeast(0))
        if (delay > 0L) checkCarBtNear()
    }

    /** 选定的车辆已通过蓝牙连接：它在附近，立即请求车辆网络。没有选定车辆时不检查（耳机）。 */
    private fun checkCarBtNear() {
        val mac = CarProfile(this).btMac ?: return
        CarBtState.check(this, mac) { near ->
            if (!near || carBtNear || !wifiBMode || stopRequested || !linkWaiting || session != null) return@check
            carBtNear = true
            scheduleCarNet()
        }
    }

    private fun requestCarNet() {
        val w = CarWifi(this)
        val ssid = w.ssid ?: return
        val ok = CarNetPolicy.shouldRequest(
            carWifiSet = w.isSet,
            autoOrManualWaiting = wifiBMode && linkWaiting && !stopRequested,
            sessionActive = session != null,
            usbActive = session?.transportKind == TransportCaps.USB_AOA.kind,
            alreadyRequesting = carNetBusy,
            waitedMs = SystemClock.elapsedRealtime() - waitSince,
            sixOfSixReady = sixReady,
            wifiDirectCar = wifiDirectCar,
            delayMs = CarNetPolicy.delayMs(sinceCarMs(), carBtNear, w.proven),
        )
        if (!ok) return
        carNet.request(ssid, w.pass, CAR_NET_TIMEOUT_MS, ::onCarNetAvailable, ::onCarNetUnavailable, ::onCarNetLost)
    }

    /** 停止等待（或服务销毁）：移除延迟请求，并按 [CarNetPolicy] 处理网络请求本身。 */
    private fun stopCarNet(event: CarNetEvent, why: String) {
        main.removeCallbacks(carNetTick)
        restartingForCarNet = false
        if (CarNetPolicy.shouldRelease(event)) carNetImpl?.release(why)
    }

    private fun logCarSkip() {
        if (carSkipLogged) return
        carSkipLogged = true
        WLog.ev(WLog.LINK, "car-skip", "why" to "wifi-direct")
    }

    /** 车机发来 TARGET_INFO（Wi-Fi Direct）：不需要车辆网络，也不需要 Wi-Fi 提示。 */
    private fun onBtTargetInfo(mac: String?) {
        if (!wifiBMode || stopRequested) return
        val profile = CarProfile(this)
        // 已选择车辆且正是它：永久记住；未选择或陌生车辆 - 仅本次等待有效
        if (CarMac.same(profile.btMac, mac)) profile.connMode = CarConnMode.BT_CARLIFE
        p2pThisWait = true
        main.removeCallbacks(carNetTick)
        if (carNetBusy) carNetImpl?.release("p2p")      // 回调被移除且不会被调用：onUnavailable/onLost 不会到来
        if (CarWifi(this).isSet) logCarSkip()
        cancelHint()
    }

    /** discoverPeers 返回 BUSY：在 Samsung 上这表示热点已开启，要求关闭它。 */
    private fun onP2pBusy() {
        if (!wifiBMode || stopRequested || !linkWaiting) return
        CarLifeState.wireless = getString(R.string.wl_state_p2p_busy)
    }

    private fun onBtAccept() {
        if (!wifiBMode || stopRequested) return
        btAcceptsThisWait++
        cancelBtHint()
    }

    private fun onCarNetAvailable(@Suppress("UNUSED_PARAMETER") network: Network) {
        markCarSeen()
        restartForCarNet()
    }

    /** 等待期间车辆网络出现或消失：重新开始等待，让信标通过新网络发送（或停止发送）。不触碰会话。 */
    private fun restartForCarNet() {
        if (!wifiBMode || stopRequested || session != null || sixReady || !linkWaiting || wifiDirectCar) return
        val l = pendingTcp6
        if (l == null) {      // 监听器仍在打开（或已返回结果）：等待期间重试
            main.postDelayed({ restartForCarNet() }, 300)
            return
        }
        restartingForCarNet = true
        WLog.ev(WLog.LINK, "rearm", "after" to "car-net")
        l.abort()
    }

    private fun onCarNetUnavailable(reason: String) {
        if (wifiDirectCar) return
        if (autoMode && !hintShown) showWifiHint()      // 车辆网络不可用：让用户自行连接 Wi-Fi
    }

    private fun onCarNetLost() {
        markCarSeen()
        if (!wifiBMode || stopRequested || session != null) return
        restartForCarNet()
        scheduleCarNet()
    }

    /** 等待状态；自动模式下附带车辆在蓝牙附近的标记。 */
    private fun waitingText(ifaces: String) = getString(if (autoMode) R.string.wl_state_auto else R.string.wl_state_waiting, ifaces)

    /** 所选车辆的 ACL_CONNECTED：只要它在附近就按 Wi-Fi 等待。不触碰进行中的会话（USB 或无线）。 */
    private fun onCarAuto() {
        if (!BuildConfig.WIRELESS_BETA) {
            AppLog.i("сервис: беспровод в этой сборке выключен")
            failStart()
            return
        }
        cancelAutoOff()
        autoMode = true
        autoStarted = true
        if (session != null) {
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "session", "kind" to session?.transportKind)
            return
        }
        if (wifiBMode && linkWaiting) {
            // ACL 波动：等待已在进行。若没有 RFCOMM 和群组则重启探针 - 车机在新的监听器上打开 SPP（h211）
            val restart = btProbe.idle()
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "waiting", "btRestart" to restart)
            if (restart) btProbe.restart("ACL вернулся")
            return
        }
        hintShown = false
        wifiBMode = true
        stopRequested = false
        beginWait()
        startWifiB()
        btProbe.start()
        WLog.ev(WLog.TRIGGER, "auto-start")
    }

    /** 所选车辆的 ACL_DISCONNECTED：不立即关闭（车机会有 ACL 波动），而是经过 [AUTO_OFF_DEBOUNCE_MS]。 */
    private fun onCarAutoOff() {
        if (session == null && host == null && !linkWaiting && !wifiBMode) {
            // 服务仅为响应断开而从零启动（进程被杀，接收器到达空服务）：无需关闭任何内容
            autoMode = false
            finish()
            return
        }
        autoOff.arm()
        main.removeCallbacks(autoOffTick)
        main.postDelayed(autoOffTick, AUTO_OFF_DEBOUNCE_MS)
        WLog.ev(WLog.TRIGGER, "auto-off-armed", "ms" to AUTO_OFF_DEBOUNCE_MS)
    }

    private fun cancelAutoOff() {
        autoOff.cancel()
        main.removeCallbacks(autoOffTick)
    }

    /** 车辆已通过蓝牙离开且没有新的 ACL_CONNECTED：关闭自动模式。绝不触碰 USB 会话。 */
    private fun applyAutoOff() {
        autoMode = false
        cancelHint()
        if (session != null) {
            // 会话进行中（无线：车机保持 Wi-Fi 的时间可能比蓝牙更久；有线 - 与我们无关）；会话结束后不再恢复等待
            WLog.ev(WLog.TRIGGER, "auto-off", "session" to true)
            return
        }
        if (wifiBMode && autoStarted) {
            // 顺序很重要：在 abort 之前关闭模式，startTcp6 中的失败分支会看到 wifiBMode=false
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            pendingTcp6?.abort()
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to true)
            failStart()
        } else {
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to false)
        }
    }

    /**
     * USB 会话结束后经过 [USB_TO_AIR_MS]：没有线缆（G6S EIO 常常立即重新连接配件），
     * 车辆在蓝牙附近 - 显示"尝试无线连接"通知并按 ACL_CONNECTED 方式等待。
     */
    private fun usbToAir() {
        usbToAirPending = false
        if (session != null || wifiBMode || !autoMode || stopRequested) {
            if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
            return
        }
        if (getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true) {
            WLog.ev(WLog.LINK, "usb-to-air", "skip" to "кабель на месте")
            if (session == null && host == null) finish()
            return
        }
        WLog.ev(WLog.LINK, "usb-to-air")
        AppLog.i("сервис: кабель отключён, машина рядом по Bluetooth - жду её по воздуху")
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_usb_to_air_title))
            .setContentText(getString(R.string.wl_usb_to_air_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(USB_TO_AIR_NOTIF_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(USB_TO_AIR_NOTIF_ID, n)
        onCarAuto()
    }

    /** 移除两个等待提示（Wi-Fi 和蓝牙）及其定时器。 */
    private fun cancelHint() {
        main.removeCallbacks(hintTick)
        getSystemService(NotificationManager::class.java).cancel(HINT_NOTIF_ID)
        cancelBtHint()
    }

    private fun cancelBtHint() {
        main.removeCallbacks(btHintTick)
        getSystemService(NotificationManager::class.java).cancel(BT_HINT_NOTIF_ID)
        cancelWifiOff()
    }

    private fun cancelWifiOff() {
        if (!wifiOffShown) return
        wifiOffShown = false
        getSystemService(NotificationManager::class.java).cancel(WIFI_OFF_NOTIF_ID)
    }

    /**
     * 加入车机组时因 Wi-Fi 关闭而受阻（Android 保持 P2P 关闭，discover 返回 BUSY）：请求开启
     * Wi-Fi 并关闭热点。Wi-Fi 开启后 - 撤销请求，P2pJoin 自行加入。点击打开 Wi-Fi 面板。
     */
    private fun onP2pWifi(on: Boolean) {
        if (on) {
            if (CarLifeState.wireless == getString(R.string.wl_state_wifi_off)) CarLifeState.wireless = waitingText("…")
            cancelWifiOff()
            return
        }
        if (!wifiBMode || stopRequested || session != null) return
        wifiOffShown = true
        CarLifeState.wireless = getString(R.string.wl_state_wifi_off)
        val open = PendingIntent.getActivity(
            this, 4, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_wifi_off_title))
            .setContentText(getString(R.string.wl_wifi_off_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.wl_wifi_off_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WIFI_OFF_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-off-hint")
    }

    /** 自动模式等待 30 秒仍无 6/6：车机可能不在手机网络中。点击打开 Wi-Fi 面板。 */
    private fun showWifiHint() {
        if (!autoMode || !wifiBMode || !linkWaiting || session != null || hintShown) return
        if (wifiDirectCar) return      // Wi-Fi Direct：手机自行加入车机组，无需连接 Wi-Fi
        // 车辆网络正在请求或已获得：用户无需连接 Wi-Fi，提示将在 onUnavailable 时出现
        if (carNetBusy) return
        hintShown = true
        val open = PendingIntent.getActivity(
            this, 2, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_hint_title))
            .setContentText(getString(R.string.wl_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(HINT_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-hint")
    }

    /** 等待 15 秒而车机未连接 RFCOMM `carlife`：如果手机与车辆通过蓝牙连接，请求重新连接。 */
    private fun checkBtHint() {
        if (!wifiBMode || stopRequested || session != null || btHintShown || btAcceptsThisWait > 0 || p2pThisWait) return
        CarBtState.check(this, CarProfile(this).btMac) { carBt ->
            val mode = CarProfile(this).connMode
            val show = BtReconnectHint.shouldShow(
                waiting = wifiBMode && linkWaiting && !stopRequested,
                sessionActive = session != null,
                waitedMs = SystemClock.elapsedRealtime() - waitSince,
                acceptsThisWait = btAcceptsThisWait,
                targetInfoThisWait = p2pThisWait,
                carBtConnected = carBt,
                mode = mode,
                alreadyShown = btHintShown,
            )
            if (show) showBtHint(carBt, mode)
        }
    }

    /** 点击打开蓝牙设置。 */
    private fun showBtHint(carBt: Boolean, mode: CarConnMode) {
        btHintShown = true
        val open = PendingIntent.getActivity(
            this, 3, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_bt_hint_title))
            .setContentText(getString(R.string.wl_bt_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(BT_HINT_NOTIF_ID, n)
        CarLifeState.btProbe = "машина подключена по Bluetooth, но carlife не открыла: переподключите Bluetooth"
        WLog.ev(WLog.TRIGGER, "bt-hint", "carBt" to carBt, "mode" to mode.name)
    }

    private fun startTcp6(kind: String, bind: String, waitMs: Long, beacon: HotspotBeacon?) {
        if (linkWaiting) {
            AppLog.i("сервис: $kind: уже жду ГУ, повторный старт игнорирую")
            return
        }
        linkWaiting = true
        sixReady = false
        WLog.begin(kind, "bind" to bind, "ACCESS_LOCAL_NETWORK" to NetProbe.localNetworkGranted(this))
        AppLog.i("сервис: $kind, слушаю 6 портов на $bind, жду ГУ ${waitMs / 1000} с")
        CarLifeState.status = "$kind: жду ГУ"
        val waitStart = SystemClock.elapsedRealtime()
        Thread({
            var hotspotPeer = false      // 6/6 来自信标子网而非车机的 P2P 群组
            val result: Any = try {
                val peerOk: (java.net.InetAddress) -> Boolean =
                    if (beacon == null) { _ -> true } else { a -> beacon.current.any { it.contains(a) } || btProbe.ownsPeer(a) }
                val l = Tcp6Listener(java.net.InetAddress.getByName(bind), peerOk = peerOk).open()
                pendingTcp6 = l
                beacon?.start()
                l.await(waitMs, "$kind $bind").also { hotspotPeer = beacon != null && l.peerAddress?.let { !btProbe.ownsPeer(it) } == true }
            } catch (e: Exception) {
                e
            } finally {
                pendingTcp6 = null
            }
            beacon?.stop(if (result is CarTransport) "6/6" else "нет ГУ")
            main.post {
                linkWaiting = false
                val restarting = restartingForCarNet
                restartingForCarNet = false
                when {
                    result is CarTransport && session != null -> result.close()
                    result is CarTransport -> {
                        sixReady = true
                        // 非 Wi-Fi Direct 连接：此车辆不需要 P2P（如果本次等待中有 TARGET_INFO，则不修改模式）
                        if (hotspotPeer && !p2pThisWait) CarProfile(this).connMode = CarConnMode.HOTSPOT
                        // 6/6 来自车辆网络（信标只在该网络中发送）：接下来立即请求它，无需 CAR_NET_DELAY_MS
                        if (hotspotPeer && carNetImpl?.current != null) CarWifi(this).let { w ->
                            if (w.isSet && !w.proven) {
                                w.markProven()
                                WLog.ev(WLog.LINK, "car-net-proven")
                            }
                        }
                        if (beacon != null) {
                            cancelHint()      // 6/6：不再需要 Wi-Fi 提示
                            CarLifeState.wireless = getString(R.string.wl_state_connected)
                        }
                        startSession(result)
                    }
                    // 为在车辆网络中重发信标而中止：不算放弃，不修改模式和状态，暂停后重新等待
                    restarting && beacon != null && wifiBMode && !stopRequested && session == null -> {
                        main.postDelayed({
                            if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                        }, WIFI_B_REARM_MS)
                    }
                    else -> {
                        val msg = (result as Exception).message
                        if (result !is LinkSetupException) AppLog.e(kind, result)
                        AppLog.i("сервис: $msg")
                        // 自动模式：车辆在蓝牙附近，超时不算放弃，而是新一轮等待。提前失败
                        // （端口被占用等）不会进入这里：否则每 1.5 秒循环一次
                        val timedOut = SystemClock.elapsedRealtime() - waitStart >= waitMs
                        if (beacon != null && wifiBMode && autoMode && !stopRequested && session == null && timedOut) {
                            WLog.ev(WLog.LINK, "rearm", "after" to "timeout-auto")
                            main.postDelayed({
                                if (wifiBMode && autoMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                            }, WIFI_B_REARM_MS)
                            return@post
                        }
                        if (beacon != null && wifiBMode) {
                            wifiBMode = false
                            autoStarted = false
                            cancelHint()
                            stopCarNet(CarNetEvent.AUTO_OFF, "gave-up")      // 等待已结束：不再需要车辆网络
                            btProbe.stop("не дождались")
                            CarLifeState.wireless = getString(R.string.wl_state_gave_up, (WIFI_B_WAIT_MS / 60_000).toInt())
                        }
                        // 等待被线缆取消：状态已是 USB 会话的，不要覆盖
                        if (session == null) CarLifeState.status = msg ?: "$kind: ошибка"
                        failStart()
                    }
                }
            }
        }, "link-$kind").start()
    }

    private fun startSession(transport: CarTransport) {
        main.removeCallbacks(usbToAirTick)
        usbToAirPending = false
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        main.removeCallbacks(carNetTick)      // 会话进行中：不再发起新的车辆网络请求（保留请求本身，CarNetEvent.SESSION_START）
        cancelBtHint()
        CarLifeState.accessory = transport.label
        CarLifeState.wirelessSession = transport.caps.kind == TransportCaps.TCP6.kind
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

        val aaPrefs = AaPrefs(this)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, aaPrefs.micVrStatus, aaPrefs.fpsSetting,
            metaMusic = aaPrefs.metaMusic, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // 到 127.0.0.1:5277 上车机服务器的 AA 会话存活于服务层面（BridgeHost），而非本次 USB 会话
        var h = host
        // 模式在连接时固定（SDR 已发出）。车机服务器是一次性的，现在没有人能重启它
        // （没有无障碍服务），因此不关闭具有其他几何或 DPI 的存活 AA：沿用旧模式工作
        val chosenGeometry = aaPrefs.videoGeometry
        val wantMode = chosenGeometry.effective(aaPrefs.transcode)
        val wantDensity = aaPrefs.aaDensity
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (${h.core.videoMode.label} -> ${wantMode.label}), применится после перезапуска сервера ГУ")
        } else if (h != null && !h.isDead && h.core.densityOverride != wantDensity) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (DPI ${h.core.densityOverride} -> $wantDensity, 0 = авто), применится после перезапуска сервера ГУ")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, chosenGeometry, wantMode, wantDensity, previous = h)
            host = h
            if (h == null) {
                // 没有 head unit 证书或桥接未建立：原因已在 AaState.setupError 中，不打开 USB 会话
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("BRIDGE: AA-хост уже есть, переиспользую (grace=${h.isInGrace})")
        }
        // 无线连接时必选转码：passthrough 模式下码率无法调节。不修改主机模式
        // （不转码时它已是 R720，可以转码），不重建主机：AA 服务器是一次性的。
        // 带裁剪的旧主机模式（WIDE*、R1024）在 passthrough 下无法工作：保持转码开启
        val transcode = aaPrefs.transcode || transport.caps == TransportCaps.TCP6 || h.core.videoMode.needsTranscode
        if (h.core.transcodeEnabled != transcode) {
            AppLog.i("BRIDGE видео: ${if (transcode) "перекодирование включаю принудительно (воздух или режим хоста ${h.core.videoMode.label})" else "кабель, возвращаю passthrough по настройке"}")
            h.core.transcodeEnabled = transcode
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core, PhoneThermal(this), onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) }) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
    }

    /** 新的 AA 主机：内核、连接器、证书。null = 桥接未建立（原因在 AaState.setupError）。 */
    private fun createHost(aaPrefs: AaPrefs, chosen: VideoGeometry, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?): BridgeHost? {
        return try {
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            val audio = getSystemService(AudioManager::class.java)
            core.phoneCallProvider = {
                when (audio?.mode) {
                    AudioManager.MODE_RINGTONE -> PhoneCall.RINGING
                    AudioManager.MODE_IN_CALL -> PhoneCall.ACTIVE
                    else -> PhoneCall.IDLE                  // 不采用 IN_COMMUNICATION：助手语音与之表现相同
                }
            }
            core.musicRateProvider = { aaPrefs.musicRate }
            core.mediaChunkProvider = { aaPrefs.mediaChunk }
            core.voiceChunkProvider = { aaPrefs.voiceChunk }
            core.ttsPrerollProvider = { aaPrefs.ttsPreroll }
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            EqLive.settings = aaPrefs.eq
            core.transcodeEnabled = aaPrefs.transcode
            core.videoMode = mode
            core.densityOverride = densityOverride
            core.expectFeatureConfig = true
            if (chosen != mode) AppLog.i("BRIDGE видео-режим ${chosen.label} недоступен без перекодирования, использую ${mode.label}")
            // 音频同步（MEDIA/SPEECH）在手机请求时声明，此时已确定音频方案
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb, densityOverride = densityOverride, carMake = aaPrefs.carMake) }) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
                }
            })
            ref[0] = h
            h
        } catch (e: AaCertsMissingException) {
            AppLog.e("BRIDGE не стартовал: нет сертификата head unit", e)
            AaState.setupError = e.message
            AaState.status = "ошибка: нет сертификата"
            null
        } catch (e: Throwable) {
            AppLog.e("BRIDGE не стартовал (сертификаты AA)", e)
            AaState.setupError = getString(R.string.svc_bridge_failed, "${e.javaClass.simpleName}: ${e.message}")
            AaState.status = "ошибка: мост не стартовал"
            null
        }
    }

    /**
     * Foreground 类型 connectedDevice；仅当拥有 RECORD_AUDIO 权限且手机麦克风
     * 确实可能被用到（模式非"车机"）时才添加 microphone：否则会抛 SecurityException。Android 14+ 在后台
     * 不允许 microphone 类型（while-in-use）- 此时保持 connectedDevice。
     */
    private fun startForegroundTyped(n: Notification) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val micWanted = AaPrefs(this).micMode != MicMode.CAR &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micWanted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, n, type)
            if (micWanted) AppLog.i("сервис: foreground-тип connectedDevice|microphone")
        } catch (e: Exception) {      // SecurityException 或 ForegroundServiceStartNotAllowedException
            AppLog.e("сервис: foreground-тип с microphone не дали, остаюсь на connectedDevice", e)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    /** 启动失败：如果 AA 主机存活（处于 grace 期），不关闭服务，否则 finish。 */
    private fun failStart() {
        if (linkWaiting) return      // 正在按无线等待车辆：需要服务
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        session = null
        markCarSeen()
        CarLifeState.wirelessSession = false
        if (wifiBMode && autoStarted && !autoMode && !stopRequested) {
            // 无线会话进行期间车辆通过蓝牙离开（车机保持着 Wi-Fi）：不重新开始等待
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            WLog.ev(WLog.TRIGGER, "auto-off", "after" to "session")
        }
        if (!wifiBMode && autoMode && !stopRequested && BuildConfig.WIRELESS_BETA && s.transportKind == TransportCaps.USB_AOA.kind) {
            // 线缆被拔掉而车辆在蓝牙附近：不会有新的 ACL，我们自己开始无线等待（h214）
            usbToAirPending = true
            main.removeCallbacks(usbToAirTick)
            main.postDelayed(usbToAirTick, USB_TO_AIR_MS)
            WLog.ev(WLog.LINK, "usb-to-air-armed", "ms" to USB_TO_AIR_MS)
        }
        val h = host
        if (h == null) {
            if ((!wifiBMode && !usbToAirPending) || stopRequested) finish()
        } else {
            // USB 断开：CarLife 已关闭，AA 保持 graceMs（或如果停止则立即关闭）
            h.onUsbSessionClosed(immediate = stopRequested)
        }
        if (wifiBMode && !stopRequested) {
            // 无线：车机已离开（Wi-Fi 断开、车辆熄火）- 重新发送信标并等待，AA 仍处于热状态（grace）
            CarLifeState.wireless = getString(R.string.wl_state_lost)
            WLog.ev(WLog.LINK, "rearm", "after" to reason)
            main.postDelayed({
                if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
            }, WIFI_B_REARM_MS)
        }
    }

    private fun finish() {
        stopCarNet(CarNetEvent.STOP, "finish")
        CarLifeState.running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        alive = false
        autoMode = false
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.DESTROY, "destroy")
        pendingTcp6?.abort()
        btProbe.stop("сервис уничтожен")
        audioDiag.stop()
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** 无线（测试版）：按 Wi-Fi 等待车辆，分支 B（手机热点 + 信标）。 */
        const val ACTION_WIFI_B = "com.yegorkapomidorka.headswap.WIFI_B"
        /** 所选车辆已通过蓝牙连接（ACL_CONNECTED）：只要它在附近就按 Wi-Fi 等待。 */
        const val ACTION_WIFI_B_AUTO = "com.yegorkapomidorka.headswap.WIFI_B_AUTO"
        /** 车辆已通过蓝牙断开（ACL_DISCONNECTED）：经过防抖后解除等待。 */
        const val ACTION_WIFI_B_AUTO_OFF = "com.yegorkapomidorka.headswap.WIFI_B_AUTO_OFF"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val HINT_NOTIF_ID = 2
        private const val USB_TO_AIR_NOTIF_ID = 5
        /** USB 会话结束后、开始无线等待前的延迟：以便观察到配件重新连接。 */
        private const val USB_TO_AIR_MS = 3_000L
        private const val USB_TO_AIR_NOTIF_MS = 60_000L
        private const val BT_HINT_NOTIF_ID = 3
        private const val WIFI_OFF_NOTIF_ID = 4

        /** 服务已创建且未被销毁（同一进程）：ACL 接收器无需为响应断开而唤醒空服务。 */
        @Volatile var alive = false
            private set
        /** debug tcp6：等待车机全部 6 个套接字的时间。 */
        private const val TCP6_WAIT_MS = 60_000L
        /** 分支 B：发送信标并等待车机的时间（用户开启热点并在车机上打开 CarLife）。 */
        private const val WIFI_B_WAIT_MS = 10 * 60_000L
        /** 无线会话断开后、开始新等待前的延迟。 */
        private const val WIFI_B_REARM_MS = 1_500L
        /** 自动模式：ACL_DISCONNECTED 之后等待新的 ACL_CONNECTED 多久，才关闭等待。 */
        private const val AUTO_OFF_DEBOUNCE_MS = 10_000L
        /** 自动模式：等待多久无 6/6 时显示 Wi-Fi 提示。 */
        private const val AUTO_HINT_MS = 30_000L
    }
}
