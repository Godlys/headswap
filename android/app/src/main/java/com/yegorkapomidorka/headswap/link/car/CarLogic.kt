package com.yegorkapomidorka.headswap.link.car

/** 汽车蓝牙自动启动的纯逻辑，不含 android.*：由 JVM 测试验证。 */
object CarMac {
    /** 是同一个地址吗？大小写不重要（CDM 返回小写 MAC，BluetoothDevice 返回大写），null 永远不相等。 */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)
}

/**
 * 汽车断开连接的防抖：车机在重连时会产生 ACL_DISCONNECTED/ACL_CONNECTED 的突发（Vezel 日志中 7 秒内 5 个周期），
 * 不能立即熄灭等待。[arm] 启动倒计时（重复调用会顺延），[cancel] 取消（收到了 ACL_CONNECTED），
 * [fire] 由定时器回答是否该熄灭了：期限已过且没有取消；只触发一次。单线程（主线程）。
 */
class AutoOffDebounce(private val delayMs: Long, private val clock: () -> Long) {
    private var dueAt = -1L

    val pending: Boolean get() = dueAt >= 0

    fun arm() {
        dueAt = clock() + delayMs
    }

    fun cancel() {
        dueAt = -1L
    }

    fun fire(): Boolean {
        if (dueAt < 0 || clock() < dueAt) return false
        dueAt = -1L
        return true
    }
}

/** 不超过每 [intervalMs] 一次：陌生设备会持续发送 ACL，我们不污染日志。 */
class MinInterval(private val intervalMs: Long, private val clock: () -> Long) {
    private var last = -1L

    fun tryAcquire(): Boolean {
        val now = clock()
        if (last >= 0 && now - last < intervalMs) return false
        last = now
        return true
    }
}

/** 用哪种加密方式进入汽车网络。成功的加密会被记住（[CarWifi.sec]），之后不再穷举：一个网络对应一个 MAC。 */
object CarSecPolicy {
    fun first(remembered: String?, hasPass: Boolean): String = when {
        !hasPass -> "open"
        remembered == "wpa3" -> "wpa3"
        else -> "wpa2"
    }

    /** 在 WPA2 失败后重试 WPA3 —— 仅当成功加密尚不可知时（否则汽车根本不在线）。 */
    fun retryWpa3(remembered: String?, tried: String, hasPass: Boolean): Boolean =
        hasPass && tried == "wpa2" && remembered == null
}

/** Wi-Fi 等待多久仍无 6/6 时请求进入汽车网络（在此之前车机可能已经自行连入手机网络）。 */
const val CAR_NET_DELAY_MS = 15_000L

/**
 * 汽车在此时间之前仍保持联络（USB/无线会话、汽车网络）：立即请求汽车网络，无需
 * [CAR_NET_DELAY_MS]。拔掉线缆、Wi-Fi 中断：汽车就在附近，没必要在手机网络里等它（Pavel 的日志
 * 2026-10-17：每次有线 -> 无线切换都有 15 秒静默）。
 */
const val CAR_RECENT_MS = 60_000L

/** 系统搜索汽车网络并等待批准的时长；没找到 - 提示手动开启 Wi-Fi。 */
const val CAR_NET_TIMEOUT_MS = 90_000L

/** RFCOMM `carlife` 上等待多久仍无 accept 时提示重新连接 Bluetooth（车机只在 BT 连接时打开它）。 */
const val BT_HINT_MS = 15_000L

/** 所选汽车如何无线连接；与它的 Bluetooth 地址绑定（参见 CarProfile）。 */
enum class CarConnMode {
    UNKNOWN,
    /** 车机通过 Bluetooth 发来过 TARGET_INFO：Wi-Fi Direct，手机加入它的组。 */
    BT_CARLIFE,
    /** 6/6 是与信标子网中的对端建立起来的，没有 Wi-Fi Direct。 */
    HOTSPOT,
}

/** 决定汽车网络请求命运的事件。 */
enum class CarNetEvent { AUTO_OFF, STOP, USB_WINS, DESTROY, SESSION_START, SESSION_END }

/** 汽车网络请求的纯逻辑，不含 android.*：由 JVM 测试验证。 */
object CarNetPolicy {
    /**
     * 是否该发送请求：已设置汽车网络，正在进行 Wi-Fi 等待（自动或手动），没有任何会话（既无 USB 也无
     * 无线），6/6 尚未就绪，请求未挂起也未发出，自等待开始已过去不少于 [delayMs]。
     * 对 Wi-Fi Direct 汽车（[wifiDirectCar]）不请求网络：手机会加入车机组，请求只会碍事。
     */
    fun shouldRequest(
        carWifiSet: Boolean,
        autoOrManualWaiting: Boolean,
        sessionActive: Boolean,
        usbActive: Boolean,
        alreadyRequesting: Boolean,
        waitedMs: Long,
        sixOfSixReady: Boolean,
        wifiDirectCar: Boolean,
        delayMs: Long = CAR_NET_DELAY_MS,
    ): Boolean = carWifiSet && autoOrManualWaiting && !sessionActive && !usbActive && !alreadyRequesting &&
        !sixOfSixReady && !wifiDirectCar && waitedMs >= delayMs

    /**
     * 汽车网络请求的延迟：0，如果汽车在 [CAR_RECENT_MS] 之前仍保持联络（[sinceCarMs]，null = 从未）或所选汽车
     * 当前通过 Bluetooth 连接（[carBtNear]）：它在附近且已开机，没必要在手机网络中等待（Pavel 2026-10-18：
     * 在 15 秒等待期间，车机上首次点击 CarLife 磁贴报「未找到」）。
     * 如果 6/6 已在该汽车的网络中建立过（[carNetProven]），同样为 0：车机广播自己的 Wi-Fi 且不会进入手机网络，
     * 而并非所有人都能通过 Bluetooth 选择汽车（Pavel 2026-10-19：CDM 崩溃，每次启动 15 秒）。
     */
    fun delayMs(sinceCarMs: Long?, carBtNear: Boolean = false, carNetProven: Boolean = false): Long =
        if (carBtNear || carNetProven || (sinceCarMs != null && sinceCarMs in 0 until CAR_RECENT_MS)) 0L else CAR_NET_DELAY_MS

    /**
     * 是否释放网络。停止等待、线缆和服务死亡 - 是。无线会话的开始和结束 - 否：汽车网络
     * 承载着它的 TCP，而会话结束后等待通常会在同一网络上恢复。
     */
    fun shouldRelease(event: CarNetEvent): Boolean = when (event) {
        CarNetEvent.AUTO_OFF, CarNetEvent.STOP, CarNetEvent.USB_WINS, CarNetEvent.DESTROY -> true
        CarNetEvent.SESSION_START, CarNetEvent.SESSION_END -> false
    }
}

/** 「重新连接 Bluetooth」提示的纯逻辑：不含 android.*，由 JVM 测试验证。 */
object BtReconnectHint {
    /**
     * 是否显示：已无会话等待 [BT_HINT_MS]，车机一次也未连接到 RFCOMM `carlife`，尽管手机与汽车已通过 Bluetooth
     * 连接（Mazda 只在 BT 连接时刻打开 carlife），而且这辆汽车之前从未通过热点连接过。每次等待只显示一次。
     * 车机已发来 TARGET_INFO（[targetInfoThisWait]）——它已打开 carlife，无需重新连接 Bluetooth。
     */
    fun shouldShow(
        waiting: Boolean,
        sessionActive: Boolean,
        waitedMs: Long,
        acceptsThisWait: Int,
        targetInfoThisWait: Boolean,
        carBtConnected: Boolean,
        mode: CarConnMode,
        alreadyShown: Boolean,
    ): Boolean = waiting && !sessionActive && waitedMs >= BT_HINT_MS && acceptsThisWait == 0 && !targetInfoThisWait &&
        carBtConnected && mode != CarConnMode.HOTSPOT && !alreadyShown
}
