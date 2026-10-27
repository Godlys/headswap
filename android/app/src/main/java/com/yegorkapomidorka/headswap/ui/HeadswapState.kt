package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState

/** 主屏幕和通知的状态摘要。纯逻辑,不依赖 Android:由 Inputs 计算得出。 */
enum class Phase { OFF, WAITING, CONNECTED, RECONNECTING, PARKED, ERROR }

enum class StageState { DONE, ACTIVE, PAUSE, ERR, TODO, PEND }

enum class Tone { PRI, OK, WARN, ERR }

enum class HeroIcon { POWER, PLUG, CAR, CAR_USB, CAR_WIFI, REFRESH, ALERT }

enum class PrimaryAction { START_SERVER }

enum class OutlineAction { OPEN_AA, STOP }

data class HomeState(
    val phase: Phase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: UiText,
    val sub: UiText,
    val stages: List<Pair<UiText, Pair<StageState, UiText>>>,
    /** grace 剩余秒数(仅 RECONNECTING),否则为 -1。 */
    val graceLeftS: Int,
    val graceTotalS: Int,
    val primary: PrimaryAction?,
    val outline: OutlineAction?,
    val logLink: Boolean,
    val showChain: Boolean,
    val notifTitle: UiText,
    val notifText: UiText,
)

class Inputs(
    val bridgeRunning: Boolean,
    val aaStatus: String,
    val protocolKnown: Boolean,
    /** AA 车机服务器已启动(由我们启动),但会话尚未建立。 */
    val serverUp: Boolean,
    /** 已建立的会话已结束(USB 断开或「停止」),AA 正常关闭:车机服务器应保持运行。 */
    val ended: Boolean,
    val graceLeftS: Int,
    val graceTotalS: Int,
    val usbAudio: Boolean,
    /** 桥接因配置未启动(缺少车机证书):面向用户的说明文本。 */
    val setupError: UiText? = null,
    /** 车机上的视频尺寸(「1280×720」):会话的实际模式。 */
    val videoRes: String = "1280×720",
    /** 会话通过 Wi-Fi 进行:CarLife 和声音经无线传输到车机,无需数据线。 */
    val wireless: Boolean = false,
    /** 车机服务器连续多少毫秒拒绝(ECONNREFUSED)桥接的真实连接;0 = 未拒绝。 */
    val refusedForMs: Long = 0,
    /** 桥接连续多少次 TCP 连接被接受却没有 VERSION_RESPONSE(服务器卡死)。 */
    val versionHangs: Int = 0,
    /** 至少成功建立过一次真正的 AA 会话:车机服务器曾正常工作。没有这一点就不显示「无响应」(对新用户显示「启动车机服务器」)。 */
    val serverWorked: Boolean = false,
)

object HomeLogic {
    /** 车机服务器拒绝短于此时间属正常:ByeBye 后 AA 约 6 秒重新监听端口。 */
    const val REFUSED_DOWN_MS = 15_000L

    /** 连续这么多次「TCP 通了但没有 VERSION_RESPONSE」,即服务器卡死。 */
    const val HANGS_DOWN = 2

    val LABELS = listOf(
        UiText.res(R.string.home_stage_server), UiText.res(R.string.home_stage_headunit), UiText.res(R.string.home_stage_aa),
        UiText.res(R.string.home_stage_video), UiText.res(R.string.home_stage_audio),
    )
    private val NONE = UiText.raw("")
    private val CARLIFE = UiText.raw("CarLife 2.0")
    private val CARLIFE_WIFI = UiText.raw("CarLife 2.0 · Wi-Fi")
    private val TEXT_START_CHAIN = UiText.res(R.string.home_sub_start_chain)

    fun compute(i: Inputs): HomeState {
        val st = i.aaStatus
        val reconnecting = i.bridgeRunning && "USB потерян" in st
        val connected = i.bridgeRunning && st.startsWith("мост: AA -> CarLife")
        val refused = i.bridgeRunning && "жду сервер ГУ" in st
        val total = if (i.graceTotalS > 0) i.graceTotalS else 8
        // 数据线已连接,桥接尝试连接,而此前工作正常的服务器对真实连接尝试无响应
        val serverDown = i.bridgeRunning && i.serverWorked && (i.refusedForMs >= REFUSED_DOWN_MS || i.versionHangs >= HANGS_DOWN)
        return when {
            i.setupError != null && !i.bridgeRunning -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_no_bridge), i.setupError,
                listOf(
                    StageState.ERR to UiText.res(R.string.home_stage_no_cert), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                logLink = true,
                notifTitle = UiText.res(R.string.home_title_no_bridge), notifText = i.setupError,
            )
            reconnecting -> {
                val left = i.graceLeftS.coerceAtLeast(0)
                state(
                    Phase.RECONNECTING, Tone.WARN, HeroIcon.REFRESH, UiText.res(R.string.home_title_reconnecting),
                    UiText.res(R.string.home_sub_reconnecting, total),
                    listOf(
                        StageState.DONE to UiText.res(R.string.home_stage_holds), StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused), StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                    ),
                    outline = OutlineAction.STOP, graceLeft = left, graceTotal = total,
                    notifText = UiText.res(R.string.notif_reconnecting, left),
                )
            }
            connected -> state(
                Phase.CONNECTED, Tone.OK, if (i.wireless) HeroIcon.CAR_WIFI else HeroIcon.CAR_USB, UiText.res(R.string.home_title_ok),
                UiText.res(if (i.wireless) R.string.home_sub_ok_wifi else R.string.home_sub_ok),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_working),
                    StageState.DONE to if (i.wireless) CARLIFE_WIFI else CARLIFE,
                    StageState.DONE to UiText.res(R.string.home_stage_connected),
                    StageState.DONE to UiText.raw(i.videoRes),
                    // 「通过 Headswap 播放声音」(原「通过 USB 播放声音」)即声音走 CarLife 通道;无数据线时经 Wi-Fi 传输
                    if (i.usbAudio) StageState.DONE to UiText.raw(if (i.wireless) "Wi-Fi" else "USB")
                    else StageState.PEND to UiText.res(R.string.home_stage_off),
                ),
                outline = OutlineAction.STOP,
                notifText = UiText.res(
                    when {
                        !i.usbAudio -> R.string.notif_video
                        i.wireless -> R.string.notif_video_wifi
                        else -> R.string.notif_video_usb
                    },
                ),
            )
            serverDown -> serverDownState()
            // 服务器此前工作过:ByeBye 后的短暂拒绝属正常,等待 AA 重新监听;像普通连接一样等待
            refused && !i.serverWorked -> state(
                Phase.OFF, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_off),
                UiText.res(R.string.home_sub_refused, TEXT_START_CHAIN),
                listOf(
                    StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.DONE to CARLIFE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = UiText.res(R.string.notif_server_off),
            )
            i.bridgeRunning -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_connecting),
                UiText.res(if ("отключился" in st) R.string.home_sub_aa_dropped else R.string.home_sub_cable_ok),
                listOf(
                    StageState.ACTIVE to UiText.res(R.string.home_stage_connecting),
                    if (i.protocolKnown) StageState.DONE to CARLIFE else StageState.ACTIVE to UiText.res(R.string.home_stage_handshake),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.STOP,
                notifText = UiText.res(R.string.notif_cable_waiting),
            )
            i.ended -> parkedState()
            i.serverUp -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_ready),
                UiText.res(R.string.home_sub_ready),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_started),
                    StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.OPEN_AA,
                notifText = UiText.res(R.string.notif_server_started),
            )
            else -> state(
                Phase.OFF, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_off), TEXT_START_CHAIN,
                listOf(
                    StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = UiText.res(R.string.notif_server_off),
            )
        }
    }

    /** 「车辆已断开」:桥接自行正常关闭了已建立的会话(ByeBye),车机服务器留在后台。该状态保持到再次插入数据线。 */
    private fun parkedState() = state(
        Phase.PARKED, Tone.PRI, HeroIcon.CAR, UiText.res(R.string.home_title_parked), UiText.res(R.string.home_sub_parked),
        listOf(
            StageState.DONE to UiText.res(R.string.home_stage_working),
            StageState.PEND to UiText.res(R.string.home_stage_hu_parked), StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        notifText = UiText.res(R.string.notif_parked),
    )

    /** 「车机服务器无响应」:桥接的真实连接得不到响应(拒绝超过 REFUSED_DOWN_MS 或卡死达到 HANGS_DOWN 次),需要重新启动服务器。 */
    private fun serverDownState() = state(
        Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_server_down), UiText.res(R.string.home_sub_server_down),
        listOf(
            StageState.ERR to UiText.res(R.string.home_stage_no_answer), StageState.PEND to NONE, StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        primary = PrimaryAction.START_SERVER, logLink = true,
        notifText = UiText.res(R.string.notif_server_down),
    )

    private fun state(
        phase: Phase, tone: Tone, icon: HeroIcon, title: UiText, sub: UiText,
        stages: List<Pair<StageState, UiText>>,
        primary: PrimaryAction? = null, outline: OutlineAction? = null, logLink: Boolean = false,
        showChain: Boolean = false, graceLeft: Int = -1, graceTotal: Int = 0,
        notifTitle: UiText = title, notifText: UiText,
    ) = HomeState(
        phase, tone, icon, title, sub, LABELS.zip(stages), graceLeft, graceTotal,
        primary, outline, logLink, showChain, notifTitle, notifText,
    )
}

/**
 * 可观察的摘要(只读 CarLifeState/AaState + UI 部分自身掌握的信息):
 * 组装 Inputs 并记住状态迁移(会话是否中断、何时连接、grace 持续到何时)。
 * 在主线程调用:由界面和通知 ticker 使用。
 */
object HeadswapStatus {
    private var prevRunning = false
    private var sawConnected = false
    private var ended = false
    private var serverUp = false
    private var graceDeadline = 0L
    private var graceTotalS = 0
    var connectedSince = 0L
        private set

    private val graceRe = Regex("""жива\s+(\d+)\s*с""")

    /** 用户点击了「启动车机服务器」(或打开了 AA 设置):视为服务器已启动。 */
    fun markServerRequested() {
        serverUp = true
        ended = false
    }

    /** 「停止」:会话正常关闭(ByeBye),之后界面显示「车辆已断开」。 */
    fun markUserStop() {
        serverUp = false
    }

    fun inputs(ctx: Context): Inputs {
        val now = SystemClock.elapsedRealtime()
        val running = CarLifeState.running
        val st = AaState.status
        val connectedNow = running && st.startsWith("мост: AA -> CarLife")
        val reconnectingNow = running && "USB потерян" in st

        if (running && !prevRunning) {
            ended = false
            sawConnected = false
            connectedSince = 0
        }
        if (connectedNow || reconnectingNow) sawConnected = true
        if (connectedNow) {
            if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
        } else if (!reconnectingNow) {
            connectedSince = 0
        }
        if (reconnectingNow) {
            if (graceDeadline == 0L) {
                graceTotalS = graceRe.find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 8
                graceDeadline = now + graceTotalS * 1000L
            }
        } else {
            graceDeadline = 0
        }
        if (!running && prevRunning) {
            if (sawConnected) ended = true
            serverUp = false
            sawConnected = false
            connectedSince = 0
        }
        prevRunning = running

        val aaPrefs = AaPrefs(ctx)
        // AaConnector 只在进程内知道已建立的会话;记住该标志以在应用重启后保留
        if (AaState.serverFailures.worked && !aaPrefs.serverWorked) aaPrefs.serverWorked = true
        val serverWorked = aaPrefs.serverWorked
        val left = if (graceDeadline > 0) ((graceDeadline - now + 999) / 1000).toInt() else -1
        return Inputs(
            bridgeRunning = running,
            aaStatus = st,
            protocolKnown = CarLifeState.protocol != "-",
            serverUp = serverUp,
            ended = ended,
            graceLeftS = left,
            graceTotalS = graceTotalS,
            usbAudio = aaPrefs.usbAudio,
            // 已知的无证书提示文本替换为资源,其余错误原样显示
            setupError = AaState.setupError?.let { if (it == AaCreds.MISSING_TEXT) UiText.res(R.string.home_err_no_cert) else UiText.raw(it) },
            videoRes = (CarLifeState.videoMode ?: aaPrefs.videoGeometry.effective(aaPrefs.transcode)).outLabel,
            wireless = CarLifeState.wirelessSession,
            refusedForMs = AaState.serverFailures.refusedForMs,
            versionHangs = AaState.serverFailures.versionHangs,
            serverWorked = serverWorked,
        )
    }

    fun current(ctx: Context): HomeState = HomeLogic.compute(inputs(ctx))

    /** USB 附件是否已连接(用于界面提示)。 */
    fun accessoryPresent(ctx: Context): Boolean =
        ctx.getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true
}
