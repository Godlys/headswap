package com.yegorkapomidorka.headswap

import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CarlifeAudio
import com.yegorkapomidorka.headswap.aa.AaTranscoder
import com.yegorkapomidorka.headswap.aa.BitratePolicy
import com.yegorkapomidorka.headswap.aa.BitrateProfile
import com.yegorkapomidorka.headswap.aa.CarlifeLink
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.IdrThrottle
import com.yegorkapomidorka.headswap.aa.CarlifeMediaInfo
import com.yegorkapomidorka.headswap.aa.LinkVideoStats
import com.yegorkapomidorka.headswap.aa.NaviField6
import com.yegorkapomidorka.headswap.aa.NaviTurnInfo
import com.yegorkapomidorka.headswap.aa.ThermalLevel
import com.yegorkapomidorka.headswap.aa.ThermalSource
import com.yegorkapomidorka.headswap.aa.ThermalThrottle
import com.yegorkapomidorka.headswap.aa.TranscodeOutput
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.aa.VideoQueueLimit
import com.yegorkapomidorka.headswap.aa.VideoTranscoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.diag.WLog

/** 会话设置，启动时读取。 */
class SessionConfig(
    val screenOn: Boolean = true,
    /** 随 MIC_RECORD_* 一起发送 MODULE_STATUS VR 1/0，同 Baidu (prepareShowVr / prepareCloseVr)。 */
    val micVrStatus: Boolean = true,
    /** 帧率：自动（按 head unit 请求）或固定。passthrough 模式下不生效。 */
    val fpsSetting: FpsSetting = FpsSetting.DEFAULT,
    /** 来自 AA 的曲目、封面和进度 (MEDIA_INFO / MEDIA_PROGRESS_BAR)。 */
    val metaMusic: Boolean = true,
    /** 来自 AA 的转弯提示（按 head unit 的 moduleID=0 订阅发送 NAVI_NEXT_TURN_INFO）。 */
    val metaNavi: Boolean = true,
    /** NAVI_NEXT_TURN_INFO 字段 6：图标和/或 time。 */
    val naviField6: NaviField6 = NaviField6.BOTH,
)

/**
 * 在传输层之上（USB 流或 6 个 socket）与 head unit 的一次会话：以 MD 身份应答，向桥接层提供视频/音频/触摸，并写入全部日志。
 * 线程：传输层读写（sink 与 onWritten 已串行化）、enc-drain/render（VideoSource 占位器）、
 * scheduler（统计、静默 watchdog、延迟的 MODULE_STATUS）。
 * CarLife 2.0 协议遵循 Baidu CarLife 8.6.7 MD 的行为，见 docs/carlife-v2-handshake.md。
 */
class CarLifeSession(
    private val transport: CarTransport,
    private val cfg: SessionConfig,
    private val bridge: AaBridgeCore,
    /** 手机发热：根据它，转码会降低 fps 和比特率。 */
    private val thermal: ThermalSource = ThermalSource.NONE,
    /** head unit 的 VIDEO_ENCODER_INIT 中的帧尺寸（用于开发者选项中的「按 head unit 请求」提示）。 */
    private val onHuSize: (Int, Int) -> Unit = { _, _ -> },
    private val onClosed: (String) -> Unit,
) : VideoSource.Listener, CarlifeLink {

    /** 连接时确定的视频模式：整个会话期间对 INIT_DONE、编码器和触摸保持一致。 */
    private val mode: VideoGeometry = bridge.videoMode

    /** 会话传输层：usb-aoa、tcp-debug 或 tcp6。 */
    val transportKind: String get() = transport.caps.kind

    /** 无线（6 个 socket）：独立的比特率阶梯、更频繁的 PAUSE keepalive、视频走 Wi-Fi 线路。USB 和 tcp-debug 维持原样。 */
    private val air = transport.caps == TransportCaps.TCP6
    private val bitratePolicy = BitratePolicy(mode.bitrate, profile = if (air) BitrateProfile.WIFI else BitrateProfile.USB)
    @Volatile private var vwIdrCutMs = NEVER_MS
    private val thermalThrottle = ThermalThrottle()
    private var thermalStatus = 0                       // 仅 cl-sched 线程
    private var thermalHeadroom = Float.NaN
    private var headroomAtMs = NEVER_MS
    private var thermalPassthroughLogged = false
    private val pauseKeepaliveMs = if (air) PAUSE_KEEPALIVE_AIR_MS else PAUSE_KEEPALIVE_MS
    private var overflows = 0
    private var maxQueueFrames = 0
    private val placeholderIdr = IdrThrottle()
    @Volatile private var lastOverflowMs = NEVER_MS
    private var passthroughBitrateLogged = false
    private var spsFixLogged = false
    private var splitLogged = false
    private val closed = AtomicBoolean(false)
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cl-sched") }
    private val metaExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cl-meta").also { it.isDaemon = true } }
    private var lastArtSrc: ByteArray? = null          // 仅 cl-meta 线程
    private var lastArtJpeg = ByteArray(0)
    private val turnIcons = HashMap<Int, ByteArray>()
    @Volatile private var metaMusicOffLogged = false

    private val t0 = SystemClock.elapsedRealtime()

    // 协议
    @Volatile private var matchSent = false
    private var huMajor = 0
    private var huMinor = 0
    private var pendingW = 0
    private var pendingH = 0
    private var pendingFps = FPS

    /** 转码器目标 fps：来自 INIT（按设置），自动模式下随 FRAME_RATE_CHANGE 变化；新转码器以此启动。 */
    @Volatile private var targetFps = FPS
    private var videoInited = false
    private var source: VideoSource? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var csdPending = true
    @Volatile private var paused = false

    // 供最终摘要使用的发现
    private var huVersion = "-"
    private var sawAuthRequest = false
    private var authRandom: String? = null
    /** 已发送 MD_INFO 中的 brand + model + sdk：2.0 授权分支的键。 */
    private var mdInfoKey = ""
    private var huAuthResult: String? = null
    private var touchMode = "не было тачей"
    private var touchCount = 0
    private var maxFingers = 0
    private var lastMoveFingers = 0
    private val rxByType = java.util.TreeMap<Int, Int>()
    private var huInfo: String? = null
    private var huStatistic: String? = null
    private var huFeature: String? = null
    private var encryptionNeeded: Boolean? = null
    private var requested = "-"
    private var chosen = "-"

    // 视频统计
    @Volatile private var lastVideoTx = 0L
    private var idleBeatLogged = false
    private var vFrames = 0L
    private var vBytes = 0L
    private var vKeys = 0L
    private var vBeats = 0L
    private var vDropped = 0L
    private var audioTx = 0L
    private var ttsTxSinceInit = 0       // 距上次 TTS_INIT 后的 TTS_DATA：前 TTS_TX_LOGGED 条含发送时间
    @Volatile private var micRxPackets = 0L
    @Volatile private var micRxBytes = 0L
    /** 我们自行发送 MODULE_STATUS VR=1 时：紧随其后的应答 MODULE_CONTROL VR=1 视为回声。 */
    @Volatile private var lastOwnVrOnMs = 0L

    // 占位器（带文字的 VideoSource）或 AA 视频；由桥核心切换
    private val videoLock = Any()
    private val srcLock = Any()
    @Volatile private var bannerText: String? = null
    @Volatile private var acceptEncoder = true
    @Volatile private var carlifeStarted = false
    private val vrReassembler = DataReassembler()  // h425: Delphi 将 VR MIC_DATA 切成 256 字节的帧

    fun start() {
        bridge.attach(this)
        AppLog.i("сессия: старт")
        CarLifeState.resetSession()
        CarLifeState.videoMode = mode
        CarLifeState.densityOverride = bridge.densityOverride
        AppLog.i(mode.describe(bridge.densityOverride))
        AppLog.i("сессия: транспорт=${transport.caps.kind} (${transport.label})")
        WLog.ev(WLog.LINK, "session", "transport" to transport.caps.kind)
        if (air) {
            WLog.ev(WLog.VIDEO, "policy", "profile" to bitratePolicy.profile.name, "startKbps" to bitratePolicy.startBps / 1000,
                "ceilingKbps" to bitratePolicy.ceilingBps / 1000, "mode" to mode.label, "transcode" to bridge.transcodeEnabled)
        }
        CarLifeState.status = "соединено, жду HU_PROTOCOL_VERSION"
        transport.start(::dispatch, ::logTx, ::close)
        sched.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ pauseHeartbeat() }, PAUSE_HEARTBEAT_MS, PAUSE_HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ adaptBitrate() }, 1000, BITRATE_POLL_MS, TimeUnit.MILLISECONDS)
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        AppLog.i("сессия: закрытие, причина: $reason")
        bridge.onCarlifeClosed(this)
        if (air) {
            WLog.ev(WLog.VIDEO, "summary", "frames" to vFrames, "dropped" to vDropped, "overflows" to overflows, "maxQueue" to maxQueueFrames,
                "downs" to bitratePolicy.downs, "ups" to bitratePolicy.ups, "minKbps" to bitratePolicy.minBps / 1000,
                "lastKbps" to bitratePolicy.current / 1000)
        }
        WLog.ev(WLog.LINK, "session-end", "transport" to transport.caps.kind, "aliveS" to (SystemClock.elapsedRealtime() - t0) / 1000, "why" to reason)
        logSummary(reason)
        CarLifeState.status = "закрыто: $reason"
        try {
            sched.shutdownNow()
            metaExec.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            source?.stop()
        } catch (e: Exception) {
            AppLog.e("остановка видео", e)
        }
        source = null
        transport.close()
        AppLog.i("сессия: закрыта")
        onClosed(reason)
    }

    // ---------------- 读取 ----------------

    private fun dispatch(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                AppLog.detail(Hex.dump(inner))
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            val note = (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "") +
                (if (m.resv != 0) " [resv=${m.resv}]" else "") +
                (if (Schemas.byType[m.type] == null && m.body.isNotEmpty()) " [схемы нет]" else "")
            val quiet = m.type == Msg.TOUCH_ACTION_MOVE || (m.type == Msg.TOUCH_ACTION && touchCount > 20 && isMove(m.body))
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet, note = note)
            if (chan == Ch.CMD) onCmd(m) else onTouch(m)
        } else {
            val whole = if (chan == Ch.VR) vrReassembler.feed(inner) ?: return else inner
            if (chan == Ch.VR && vrReassembler.assembled == 1 && whole !== inner) {
                AppLog.i("RX VR: ГУ режет сообщение на кадры по ${vrReassembler.firstFragment} Б, собираю (${whole.size} Б)")
            }
            val m = Wire.parseData(whole)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            if (chan == Ch.VR && m.type == Msg.VR_MIC_DATA) {
                onMicData(m)
                return
            }
            val note = " time=${m.time}" + (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "")
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, null, inner, note = note)
        }
    }

    /** head unit 麦克风 (VR 0x58001)：数据体 = PCM16 单声道 16 kHz，无加密。日志：第一个包和每第 200 个包。 */
    private fun onMicData(m: DataMsg) {
        micRxPackets++
        micRxBytes += m.data.size
        if (micRxPackets == 1L || micRxPackets % 200 == 0L) {
            AppLog.i("RX VR MIC_DATA len=${m.data.size} time=${m.time}${if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else ""} #$micRxPackets, всего $micRxBytes Б", ui = micRxPackets == 1L)
        }
        if (m.truncated || m.data.isEmpty()) return
        bridge.onCarlifeMicData(m.data)
    }

    private fun isMove(body: ByteArray): Boolean {
        val f = PbReader.parse(body) ?: return false
        return ((PbReader.int(f, 1) ?: -1) and 0xFF) == 2
    }

    // ---------------- CMD ----------------

    private fun onCmd(m: CmdMsg) {
        when (m.type) {
            Msg.HU_PROTOCOL_VERSION -> {
                val f = PbReader.parse(m.body)
                val maj = f?.let { PbReader.int(it, 1) }
                val min = f?.let { PbReader.int(it, 2) }
                huVersion = "$maj.$min"
                if (matchSent) {
                    // Baidu: ConnectClient.geHasMatch() -> 不发送第二次 MATCH (CarlifeConnectHsgHandler :308-310)
                    AppLog.i("== ПОВТОРНЫЙ HU_PROTOCOL_VERSION $huVersion, второй MATCH не шлю (как Baidu)")
                    return
                }
                huMajor = maj ?: 0
                huMinor = min ?: 0
                CarLifeState.protocol = "ГУ шлёт версию $huVersion"
                AppLog.i("== ВЕРСИЯ ПРОТОКОЛА ГУ: major=$maj minor=$min")
                WLog.ev(WLog.HS, "HU_PROTOCOL_VERSION", "version" to huVersion)
                matchSent = true
                // 立即发送起始数据包，不等 head unit 任何消息：MATCH, FOREGROUND, SCREEN_ON, MD_INFO
                val info = deviceInfo()
                mdInfoKey = HuAuth.mdInfoKey(info)
                for (c in Handshake.startBatch(cfg.screenOn, info)) sendCmd(c.type, c.body)
                val mod = Handshake.moduleStatus(huMajor)
                try {
                    sched.schedule({ sendCmd(mod.type, mod.body) }, Handshake.MODULE_STATUS_DELAY_MS, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // 会话已在关闭
                }
                CarLifeState.status = "handshake: стартовая пачка отправлена"
            }
            Msg.HU_INFO -> {
                huInfo = PbText.render(m.body, Schemas.deviceInfo)
                AppLog.i("== HU_INFO: $huInfo")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "HU_INFO", "manufacturer" to PbReader.str(it, 13), "model" to PbReader.str(it, 14))
                    bridge.onHuInfo(PbReader.str(it, 13), PbReader.str(it, 14), PbReader.str(it, 15))
                }
                logDesay()
                // 没有应答：Baidu 仅保存 (CarlifeDeviceInfoManager.setCarDeviceInfo)
            }
            Msg.STATISTIC_INFO -> {
                huStatistic = PbText.render(m.body, Schemas.statisticsInfo)
                AppLog.i("== STATISTIC_INFO: $huStatistic")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "STATISTIC_INFO", "channel" to PbReader.str(it, 4))
                    bridge.onHuStatistic(PbReader.str(it, 4))
                }
                logDesay()
                for (c in Handshake.onStatisticInfo(sendFeatureConfigRequest = true)) sendCmd(c.type, c.body)
            }
            Msg.HU_AUTHEN_REQUEST -> {
                sawAuthRequest = true
                val f = PbReader.parse(m.body)
                val rnd = f?.firstOrNull { it.number == 1 && it.wire == 2 }?.data
                val rndStr = rnd?.toString(Charsets.UTF_8)
                authRandom = rnd?.let { PbText.quote(it) + " hex=" + Hex.compact(it, 64) }
                val v2 = Handshake.isAuthV2(rndStr)
                AppLog.i("== ГУ ШЛЁТ HU_AUTHEN_REQUEST randomValue=$authRandom")
                AppLog.i("== AUTHEN randomValue ${if (v2) "начинается с \"2.0;\"" else "не начинается с \"2.0;\""}")
                CarLifeState.auth = "HU_AUTHEN_REQUEST пришёл, ${if (v2) "2.0;" else "не 2.0;"}"
                // 同 Baidu 8.6.7 (PrivateKeyManager.encryptContentProcess)：收到请求立即用 Baidu 私钥签名，
                // 对 "2.0;" 使用 MD_INFO 的 md5 码；MD_AUTHEN_RESULT{true} 仍在 STATISTIC_INFO 之后发送
                val enc = if (rndStr == null) null else try {
                    HuAuth.encryptValue(rndStr, mdInfoKey)
                } catch (e: Exception) {
                    AppLog.e("подпись HU_AUTHEN_REQUEST", e)
                    null
                }
                if (enc == null) {
                    AppLog.i("на HU_AUTHEN_REQUEST не отвечаю (${if (rndStr == null) "нет randomValue" else "подпись не вышла"})")
                } else {
                    if (rndStr != null && HuAuth.isV2Branch(rndStr)) AppLog.i("== AUTHEN 2.0: md5 от \"CARLIFE\" + \"$mdInfoKey\" + дата")
                    AppLog.i("== отвечаю MD_AUTHEN_RESPONSE encryptValue=$enc")
                    CarLifeState.auth += ", MD_AUTHEN_RESPONSE отправлен"
                    sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(enc))
                }
            }
            Msg.HU_FEATURE_CONFIG_RESPONSE -> onFeatureConfig(m)
            Msg.CARLIFE_DATA_SUBSCRIBE -> {
                val done = Msgs.subscribeDone(m.body)
                if (done == null) {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE не разобрался, не отвечаю (как Baidu)")
                } else {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE moduleID=${Msgs.subscribeIds(m.body)}, отвечаю DONE")
                    sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE_START, Msg.CARLIFE_DATA_SUBSCRIBE_STOP -> {
                val on = m.type == Msg.CARLIFE_DATA_SUBSCRIBE_START
                val ids = Msgs.subscribeIds(m.body)
                // 设置中已关闭导航：不传递 head unit 的订阅，NAVI_NEXT_TURN_INFO 不会发出
                val pass = ids.filter { it != SUB_NAVI || cfg.metaNavi }
                AppLog.i("== SUBSCRIBE_${if (on) "START" else "STOP"} moduleID=$ids" +
                    (if (pass.size != ids.size) ", навигация выключена в настройках" else ""))
                bridge.onCarlifeSubscribe(pass, on)
            }
            Msg.VIDEO_ENCODER_JPEG -> {
                AppLog.i("== ГУ просит JPEG-режим видео: отвечаю ACK, но шлю только H.264")
                sendCmd(Msg.VIDEO_ENCODER_JPEG_ACK, null)
            }
            Msg.REQUEST_MD_THEME_STATE -> {
                // 我们的主题 = head unit 最后发送的那个（AA 也显示它），CHANGE_MD_THEME 之前为浅色
                val mode = bridge.huTheme
                AppLog.i("== REQUEST_MD_THEME_STATE: отвечаю themeModes=$mode (${AaBridgeCore.themeName(mode)})")
                sendCmd(Msg.RESPONSE_MD_THEME_STATE, Msgs.themeState(mode))
            }
            Msg.CHANGE_MD_THEME -> {
                AppLog.i("== CHANGE_MD_THEME ${PbText.render(m.body, Schemas.themeState)}")
                bridge.onCarlifeTheme(PbReader.parse(m.body)?.let { PbReader.int(it, 1) })
            }
            Msg.HU_AUTHEN_RESULT -> {
                val f = PbReader.parse(m.body)
                val ok = f?.let { PbReader.int(it, 1) }
                huAuthResult = "authenResult=$ok"
                AppLog.i("== HU_AUTHEN_RESULT: $ok")
                CarLifeState.auth += " -> HU_AUTHEN_RESULT=$ok"
            }
            Msg.VIDEO_ENCODER_INIT -> onVideoInit(m)
            Msg.VIDEO_ENCODER_START -> onVideoStart()
            Msg.VIDEO_ENCODER_PAUSE -> {
                paused = true
                if (pauseHeartbeatHu) { // 第一个 heartbeat 立即发送；Desay 走 USB - 同以前，2 秒后
                    lastVideoTx = 0L
                    pauseHeartbeat()
                }
                bridge.onCarlifePause()
                AppLog.i("видео: PAUSE, кадры не шлю")
            }
            Msg.VIDEO_ENCODER_RESET -> {
                AppLog.i("видео: RESET, прошу ключевой кадр")
                source?.requestKeyFrame()
                bridge.onCarlifeKeyFrameRequest()
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                // 同 VideoMsgHandler.changeFrameRate：3..30 之外静默忽略，否则以相同 fps 回 DONE
                val fps = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: -1
                if (Handshake.frameRateChangeAllowed(fps)) {
                    onFrameRateChange(fps)
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                } else {
                    AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps вне 3..30, игнор (как Baidu)")
                }
            }
            Msg.MODULE_CONTROL -> {
                // head unit 查询模块状态 (moduleID=3 MUSIC；Desay G6S 在握手后以及回到 CarLife 音乐时发送一串 status=1)：
                // 我们按 MEDIA 是否实际在播放回答，或按桥接层的决定
                val f = PbReader.parse(m.body)
                val module = f?.let { PbReader.int(it, 1) }
                val status = f?.let { PbReader.int(it, 2) }
                if (module == CarlifeAudio.MODULE_MUSIC && AaBridgeCore.MODULE_MUSIC_ENABLED) {
                    val on = bridge.onCarlifeMusicControl(status)
                    AppLog.i("== MODULE_CONTROL MUSIC status=$status, отвечаю MODULE_STATUS MUSIC=${if (on) 1 else 0}", ui = false)
                    sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (on) 1 else 0)
                } else if (module == MODULE_VR) {
                    // head unit/方向盘上的语音键：Baidu 在 VR=1 时打开语音输入 (openVrAndDialog)，0/3 时关闭
                    val sinceOwn = SystemClock.elapsedRealtime() - lastOwnVrOnMs
                    when {
                        status != 1 -> AppLog.i("== MODULE_CONTROL VR status=$status: игнор")
                        sinceOwn < VR_ECHO_MS -> AppLog.i("== MODULE_CONTROL VR=1 через $sinceOwn мс после нашего VR=1: эхо, игнор")
                        else -> bridge.onCarlifeVoiceKey()
                    }
                }
            }
            Msg.BT_HFP_CONNECTION -> {
                // 字段 1: 2 = 手机通过 BT 连接 head unit，0 = 断开；连接后 AA 自行启动自动播放
                val state = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                bridge.onHuBtHfpConnection(state)
            }
            Msg.MD_EXIT -> close("ГУ прислало MD_EXIT")
            else -> Unit // 其余所有内容仅记入日志
        }
    }

    /** 自动：转码器目标 fps = max(head unit fps, 10) 动态调整；固定设置：只回 DONE。passthrough 不处理（丢弃 P 帧会破坏流）。 */
    private fun onFrameRateChange(fps: Int) {
        if (!bridge.transcodeEnabled) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, passthrough: частота не регулируется, энкодер AA не трогаю")
            return
        }
        val fixed = cfg.fpsSetting.fixed
        if (fixed != null) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, настройка фиксирована на $fixed: отвечаю DONE, частоту не меняю")
            return
        }
        val wanted = maxOf(fps, Handshake.MIN_APPLIED_FPS)
        val applied = Handshake.capFps(wanted, huMaxFps())
        val old = targetFps
        targetFps = applied
        val capped = thermalThrottle.capFps(applied)
        val live = bridge.setTargetFps(capped)
        val hu = if (applied != wanted) ", потолок ГУ $applied" else ""
        val heat = if (capped != applied) ", из-за нагрева $capped" else ""
        AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, целевой fps $old -> $applied$hu$heat${if (live) "" else " (транскодер ещё не создан, применится при старте)"}")
    }

    private fun onVideoInit(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val rw = f?.let { PbReader.int(it, 1) } ?: 0
        val rh = f?.let { PbReader.int(it, 2) } ?: 0
        val rf = f?.let { PbReader.int(it, 3) } ?: 0
        requested = "${rw}x$rh fps=$rf"
        if (rw > 0 && rh > 0) onHuSize(rw, rh)
        AppLog.i("== ГУ ПРОСИТ РАЗРЕШЕНИЕ: ${rw}x$rh fps=$rf")
        WLog.ev(WLog.HS, "VIDEO_ENCODER_INIT", "size" to "${rw}x$rh", "fps" to rf)
        if (videoInited && pendingW > 0) {
            // 同 VideoMsgHandler (:84-88)：重复 INIT -> 仅以原尺寸回 INIT_DONE
            AppLog.i("== повторный INIT, отвечаю INIT_DONE ${pendingW}x$pendingH fps=$pendingFps")
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
            return
        }
        // 尺寸由视频模式决定（默认 1280x720 fps 30：Honda head unit 接受并完整显示，已在实车验证）
        val (w, h, why) = Triple(mode.outW, mode.outH, "мост: режим ${mode.label}")
        val fps = Handshake.chooseFps(cfg.fpsSetting, rf, huMaxFps())
        pendingW = w
        pendingH = h
        pendingFps = fps
        targetFps = fps
        videoInited = true
        chosen = "${w}x$h ($why)"
        AppLog.i("== ОТВЕЧАЮ INIT_DONE ${w}x$h fps=$fps ($why; ГУ просит fps=$rf, настройка ${cfg.fpsSetting.name}${if (bridge.transcodeEnabled) "" else ", passthrough: частота не регулируется"})")
        CarLifeState.video = "ГУ просит $requested, кодирую ${w}x$h"
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(w, h, fps))
        sendCmd(Msg.FOREGROUND, null)
        // VW Tharu XR 2026 (CarLife 2.1) 在 INIT_DONE 后不发送 START，等待视频并在约 5.5 秒后断开 USB
        try {
            sched.schedule({
                if (!closed.get() && !carlifeStarted) {
                    AppLog.i("== START не пришёл за ${Handshake.START_FALLBACK_MS} мс после INIT_DONE: стартую видео сам")
                    onVideoStart()
                }
            }, Handshake.START_FALLBACK_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // 会话已在关闭
        }
    }

    private var desayLogged = false

    private fun logDesay() {
        if (!bridge.huDesayG6S || desayLogged) return
        desayLogged = true
        AppLog.i("== ГУ Desay G6S: маршрутный NAVI=1 выключен")
    }

    private fun onFeatureConfig(m: CmdMsg) {
        val fc = FeatureConfig.parse(m.body)
        if (fc == null) {
            AppLog.i("== FEATURE_CONFIG не разобрался: ${Hex.compact(m.body, 64)}")
            return
        }
        huFeature = fc.items.joinToString(" ") { "${it.first}=${it.second}" }
        bridge.onHuFeatureConfig(fc.value("AUDIO_TRANSMISSION_MODE"))
        AppLog.i("== HU FEATURE_CONFIG (${fc.items.size} ключей), btAudio=${fc.huBtAudioSupport} btName=${fc.huBtName} btMAC=${fc.huBtMac}")
        for ((k, v) in fc.items) AppLog.i("   feature $k = $v")
        val enc = fc.value(FEATURE_CONTENT_ENCRYPTION)
        encryptionNeeded = enc?.let { it == 1 }
        AppLog.i("== CONTENT_ENCRYPTION=${enc ?: "нет в списке"}, CONNECT_TYPE=${fc.value("CONNECT_TYPE") ?: "нет"}")
        WLog.ev(WLog.HS, "FEATURE_CONFIG", "keys" to fc.items.size, "CONNECT_TYPE" to fc.value("CONNECT_TYPE"), "CONTENT_ENCRYPTION" to enc)
        if (enc == 1) {
            AppLog.i("== !!! ГУ ТРЕБУЕТ ШИФРОВАНИЕ КАНАЛА (CONTENT_ENCRYPTION=1): нужна цепочка RSA/AES (0x1006A..0x1006E), пока не реализована")
        }
    }

    /** START：AA 门控复位，占位器（若激活）以 SPS+PPS+IDR 启动。 */
    private fun onVideoStart() {
        paused = false
        if (pendingW <= 0) {
            pendingW = mode.outW
            pendingH = mode.outH
            chosen = "${mode.outW}x${mode.outH} (START без INIT, мост, режим ${mode.label})"
        }
        carlifeStarted = true
        synchronized(videoLock) {
            csdPending = true
            lastVideoTx = SystemClock.elapsedRealtime()
        }
        CarLifeState.status = "видео: мост"
        bridge.onCarlifeStart()
        if (bannerText != null) startOrUpdatePlaceholder()
    }

    private fun startOrUpdatePlaceholder() {
        synchronized(srcLock) {
            val text = bannerText ?: return
            val cur = source
            if (cur != null) {
                cur.banner = text
                cur.requestKeyFrame()
                return
            }
            // Pioneer RM56E8B (h277), Mazda 20622111 (h359)：USB 上的占位器也不超过 head unit 上限（不调用 setEncoderBitrate：此处持有 srcLock）
            val stubBps = when {
                bridge.huPioneerRm56 -> minOf(mode.bitrate, AaBridgeCore.PIONEER_RM56_CEILING_BPS)
                bridge.huMazda20622111 -> minOf(mode.bitrate, AaBridgeCore.MAZDA_20622111_CEILING_BPS)
                else -> mode.bitrate
            }
            val s = VideoSource(pendingW, pendingH, pendingFps, if (air) bitratePolicy.current else stubBps, this)
            s.banner = text
            try {
                s.start()
                source = s
                AppLog.i("BRIDGE заглушка: энкодер ${s.encoderName} ${pendingW}x$pendingH, текст '$text'")
            } catch (e: Exception) {
                AppLog.e("не удалось запустить энкодер заглушки", e)
                s.stop()
            }
        }
    }

    override fun sendVideo(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            lastVideoTx = SystemClock.elapsedRealtime()
            sendVideoData(withFixedSps(payload))
            return true
        }
    }

    override val isAir: Boolean get() = air

    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            // 与占位器的 onFrame 共用同一把锁：AA-IDR 之后其任一帧都不再发出
            acceptEncoder = false
            lastVideoTx = SystemClock.elapsedRealtime()
            sendVideoData(withFixedSps(payload))
            return true
        }
    }

    /**
     * Mazda 20622111 (h238/h359)：VIDEO_DATA 超过约 16 KiB 时 head unit 不再整体读取 USB。帧被切成
     * [AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET] 字节的片段，每段一个连续的 VIDEO_DATA（预期 head unit
     * 在解码器之前拼接流）。其他 head unit 仍像以前一样一帧一个包。
     */
    private fun sendVideoData(data: ByteArray) {
        val max = AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET
        if (!bridge.huMazda20622111 || data.size <= max) {
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), data))
            return
        }
        if (!splitLogged) {
            splitLogged = true
            AppLog.i("видео: Mazda 20622111, кадр ${data.size} Б режу на ${(data.size + max - 1) / max} VIDEO_DATA по ≤ $max Б")
        }
        val ts = unixSeconds()
        var off = 0
        while (off < data.size) {
            val end = minOf(off + max, data.size)
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, ts, data.copyOfRange(off, end)))
            off = end
        }
    }

    /** 发往 head unit 的所有 H.264（桥接、接管、占位器）都经 AnnexB.fixSps：head unit 不接受 Exynos 的 SPS 签名。 */
    private fun withFixedSps(payload: ByteArray): ByteArray {
        val out = AnnexB.fixSps(payload, pendingW, pendingH)
        if (out !== payload && !spsFixLogged) {
            spsFixLogged = true
            val i = payload.indices.firstOrNull { payload[it] != out[it] } ?: return out
            val from = maxOf(0, i - 4)
            AppLog.i("видео: SPS в ГУ исправлен под ${pendingW}x$pendingH: ${Hex.compact(payload.copyOfRange(from, i + 4), 8)}" +
                " -> ${Hex.compact(out.copyOfRange(from, i + 4), 8)}")
        }
        return out
    }

    /**
     * 视频队列溢出：排队帧超过 30 帧，或其字节数超过约 500 毫秒的排出量（排出量未测得时为 512 KB）。
     * 在添加新帧之前检查：空队列中 IDR 始终被接受。拒绝 = 调用方丢弃该帧
     * 并等待 IDR（桥接：outGotIdr=false；占位器：csdPending=true），丢失帧之后的 P 帧不能发送。
     */
    private fun videoOverflow(): Boolean {
        val over = VideoQueueLimit.overflow(transport.queuedVideoFrames, transport.queuedVideoBytes, transport.drainBytesPerSec)
        if (over) {
            lastOverflowMs = SystemClock.elapsedRealtime()
            overflows++
        }
        return over
    }

    override fun videoStats(): LinkVideoStats {
        val bytes = transport.queuedVideoBytes
        val rate = transport.drainBytesPerSec
        return LinkVideoStats(rate, VideoQueueLimit.backlogMs(bytes, rate), transport.queuedVideoFrames, bytes)
    }

    /**
     * 编码器比特率自适应（每 100 毫秒一次）：积压和排出量 -> BitratePolicy -> 实时 MediaCodec.setParameters。
     * 仅转码有效：passthrough 下 AA 的 H.264 原样发送，比特率不会改变。
     */
    private fun adaptBitrate() {
        try {
            if (closed.get() || !carlifeStarted) return
            if (!bridge.transcodeEnabled) {
                if (!passthroughBitrateLogged) {
                    passthroughBitrateLogged = true
                    AppLog.i("видео: passthrough, адаптация битрейта недоступна (H.264 AA идёт как есть)")
                }
                return
            }
            // 无线时策略也驱动占位器（AA 启动期间以 CBR 跑在上限）；USB 上不动占位器
            val placeholder = if (air && !bridge.isTranscoding) source else null
            if (!bridge.isTranscoding && placeholder == null) return
            val now = SystemClock.elapsedRealtime()
            val rate = transport.drainBytesPerSec
            val qf = transport.queuedVideoFrames
            if (qf > maxQueueFrames) maxQueueFrames = qf
            val real = VideoQueueLimit.backlogMs(transport.queuedVideoBytes, rate)
            val sinceOverflow = lastOverflowMs.let { if (it == NEVER_MS) -1L else now - it }
            applyHuCeilings()
            val old = bitratePolicy.current
            val bps = bitratePolicy.update(now, VideoQueueLimit.policyBacklogMs(real, sinceOverflow), rate) ?: return
            if (placeholder?.setBitrate(bps) ?: bridge.setEncoderBitrate(bps)) {
                val who = if (placeholder != null) "заглушки" else "энкодера"
                AppLog.i("видео: битрейт $who ${old / 1000} -> ${bps / 1000} кбит/с (${bitratePolicy.lastReason}; потолок ${bitratePolicy.ceilingBps / 1000})")
                if (air) WLog.ev(WLog.VIDEO, "bitrate", "fromKbps" to old / 1000, "toKbps" to bps / 1000,
                    "src" to if (placeholder != null) "placeholder" else "aa", "why" to bitratePolicy.lastReason)
            }
        } catch (e: Exception) {
            AppLog.e("адаптация битрейта", e)
        }
    }

    /**
     * VW ID (h196)：IDR 超过约 600 KiB 时 head unit 以 EIO 断开 USB。在 head unit 标志下转码上限为 2 Mbit/s（标志可能
     * 在编码器启动后才到达 - 比特率实时下调）。4 Mbit/s 时会话在前 10-15 秒内就会断开，直到保险机制
     * 降低上限；一趟行程中它自行收敛到 1.7 Mbit/s。其他 head unit 不受影响。
     */
    private fun applyVwCeiling() {
        if (!bridge.huVw20082138) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(VW_20082138_CEILING_BPS)) return
        AppLog.i("VW 20082138: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с (лимит размера IDR)")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /**
     * Pioneer RM56E8B (h277)：4-5 Mbit/s 时 head unit 在 25-30 秒内以 EIO 断开 USB；约 2 Mbit/s 撑了 195 秒，仅 800 kbit/s 稳定。
     * 在 head unit 标志下上限为 1 Mbit/s（HU_INFO 在 INIT 之前到达，但上限也可实时应用）。热上限
     * ([BitratePolicy.thermalCapBps]) 独立存在，在 update() 中通过 min 起作用，因此与之不冲突。
     */
    private fun applyPioneerCeiling() {
        if (!bridge.huPioneerRm56) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(AaBridgeCore.PIONEER_RM56_CEILING_BPS)) return
        AppLog.i("Pioneer RM56E8B: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** Mazda 20622111 (h238/h359)：上限 1.5 Mbit/s，让 IDR 较少超出 head unit 的包限制。 */
    private fun applyMazda20622111Ceiling() {
        if (!bridge.huMazda20622111) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(AaBridgeCore.MAZDA_20622111_CEILING_BPS)) return
        AppLog.i("Mazda 20622111: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    private fun applyHuCeilings() {
        applyVwCeiling()
        applyPioneerCeiling()
        applyMazda20622111Ceiling()
    }

    /** head unit 的 fps 上限 (Pioneer RM56E8B: 15)，null = 无限制。 */
    private fun huMaxFps(): Int? = if (bridge.huPioneerRm56) AaBridgeCore.PIONEER_RM56_MAX_FPS else null

    /** VW ID 保险：IDR > 450 KB 时上限 ×0.75（不低于 1.5 Mbit/s），每秒至多一次；帧已发出。 */
    override fun onEncodedKeyFrame(size: Int) {
        if (size <= VW_20082138_IDR_LIMIT) return
        val now = SystemClock.elapsedRealtime()
        if (vwIdrCutMs != NEVER_MS && now - vwIdrCutMs < VW_20082138_CUT_INTERVAL_MS) return
        applyVwCeiling()
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(maxOf(VW_20082138_MIN_BPS, (bitratePolicy.ceilingBps * 0.75).toInt()))) return
        vwIdrCutMs = now
        AppLog.i("VW 20082138: IDR $size Б > $VW_20082138_IDR_LIMIT, потолок битрейта -> ${bitratePolicy.ceilingBps}")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** 桥接音频：MEDIA/TTS 通道，12 字节头；DATA 中 time = millis & 0xFFFFFFFF，INIT/STOP 中为 0（同 Baidu）。 */
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        if (payload.isNotEmpty() && transport.queueSize(channel) > MAX_AUDIO_QUEUE) return false   // 音频不应在队列中挤掉视频
        val isData = type == CarlifeAudio.MEDIA_DATA || type == CarlifeAudio.TTS_DATA || type == CarlifeAudio.VR_DATA
        val time = if (isData) (System.currentTimeMillis() and 0xFFFFFFFFL).toInt() else 0
        send(channel, Wire.dataInner(type, time, payload))
        return true
    }

    /** 关于单个模块的 MODULE_STATUS（TTS 前发 NAVI，MEDIA INIT/PAUSE 时发 MUSIC），同 Baidu ModuleStatusManage。 */
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (closed.get()) return false
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(module, status))
        return true
    }

    /** CMD GO_TO_DESKTOP (MD -> head unit，空数据体)：head unit 显示自己的桌面。 */
    override fun goToDesktop() {
        if (closed.get()) return
        AppLog.i("-> ГУ: GO_TO_DESKTOP")
        sendCmd(Msg.GO_TO_DESKTOP, null)
    }

    /**
     * head unit 上的麦克风录音，同 Baidu MD：启动 = MIC_RECORD_RECOG_START (VrManager.sendStartRecogMsgToVehicle)
     * 和 MODULE_STATUS VR=1 (prepareShowVr)；停止 = MIC_RECORD_END (sendRecordEndMsgToVehicle) 和 VR=0 (prepareCloseVr)。
     */
    override fun requestMicRecord(start: Boolean): Boolean {
        if (closed.get()) return false
        if (start) {
            micRxPackets = 0
            micRxBytes = 0
            AppLog.i("MIC -> ГУ: MIC_RECORD_RECOG_START${if (cfg.micVrStatus) " + MODULE_STATUS VR=1" else ""}")
            sendCmd(Msg.MIC_RECORD_RECOG_START, null)
            if (cfg.micVrStatus) {
                lastOwnVrOnMs = SystemClock.elapsedRealtime()
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 1))
            }
        } else {
            AppLog.i("MIC -> ГУ: MIC_RECORD_END${if (cfg.micVrStatus) " + MODULE_STATUS VR=0" else ""}; с ГУ принято $micRxPackets пакетов, $micRxBytes Б")
            sendCmd(Msg.MIC_RECORD_END, null)
            if (cfg.micVrStatus) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 0))
        }
        return true
    }

    // ---------------- AA 元数据 -> CarLife ----------------
    // 三个消息都经过同一条 metaExec 线程：MEDIA_INFO -> PROGRESS 的顺序得以保持，而封面转码
    // 和箭头绘制不会占用桥接监视器（它们由持有监视器的 AA 会话线程调用）。

    private fun metaSubmit(what: String, task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            metaExec.execute {
                try {
                    if (!closed.get()) task()
                } catch (e: Throwable) {
                    AppLog.e("метаданные: $what", e)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    override fun sendMediaInfo(info: CarlifeMediaInfo): Boolean {
        if (!cfg.metaMusic) {
            if (!metaMusicOffLogged) AppLog.i("метаданные: музыка выключена в настройках, MEDIA_INFO не шлю")
            metaMusicOffLogged = true
            return false
        }
        return metaSubmit("MEDIA_INFO") {
            val art = info.art
            val jpeg = when {
                art == null || art.isEmpty() -> ByteArray(0)
                art.contentEquals(lastArtSrc) -> lastArtJpeg
                else -> {
                    val t = SystemClock.elapsedRealtime()
                    // Mazda 20622111 (h359)：包超过约 16 KiB 会挂起 USB，24 KB 的封面在图片出现 3 秒后杀死了会话
                    val limit = if (bridge.huMazda20622111) AaBridgeCore.MAZDA_20622111_ART_BYTES else MetaImages.MAX_BYTES
                    val j = MetaImages.albumJpeg(art, limit) ?: ByteArray(0)
                    AppLog.i("метаданные: обложка ${PbText.blob(art)} -> ${PbText.blob(j)} за ${SystemClock.elapsedRealtime() - t} мс", ui = false)
                    lastArtSrc = art
                    lastArtJpeg = j
                    j
                }
            }
            sendCmd(Msg.MEDIA_INFO, Msgs.mediaInfo(info, jpeg))
        }
    }

    override fun sendMediaProgress(progressBar: Int): Boolean {
        if (!cfg.metaMusic) return false
        return metaSubmit("MEDIA_PROGRESS_BAR") { sendCmd(Msg.MEDIA_PROGRESS_BAR, Msgs.mediaProgress(progressBar)) }
    }

    private var hondaRoadLogged = false

    /** Honda (channel 2077xxxx/2083xxxx)：roadName 适配到 ≤24 格 / ≤31 字节，否则仪表盘丢弃该字符串。其他 head unit - 不变。 */
    private fun fitRoad(info: NaviTurnInfo): NaviTurnInfo {
        if (!bridge.huHonda) return info
        if (!hondaRoadLogged) {
            hondaRoadLogged = true
            AppLog.i("== Honda приборка: roadName подгоняю под ≤24 клетки / ≤31 Б")
        }
        val road = HondaClusterRoad.fit(info.roadName)
        if (road == info.roadName) return info
        AppLog.i("метаданные: road→\"$road\" (${road.toByteArray(Charsets.UTF_8).size} Б)", ui = false)
        return NaviTurnInfo(info.action, info.nextTurn, road, info.totalDistance, info.remainDistance, info.timeSeconds)
    }

    override fun sendNaviTurn(info: NaviTurnInfo): Boolean {
        if (!cfg.metaNavi) return false
        val sent = fitRoad(info)
        return metaSubmit("NAVI_NEXT_TURN_INFO") {
            // 路线结束 (action 3) - 图标为空，同 Baidu onNaviStateChange(false)
            val icon = if (!cfg.naviField6.icon || info.action == 3) ByteArray(0) else turnIcons.getOrPut(info.nextTurn) {
                MetaImages.turnIcon(info.nextTurn).also {
                    AppLog.i("метаданные: стрелка nextTurn=${info.nextTurn} ${PbText.blob(it)}", ui = false)
                }
            }
            sendCmd(Msg.NAVI_NEXT_TURN_INFO, Msgs.naviNextTurn(sent, cfg.naviField6, icon))
        }
    }

    /** 桥接转码器：AA 解码器（模式帧）-> 裁剪 -> 编码器 Surface（模式输出）。MediaCodec 未启动时为 null。 */
    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = try {
        applyHuCeilings()
        AaTranscoder(
            mode.outW, mode.outH, thermalThrottle.capFps(targetFps), bitratePolicy.current, out, { AppLog.i(it, false) },
            srcWidth = mode.aaW, srcHeight = mode.aaH, crop = mode.crop,
        ).also { it.start() }
    } catch (e: Exception) {
        AppLog.e("BRIDGE транскодер не создан", e)
        null
    }

    override fun showPlaceholder(text: String) {
        bannerText = text
        synchronized(videoLock) {
            if (!acceptEncoder) csdPending = true
            acceptEncoder = true
        }
        if (carlifeStarted && !closed.get()) {
            synchronized(videoLock) { csdPending = true }
            startOrUpdatePlaceholder()
        }
    }

    override fun placeholderSnapshot(): android.graphics.Bitmap? = try {
        synchronized(srcLock) { source }?.snapshot()
    } catch (e: Exception) {
        AppLog.e("снимок заглушки для перехода", e)
        null
    }

    override fun hidePlaceholder() {
        synchronized(videoLock) { acceptEncoder = false }
        bannerText = null
        val old = synchronized(srcLock) {
            val o = source
            source = null
            o
        }
        if (old != null) Thread({ old.stop() }, "cl-placeholder-stop").start()
    }

    // ---------------- TOUCH ----------------

    private fun onTouch(m: CmdMsg) {
        if (m.type == Msg.TOUCH_CAR_HARD_KEY_CODE) {
            val code = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
            AppLog.i("== HARD KEY keycode=$code")
            if (code != null) bridge.onCarlifeHardKey(code)
            return
        }
        val t = CarlifeTouch.parse(m.type, m.body) ?: return
        setTouchMode(
            if (m.type == Msg.TOUCH_ACTION) "TOUCH_ACTION (прозрачный, TRANSPARENT_SEND_TOUCH_EVENT=true)"
            else "жестовый (отдельные коды 0x68002..0x6800D)"
        )
        touchCount++
        val n = t.fingers
        if (n > 1 && maxFingers < 2) AppLog.i("== МУЛЬТИТАЧ: ГУ шлёт второй палец (action=${t.action} тело=${m.body.size} байт)")
        maxFingers = maxOf(maxFingers, n)
        // 供捏合缩放清单使用：原始 action、手指数、数据体长度；仅手指数变化时记录 MOVE，否则日志会爆炸
        if (t.masked != 2 || n != lastMoveFingers) {
            AppLog.i("TOUCH action=${t.action} пальцев=$n тело=${m.body.size} байт", false)
            lastMoveFingers = if (t.masked == 2) n else 0
        }
        if (t.masked in TOUCH_TO_AA) bridge.onCarlifeTouch(t.action, t.x, t.y, t.px, t.py)
        CarLifeState.touch = "$touchMode; событий=$touchCount; пальцев макс=$maxFingers; последний act=${t.action} x=${t.x} y=${t.y}" +
            (if (n > 1) " px=${t.px} py=${t.py}" else "")
    }

    private fun setTouchMode(mode: String) {
        if (touchMode != mode) {
            touchMode = mode
            AppLog.i("== РЕЖИМ ТАЧА: $mode")
        }
    }

    // ---------------- 写入 ----------------

    private fun sendCmd(type: Int, body: ByteArray?) = send(Ch.CMD, Wire.cmdInner(type, body))

    private fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        if (bridge.huMazda20622111 && inner.size > AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET + 12) {
            // h359 保险：VIDEO_DATA 已切片，其余超过 head unit 限制的内容不发送 - 否则 head unit 会停止读取 USB
            AppLog.i("Mazda 20622111: пакет ch$chan ${inner.size} Б больше лимита ГУ, не отправляю")
            return
        }
        transport.send(chan, inner)
    }

    /** 写入传输层之后（写线程）。 */
    private fun logTx(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner) ?: return
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            // 曲目进度 - 每秒一次，距离更新 (action 2) - 同样：写入文件，不进 UI
            val quiet = m.type == Msg.MEDIA_PROGRESS_BAR ||
                (m.type == Msg.NAVI_NEXT_TURN_INFO && PbReader.parse(m.body)?.let { PbReader.int(it, 1) } == 2)
            AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet)
        } else {
            val m = Wire.parseData(inner) ?: return
            if (chan != Ch.VIDEO) { // 桥接音频：INIT/STOP 始终记录，DATA 记前几个和每第 200 个
                audioTx++
                if (m.type == CarlifeAudio.TTS_INIT) ttsTxSinceInit = 0
                val ttsHead = m.type == CarlifeAudio.TTS_DATA && ++ttsTxSinceInit <= TTS_TX_LOGGED
                if (m.data.size <= 16 || audioTx <= 3 || audioTx % 200 == 0L || ttsHead) {
                    AppLog.i(
                        "TX ${Ch.name(chan)} ${Msg.hex(m.type)} ${Msg.name(m.type)} len=${m.data.size} time=${m.time} #$audioTx" +
                            (if (m.data.size <= 16) " ${Hex.compact(m.data, 16)}" else ""), ui = false
                    )
                }
                return
            }
            if (m.data.isEmpty()) { // PAUSE 时的 keepalive：空 VIDEO_DATA (Desay) 或 VIDEO_HEARTBEAT (VW 20082120)
                vBeats++
                if (vBeats <= 3) AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, null, inner)
                return
            }
            vFrames++
            vBytes += m.data.size
            if (vFrames == 1L) WLog.ev(WLog.HS, "first-video", "bytes" to m.data.size)
            val key = AnnexB.nalTypes(m.data).contains(5)
            if (key) vKeys++
            val nals = AnnexB.nalTypes(m.data)
            if (vFrames <= 5 || key) {
                AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, "nal=$nals", inner, ui = vFrames <= 5)
            }
        }
    }

    // ---------------- 编码器视频 ----------------

    override fun onConfig(csd: ByteArray) {
        this.csd = csd
        val types = AnnexB.nalTypes(csd)
        var prof = ""
        if (csd.size > 8 && types.firstOrNull() == 7) {
            // [00 00 00 01][SPS 头][profile_idc][constraints][level_idc]
            prof = " profile_idc=${csd[5].toInt() and 0xFF} constraints=0x%02X level_idc=${csd[7].toInt() and 0xFF}".format(csd[6].toInt() and 0xFF)
        }
        AppLog.i("энкодер: codec config ${csd.size} байт nal=$types$prof")
        AppLog.detail(Hex.dump(csd, 128))
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || paused) return
        synchronized(videoLock) {
            if (!acceptEncoder) return   // 占位器已隐藏（AA 视频在播放）：编码器剩余帧不再需要
            onEncoderFrame(data, key)
        }
    }

    private fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (!key && csdPending) {
            vDropped++
            return // 第一个 IDR 之前不发送任何内容（第一个包必须以 SPS 开头）
        }
        if (videoOverflow()) {
            // 帧已丢失：其后的 P 帧不能发送，重新等待 IDR (csdPending)，IDR 请求每 1.5 秒至多一次
            vDropped++
            csdPending = true
            if (placeholderIdr.tryRequest(SystemClock.elapsedRealtime())) source?.requestKeyFrame()
            return
        }
        var payload = data
        if (key) {
            val c = csd
            if (csdPending && c != null && !AnnexB.startsWithSps(data)) payload = AnnexB.glue(c, data)
            csdPending = false
        }
        lastVideoTx = SystemClock.elapsedRealtime()
        sendVideoData(withFixedSps(payload))
    }

    override fun onError(msg: String, t: Throwable?) {
        AppLog.e(msg, t)
        CarLifeState.status = "ОШИБКА: $msg: ${t?.message}"
    }

    // ---------------- 定时器 ----------------

    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L

    // 视频播放期间没有空的 keepalive：帧由编码器提供 (repeat-previous-frame-after 100 毫秒)。
    // PAUSE 时（head unit 在自己的菜单/自己的音源上）通道否则会静默：
    // - Desay G6S (Mazda CX-5) 约 22 秒静默后断开 USB (EIO, Ivan 的日志 2026-10-12)：空 VIDEO_DATA 0x20001，
    //   同 Baidu sendEmptyPacketBackground，USB 每 2 秒一次，无线约每 1 秒一次（Wi-Fi 上的 head unit 断开视频 socket 更快）；
    // - VW channel 20082120 在 PAUSE 后 3.6-4 秒断开通道，即使每 1-2 秒发一次 0x20001 (h93, 日志 2026-10-15 USB
    //   和 2026-10-16 Wi-Fi)，带 FOREGROUND 的重连会把用户从 head unit 菜单拽回 CarLife：VIDEO_HEARTBEAT 0x20002 立即
    //   发送且每 100 毫秒一次，同 Baidu Recorder.sendEmptyPacketBackground (~50 毫秒)。VW CNS3 channel 20082122 相同，
    //   约 5.6 秒 (h222, 2026-10-17 USB)。
    // - 无线上的 Desay G6S (h211, 倒车)：每 900 毫秒一次的空 VIDEO_DATA 撑不住，24-28 秒后 head unit 断开
    //   Wi-Fi Direct；只撑得住 25 秒以内的暂停。Baidu 在暂停时发送的正是 VIDEO_HEARTBEAT (Recorder:906-926)，
    //   我们设为每 100 毫秒一次，USB 上 Desay 仍使用 VIDEO_DATA。
    /**
     * PAUSE 时需要每 100 毫秒一次 VIDEO_HEARTBEAT 的 head unit：VW 20082120/20082122 (h93, h222)、无线的 VW BT 1420
     * (Pavel 2026-10-18：PAUSE 下无 keepalive 时 head unit 约 1 分钟后关闭 socket，用空 VIDEO_DATA 则在 START 时断开)
     * 以及无线的 Desay G6S (h211)。
     */
    private val pauseHeartbeatHu: Boolean
        get() = bridge.huVw20082120 || (air && (bridge.huVwBt1420 || bridge.huDesayG6S))

    private fun pauseHeartbeat() {
        if (closed.get() || !PAUSE_KEEPALIVE_ENABLED) return
        if (!paused) {
            idleHeartbeat()
            return
        }
        val (type, period) =
            if (pauseHeartbeatHu) Msg.VIDEO_HEARTBEAT to PAUSE_HEARTBEAT_MS else Msg.VIDEO_DATA to pauseKeepaliveMs
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoTx < period) return
        if (vBeats == 0L) AppLog.i("видео: PAUSE, шлю пустой ${Msg.name(type)} раз в $period мс, чтобы ГУ не рвало USB")
        lastVideoTx = now
        send(Ch.VIDEO, Wire.dataInner(type, unixSeconds(), ByteArray(0)))
    }

    /**
     * VW CNS2 Delphi 20082114 (h425, 日志 2026-10-24)：AA 在静态画面时不发送帧，6.4 秒无视频后 head unit
     * 断开 USB (EIO)，5.1 秒以内的暂停没问题。非 PAUSE 状态下 [IDLE_HEARTBEAT_MS] 无帧后发送 VIDEO_HEARTBEAT，
     * 与 PAUSE 时相同，并以同样间隔继续，直到 AA 恢复发送。
     */
    private fun idleHeartbeat() {
        if (!bridge.huVwCns2Delphi || !carlifeStarted || vFrames == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoTx < IDLE_HEARTBEAT_MS) return
        if (!idleBeatLogged) {
            idleBeatLogged = true
            AppLog.i("видео: AA молчит ${now - lastVideoTx} мс, шлю пустой VIDEO_HEARTBEAT раз в $IDLE_HEARTBEAT_MS мс, чтобы ГУ не рвало USB")
        }
        lastVideoTx = now
        send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_HEARTBEAT, unixSeconds(), ByteArray(0)))
    }

    private fun tick() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        checkThermal(now)
        if (transport.rxBytes == 0L && now - t0 > SILENCE_MS && (now - t0) % 10_000 < 1000) {
            AppLog.i("ГУ не прислало ни одного байта за ${(now - t0) / 1000} с (ждём HU_PROTOCOL_VERSION)")
            CarLifeState.status = "ГУ молчит, байт от него нет"
        }
        if (now - lastStatsAt >= 5000 && (vFrames > 0 || vBeats > 0)) {
            AppLog.i("видео: отправлено кадров=$vFrames (ключевых=$vKeys) байт=$vBytes heartbeat=$vBeats сброшено=$vDropped очередь=${transport.queueSize(Ch.VIDEO)} (+${vFrames - lastStatsFrames} за ${(now - lastStatsAt) / 1000}с)")
            lastStatsAt = now
            lastStatsFrames = vFrames
            CarLifeState.video = "$chosen; кадров=$vFrames hb=$vBeats"
        }
    }

    /**
     * 每秒一次：发热状态（headroom 预测 - 每 10 秒一次，更频繁时 PowerManager 返回 NaN）-> ThermalThrottle。
     * 档位变化时 - 实时调整转码的 fps 和比特率上限。passthrough 下无可降载，仅记录日志。
     */
    private fun checkThermal(now: Long) {
        try {
            val status = thermal.status()
            if (headroomAtMs == NEVER_MS || now - headroomAtMs >= HEADROOM_POLL_MS) {
                headroomAtMs = now
                thermalHeadroom = thermal.headroom()
            }
            if (status != thermalStatus) {
                AppLog.i("нагрев: статус телефона ${thermalName(thermalStatus)} -> ${thermalName(status)} (headroom ${headroomText()})")
                thermalStatus = status
            }
            val level = thermalThrottle.update(now, status, thermalHeadroom) ?: return
            applyThermal(level)
        } catch (e: Exception) {
            AppLog.e("нагрев", e)
        }
    }

    private fun applyThermal(level: ThermalLevel) {
        val why = "статус ${thermalName(thermalStatus)}, headroom ${headroomText()}"
        if (!bridge.transcodeEnabled) {
            if (level != ThermalLevel.NORMAL && !thermalPassthroughLogged) {
                thermalPassthroughLogged = true
                AppLog.i("нагрев: ступень ${level.name} ($why), но видео passthrough: fps и битрейт не регулируются")
            }
            return
        }
        val fps = thermalThrottle.capFps(targetFps)
        val live = bridge.setTargetFps(fps)
        val old = bitratePolicy.current
        val cap = if (level == ThermalLevel.NORMAL) Int.MAX_VALUE else thermalThrottle.capBitrate(bitratePolicy.ceilingBps)
        if (bitratePolicy.setThermalCap(cap)) bridge.setEncoderBitrate(bitratePolicy.current)
        val capText = if (cap == Int.MAX_VALUE) "снят" else "${bitratePolicy.thermalCapBps / 1000} кбит/с"
        AppLog.i("нагрев: ступень ${level.name} ($why): fps $fps (ГУ/настройка $targetFps), потолок битрейта $capText, " +
            "битрейт ${old / 1000} -> ${bitratePolicy.current / 1000} кбит/с${if (live) "" else " (транскодера нет, применится при старте)"}")
        if (air) WLog.ev(WLog.VIDEO, "thermal", "level" to level.name, "status" to thermalStatus, "fps" to fps,
            "capKbps" to if (cap == Int.MAX_VALUE) 0 else bitratePolicy.thermalCapBps / 1000)
    }

    private fun headroomText(): String = if (thermalHeadroom.isNaN()) "н/д" else "%.2f".format(thermalHeadroom)

    private fun thermalName(status: Int): String = THERMAL_NAMES.getOrElse(status) { "$status" }

    private fun logSummary(reason: String) {
        val sb = StringBuilder("== РЕЗЮМЕ СЕССИИ (${(SystemClock.elapsedRealtime() - t0) / 1000} с, причина: $reason)\n")
        sb.append("  принято байт: ${transport.rxBytes}\n")
        sb.append("  версия протокола ГУ: $huVersion\n")
        sb.append("  HU_AUTHEN_REQUEST: ${if (sawAuthRequest) "ПРИШЁЛ randomValue=$authRandom" else "не приходил"}\n")
        sb.append("  HU_AUTHEN_RESULT: ${huAuthResult ?: "не приходил"}\n")
        sb.append("  запрошенное разрешение: $requested; ответ: $chosen\n")
        sb.append("  тач: $touchMode, событий=$touchCount\n")
        sb.append("  видео: кадров=$vFrames ключевых=$vKeys heartbeat=$vBeats сброшено=$vDropped\n")
        sb.append("  нагрев: статус ${thermalName(thermalStatus)}, макс. ступень ${thermalThrottle.maxLevel.name}, разгрузка ${thermalThrottle.throttledMs / 1000} с\n")
        sb.append("  HU_INFO: ${huInfo ?: "не приходил"}\n")
        sb.append("  принятые типы: ")
        for ((t, c) in rxByType) sb.append("${Msg.hex(t)} ${Msg.name(t)} x$c; ")
        AppLog.i(sb.toString().trimEnd(), ui = false)
        AppLog.i("резюме записано в лог (сессия ${reason})")
    }

    private fun deviceInfo(): DeviceInfo {
        // SDK_INT >= 33：同 8.6.7 谎报 "29"/29
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(Build.VERSION.SDK_INT)
        return DeviceInfo(
            os = "Android",
            board = Build.BOARD,
            bootloader = Build.BOOTLOADER,
            brand = Build.BRAND,
            cpuAbi = Build.SUPPORTED_ABIS.getOrElse(0) { "" },
            cpuAbi2 = Build.SUPPORTED_ABIS.getOrElse(1) { "" },
            device = Build.DEVICE,
            display = Build.DISPLAY,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            host = Build.HOST,
            cid = Build.ID,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            serial = "unknown",
            codename = Build.VERSION.CODENAME,
            incremental = Build.VERSION.INCREMENTAL,
            release = Build.VERSION.RELEASE,
            sdk = sdk,
            sdkInt = sdkInt,
            btaddress = "unknown", // BT 地址我们拿不到；Baidu 未取得时发送 "unknown"
            carlifeVersion = Handshake.MD_VERSION,
            carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    companion object {
        const val FPS = Handshake.DEFAULT_FPS

        /** 桥接层转换为 AA 的触摸 action&255：DOWN, UP, MOVE, POINTER_DOWN, POINTER_UP。 */
        private val TOUCH_TO_AA = setOf(0, 1, 2, 5, 6)
        private const val TTS_TX_LOGGED = 8           // INIT 之后带写入传输层时间的 TTS_DATA (h222)
        private const val MAX_AUDIO_QUEUE = 180       // 公共队列中包更多时音频不被接受
        private const val BITRATE_POLL_MS = 100L
        private const val VW_20082138_CEILING_BPS = 2_000_000
        private const val VW_20082138_MIN_BPS = 1_500_000
        private const val VW_20082138_IDR_LIMIT = 450_000
        private const val VW_20082138_CUT_INTERVAL_MS = 1000L
        private const val NEVER_MS = Long.MIN_VALUE

        /** getThermalHeadroom 每秒多次会返回 NaN，10 秒预测 - 每 10 秒一次足够。 */
        private const val HEADROOM_POLL_MS = 10_000L

        /** PowerManager.THERMAL_STATUS_* 按顺序排列。 */
        private val THERMAL_NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        private const val SILENCE_MS = 10_000L
        private const val PAUSE_KEEPALIVE_MS = 2_000L  // Desay G6S: 空 VIDEO_DATA
        private const val PAUSE_HEARTBEAT_MS = 100L    // VW 20082120: 空 VIDEO_HEARTBEAT
        private const val IDLE_HEARTBEAT_MS = 1_000L   // Delphi 20082114: AA 超过一秒无帧

        /** 无线约每 1 秒一次：Wi-Fi 上的 head unit 断开视频 socket 更快 (计划 5.2 第 4 条)。 */
        private const val PAUSE_KEEPALIVE_AIR_MS = 900L

        /** PAUSE 时的 keepalive：空 VIDEO_DATA，VW 20082120 用 VIDEO_HEARTBEAT。 */
        @Volatile @JvmStatic var PAUSE_KEEPALIVE_ENABLED = true

        private const val FEATURE_CONTENT_ENCRYPTION = "CONTENT_ENCRYPTION"

        /** CARLIFE_DATA_SUBSCRIBE 导航订阅的 moduleID (CarlifeDataSubscribe: 0 导航, 2 音乐)。 */
        private const val SUB_NAVI = 0

        /** MODULE_STATUS 中语音助手的 moduleID (ModuleStatusManage: 4 = VR)。 */
        private const val MODULE_VR = 4
        private const val VR_ECHO_MS = 1500L

        /** 视频包的 time 字段：unix 秒 (Recorder.sendVideoData :930-962)。 */
        private fun unixSeconds() = (System.currentTimeMillis() / 1000).toInt()
    }
}
