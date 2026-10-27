package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 桥接层要求 CarLife 侧提供的能力（由 CarLifeSession 实现）。 */
interface CarlifeLink {
    /** 一条 CarLife 视频消息（0x20001，Annex-B）。false = 未接受（队列已满、会话已关闭）。 */
    fun sendVideo(payload: ByteArray): Boolean

    /**
     * 用来自 AA 的第一个 IDR 替换占位图：发送与关闭占位图帧原子完成。否则在 sendVideo 与 hidePlaceholder 之间
     * 漏过的占位图 P 帧会叠在 AA-IDR 之上，车机直到下一个 IDR 都会显示花屏。
     * 停止占位图仍统一通过 hidePlaceholder。
     */
    fun sendVideoTakeover(payload: ByteArray): Boolean = sendVideo(payload)

    /** 无线连接（6 个 TCP socket），而非 USB / tcp-debug。 */
    val isAir: Boolean get() = false

    /** 当前占位图帧（null = 没有）：转码从这里开始过渡到 AA 画面。 */
    fun placeholderSnapshot(): android.graphics.Bitmap? = null

    /** 在车机上显示带文字的占位图（对 VideoSource 编码）；可在 CarLife START 之前调用——会被记住。 */
    fun showPlaceholder(text: String)

    /** 停止占位图：之后视频来自 AA（sendVideo）。 */
    fun hidePlaceholder()

    /** CarLife 音频消息：通道 3 MEDIA / 4 TTS，类型（INIT/DATA/STOP），数据体。false = 被丢弃。 */
    fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean

    /** 关于某个 CarLife 模块的 MODULE_STATUS（CarlifeAudio.MODULE_NAVI / MODULE_MUSIC / ...），同百度 ModuleStatusManage。false = 未发出。 */
    fun sendModuleStatus(module: Int, status: Int): Boolean

    /** 创建并启动转码器（AA H.264 → 解码器 → 编码器）。null = MediaCodec 未能启动，走 passthrough。 */
    fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = null

    /**
     * 请求车机开始（true）/结束（false）录制麦克风，同百度 MD（VrManager）：CMD
     * MIC_RECORD_RECOG_START / MIC_RECORD_END（+ MODULE_STATUS VR）。数据会送到 AaBridgeCore.onCarlifeMicData。
     * false = 消息未发出（会话已关闭）。
     */
    fun requestMicRecord(start: Boolean): Boolean = false

    /** 编码器关键帧（字节大小）已发往 CarLife；仅在带 IDR 大小限制的车机上调用（VW ID，h196）。 */
    fun onEncodedKeyFrame(size: Int) {}

    /** 视频队列状态（吞吐、积压、长度），供桥接层统计用；null = 无数据。 */
    fun videoStats(): LinkVideoStats? = null

    /** CMD MEDIA_INFO（0x10035）；封面图（AA 来的 PNG）由实现方转码，不阻塞调用方。false = 未发出。 */
    fun sendMediaInfo(info: CarlifeMediaInfo): Boolean = false

    /** CMD MEDIA_PROGRESS_BAR（0x10036）。 */
    fun sendMediaProgress(progressBar: Int): Boolean = false

    /** CMD NAVI_NEXT_TURN_INFO（0x10030）；字段 6（图标 / time）按实现方设置。 */
    fun sendNaviTurn(info: NaviTurnInfo): Boolean = false

    /** CMD GO_TO_DESKTOP（0x10021）：车机回到自己的桌面（AA 中的「退出」磁贴）。 */
    fun goToDesktop() {}
}

/** 指向已死亡 CarLife 会话的引用（USB 断开）：一切丢弃。 */
private object NoLink : CarlifeLink {
    override fun sendVideo(payload: ByteArray) = false
    override fun showPlaceholder(text: String) {}
    override fun hidePlaceholder() {}
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = false
    override fun sendModuleStatus(module: Int, status: Int) = false
    override fun goToDesktop() {}
}

/** 手机通话状态：IDLE — 无，RINGING — 来电响铃，ACTIVE — 通话中（AudioManager MODE_IN_CALL）。 */
enum class PhoneCall { IDLE, RINGING, ACTIVE }

/**
 * AA → CarLife 桥接逻辑（不含 Android 与 socket）：以 AaVideoSink 方式接收 AA 视频，CarLife 首条消息的
 * 规则（SPS+PPS+IDR 合一），占位图/AA 切换，触摸映射，关键帧请求。
 */
class AaBridgeCore(
    private val log: (String, Boolean) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clockUs: () -> Long = { System.nanoTime() / 1_000 },
    micSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
    audioSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
) : AaVideoSink, AaAudioSink, AaMicSink, AaMetaSink, TranscodeOutput {
    enum class Source { PLACEHOLDER, AA }

    @Volatile var peer: AaPeer? = null
        set(v) {
            synchronized(touchLock) {
                field = v
                fingers.clear()
            }
            v?.sendNightMode(huTheme == THEME_DARK)
        }
    @Volatile private var link: CarlifeLink = NoLink

    /** 车机最近一次 CHANGE_MD_THEME（0 浅色，1 深色，2 自动）；在此之前为浅色。 */
    @Volatile var huTheme: Int = THEME_LIGHT
        private set

    @Volatile private var carlifeStarted = false
    private var aaConfig: ByteArray? = null
    private var aaVideoSeen = false
    private var gotIdr = false
    private var active = Source.PLACEHOLDER
    private var placeholderText = TEXT_CONNECTING
    private var waitSince = 0L
    private var lastKeyframeRequest = NEVER
    private var lastIdrSentMs = NEVER              // 最近一次向 CarLife 发送 passthrough IDR 的时间（h295）
    private var h295TouchAt = NEVER                // 触摸发起 keyframe 循环的时刻；NEVER = 触摸后不等待 IDR
    private var h295LastTrigger = NEVER
    private var h295LastTouchAt = NEVER            // 车机最近一次任意 action 的触摸

    var dropped = 0L
        private set
    var sent = 0L
        private set
    var idrSent = 0L
        private set
    var keyframeRequests = 0
        private set
    private var dropLogged = 0L
    private var touchDown = false

    // --- 视频：转码（应用内默认）或 passthrough ---
    /** 桥接视频模式：true = AA → 解码器 → 编码器（静态画面重复帧），false = 原样 H.264。 */
    @Volatile var transcodeEnabled = false

    /**
     * 视频分辨率，在创建 host 时采集一次（已计入 passthrough）：AA（SDR）按它构建，
     * CarLife 会话（INIT_DONE、编码器）和触摸映射也使用它。这里不读取 Prefs。
     */
    @Volatile var videoMode: VideoGeometry = VideoMode.DEFAULT

    /** 手动 AA 界面 DPI，与 videoMode 一起采集（AaDensity.AUTO = 模式的 density）。仅 SDR，不影响裁剪和触摸。 */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    private var transcoder: VideoTranscoder? = null
    private var encCsd: ByteArray? = null
    private var outGotIdr = false
    private val idrThrottle = IdrThrottle()            // CarLife 队列溢出时的 IDR 请求
    private var overflowIdrRequests = 0L
    private var lastStatsIdrRequests = 0L
    private var outputEnabled = false
    private var lastAaFrameAt = 0L
    private var lastStatsAt = 0L
    private var lastStatsSent = 0L
    private var lastStatsAaFrames = 0L
    private var aaFrames = 0L
    private var encDropped = 0L
    val isTranscoding: Boolean @Synchronized get() = transcoder != null

    // --- 音频 AA → CarLife ---
    /** UI 开关「USB 音频」。 */
    @Volatile var usbAudioEnabled = true

    /** 连接 AA 前是否等待车机 FEATURE_CONFIG（以决定要声明哪些音频同步）。 */
    @Volatile var expectFeatureConfig = false
    @Volatile var featureConfigSeen = false
    @Volatile private var btAudio = false
    @Volatile var huDesayG6S = false           // 车机 Desay G6S（Mazda CX-5）：NAVI=1 会静音原厂收音机，因此不发送路线 NAVI
        private set
    @Volatile var huHonda = false              // 本田（STATISTIC_INFO channel 2077xxxx/2083xxxx，HU_INFO 为空）：仪表盘 roadName 按字节和宫格裁剪
        private set
    private var naviRaisedAt = NEVER          // 本田：最近一次发出 MODULE_STATUS NAVI=1 的时刻
    private var naviShownSinceRaise = false   // 本田：最近一次 NAVI=1 之后已出现过 SHOW（0→1 的跳变已被仪表盘「消耗」）
    @Volatile var huVwBt1420 = false           // VW BT 1420（Pavel，通道 20082138）：无线模式下 PAUSE 无 heartbeat 约 1 分钟后 CarLife 断开
    @Volatile var huVwCns2Delphi = false       // VW CNS2 Delphi 20082114（h425）：语音会话中 AA 语音走 TTS 无声，改发 VR
    private var vrReply = false                // 助手回复流已在 VR 通道打开（VR_INIT 已发出）
    @Volatile var huVw20082120 = false        // VW（STATISTIC_INFO channel 20082120 h93、20082122 h222）：无 heartbeat 时 PAUSE 3.7-5.6 秒 USB 断开
        private set
    @Volatile var huVw20082138 = false         // VW ID（channel 20082138，"VW BT 1538"，h196）：IDR > ~600 KiB 会 USB EIO 断开
        private set
    @Volatile var huHyundaiCn7 = false         // 车机 Hyundai CN7（product wp_cn7cpe_cn，h218）：在「自动」模式下音乐 32 kHz，48→32 重采样
        private set
    @Volatile var huPioneerRm56 = false        // 车机 Pioneer RM56E8B（Dongfeng DF6，h277）：4-5 Mbps 会在 25-30 秒内 USB EIO 断开，稳定运行在 ~1 Mbps 且 fps ≤ 15
        private set
    @Volatile var huHonda20772121 = false      // 本田 Civic 11（channel 20772121，h295）：摄像头/菜单之后车机静默重置解码器，passthrough 等待 IDR
        private set
    @Volatile var huMazda20622111 = false      // 中国版 Mazda Connect 2（channel 20622111，h238/h359）：VIDEO_DATA > ~16 KiB 会挂起 USB 读取
        private set
    @Volatile var musicRateProvider: () -> MusicRate = { MusicRate.DEFAULT }
    private var resampler: MusicResampler? = null    // 48000 时为 null：PCM 原样发送
    private var mediaRateLogged = MEDIA_RATE
    @Volatile var mediaChunkProvider: () -> MediaChunk = { MediaChunk.DEFAULT }
    private var mediaChunkLogged = CarlifeAudio.MAX_CHUNK
    @Volatile var voiceChunkProvider: () -> VoiceChunk = { VoiceChunk.DEFAULT }
    private var voiceChunkLogged = CarlifeAudio.MAX_CHUNK
    @Volatile var ttsPrerollProvider: () -> TtsPreroll = { TtsPreroll.DEFAULT }
    private val audioInited = HashMap<Int, Boolean>()
    private val audioLogged = HashMap<Int, Long>()

    /** 音乐均衡器（仅 CarLife MEDIA）；每个缓冲都读取设置，可从 UI 实时修改。 */
    private val equalizer = Equalizer(48000, 2)
    var eqProvider: () -> EqSettings = { EqLive.settings }
    private var eqLogged: EqSettings? = null
    var audioBytes = 0L
        private set
    var audioDropped = 0L
        private set

    // 音频时序，依据百度 MD：NAVI 在 TTS 之前，MEDIA 由 PAUSE 释放，TTS_STOP 带延迟。
    private val audioTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-audio-timer").also { it.isDaemon = true } }
    }
    private val audioSched: (Long, Runnable) -> MicTimerHandle = audioSchedule ?: { delayMs, task ->
        val f = audioTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        MicTimerHandle { f.cancel(false) }
    }

    /** 一次性可取消定时器；任务在核心锁内执行，已取消（即使已排入队列）不会运行。 */
    private inner class AudioTimer {
        private var gen = 0
        private var handle: MicTimerHandle? = null
        val pending: Boolean get() = handle != null

        fun start(delayMs: Long, task: () -> Unit) {
            cancel()
            val g = gen
            handle = audioSched(delayMs, Runnable {
                synchronized(this@AaBridgeCore) {
                    if (g == gen && handle != null) {
                        handle = null
                        task()
                    }
                }
            })
        }

        fun cancel(): Boolean {
            val h = handle ?: return false
            handle = null
            gen++
            h.cancel()
            return true
        }
    }

    private val ttsStopTimer = AudioTimer()

    /** h425：VW CNS2 Delphi 累积 MOVE 约 190 毫秒后成批发送；我们将整批均匀分发。仅当 [huVwCns2Delphi] 时启用。 */
    private val touchPacer = TouchBurstPacer(clockMs, audioSched) { a, x, y, px, py -> dispatchTouch(a, x, y, px, py) }
    @Volatile private var touchPacerLogged = false

    /** MOVE 抽稀（[TouchDecimation]）位于 pacer 与直接发送之前。 */
    @Volatile var touchDecimationProvider: () -> TouchDecimation = { TouchDecimation.DEFAULT }
    private val touchDecimator = TouchDecimator { a, x, y, px, py -> routeTouch(a, x, y, px, py) }
    @Volatile private var touchDecimationLogged = 1
    private val mediaReleaseTimer = AudioTimer()
    private val voiceExitTimer = AudioTimer()
    private var carlifePaused = false          // 自最近一次 START 以来的 VIDEO_ENCODER_PAUSE
    private var exitPending = false            // AA 中的「退出」：GO_TO_DESKTOP 已发出，等待车机 PAUSE 以向 AA 发送 NATIVE
    private var huPausedAa = false             // 车机切到自家音源时暂停了 AA 播放器；在 play 之前不转发 MEDIA
    private var resumeOnHuMusic = false        // 暂停是我们发起的（AA 播放时发了 127）：车机 MUSIC=1 时可恢复 play
    private var lastHuResumeMs = NEVER         // 车机 MUSIC=1 时向 AA 发送 KEYCODE 126 的时刻
    @Volatile private var lastUserInputMs = NEVER   // 车机最近一次触摸 DOWN / 按键 / 旋钮 / 语音
    @Volatile private var lastVoiceKeyMs = NEVER    // AA 中最近一次 KEYCODE_SEARCH（防抖 h425）
    private var lastHfpConnectMs = NEVER       // 车机发来 BT_HFP_CONNECTION=connected（之后 AA 会自行通过 BT 启动自动播放）
    private var lastPauseKeyMs = NEVER         // 最近一次向 AA 发送 KEYCODE 127 的时刻
    private var naviSent = false               // MODULE_STATUS NAVI=1 已发出（保持 TTS 和/或路线）
    private var naviEndPending = false           // 路线已结束，而 NAVI=1 仍在保持 TTS：NAVI=0 将在 TTS STOP 后发出
    private var ttsNaviHeld = false            // NAVI=1 为 TTS 而保持（直至 stopAllAudio）
    private var routeNaviHeld = false          // NAVI=1 为路线而保持（MetaRelay）
    private var mediaPaused = false            // 车机 MEDIA 处于 PAUSE（STOP 尚未发送）
    private var mediaGuard = false             // TTS_STOP 已发出，等待 +300 毫秒后才允许 MEDIA 控制包
    private var pendingMediaPause = false      // MEDIA 期望状态 = 暂停，将在 TTS 之后应用
    private var voice = false
    private var mediaGateDrops = 0L
    private var ttsPackets = 0

    /** MEDIA 已在车机初始化并播放（用于响应 MODULE_CONTROL MUSIC）。 */
    val isMediaInited: Boolean @Synchronized get() = audioInited[CarlifeAudio.CH_MEDIA] == true

    /** MODULE_CONTROL MUSIC 的应答：USB 音频按 MEDIA INIT，否则按 AA 播放器状态（ch9）。 */
    val musicActive: Boolean @Synchronized get() = isMediaInited || (!audioViaUsb && meta.playing)

    // --- 元数据 AA（ch9/ch10）→ CarLife MEDIA_INFO / PROGRESS / NAVI_NEXT_TURN_INFO ---
    private val meta = MetaRelay(
        log = log,
        clockMs = clockMs,
        link = { link },
        ready = { carlifeStarted && link !== NoLink },
        navHold = { on -> routeNavi(on) },
        musicState = { on -> musicByState(on) },
        navRearm = { rearmNavi() },
        emptyRoadText = { huHonda },
    )

    val metaRelay: MetaRelay get() = meta

    /** 音频走 USB：UI 已启用且车机未请求蓝牙。读取 AapOptions.sdProvider（要声明哪些同步）。 */
    val audioViaUsb: Boolean get() = usbAudioEnabled && !btAudio

    val activeSource: Source @Synchronized get() = active
    val currentPlaceholderText: String @Synchronized get() = placeholderText
    val isCarlifeStarted: Boolean @Synchronized get() = carlifeStarted

    /** 存在存活（未关闭）的 CarLife 会话：USB 已连接。无锁读取。 */
    val hasCarlifeLink: Boolean get() = link !== NoLink

    /**
     * 更换车机上的占位图文字（「正在启动服务器...」、「请解锁手机」）；AA 视频已在播放时不改动。
     * 会记住，供未来的 CarLife 会话使用。
     */
    @Synchronized
    fun showStatus(text: String) {
        if (active == Source.AA || text == placeholderText) return
        placeholderText = text
        link.showPlaceholder(text)
        log("BRIDGE заглушка: '$text'", true)
    }

    // --- AA 麦克风：请求由手机打开，声音取自车机（CarLife VR）或手机麦克风 ---
    /** 每次 open 时读取麦克风模式：UI 开关立即生效。 */
    @Volatile var micModeProvider: () -> MicMode = { MicMode.AUTO }
    /** 手机通话状态（AudioManager.mode）：在 [huVwCns2Delphi] 上，通话期间方向盘切歌键 = 接听/挂断。 */
    @Volatile var phoneCallProvider: () -> PhoneCall = { PhoneCall.IDLE }

    /** 手机麦克风（AudioRecord）；null = 无。全程单一实例：router 按顺序调用 start/stop。 */
    @Volatile var phoneMic: PhoneMic? = null

    private val micTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-mic-timer").also { it.isDaemon = true } }
    }

    private val mic = AaMicRouter(
        log = log,
        mode = { micModeProvider() },
        huLinkUp = { carlifeStarted && link !== NoLink },
        huRecord = { start -> link.requestMicRecord(start) },
        phone = { phoneMic },
        toAa = { pcm, ts -> peer?.sendMicPcm(pcm, ts) },
        schedule = micSchedule ?: { delayMs, task ->
            val f = micTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            MicTimerHandle { f.cancel(false) }
        },
        nowMs = clockMs,
        nowUs = clockUs,
    )

    override fun onMicOpen(): Boolean = mic.open()

    override fun onMicClose(why: String) = mic.close(why)

    /** 车机麦克风 PCM（VR 0x00058001，消息体）→ AA。 */
    fun onCarlifeMicData(pcm: ByteArray) = mic.onHuData(pcm)

    /**
     * 在 CarLife 会话启动时调用：显示占位图。如果 AA 已存活并在播放（USB 在 grace 窗口内回归），
     * 显示「等待画面」而非「启动服务器」，关键帧将在 CarLife START 时发出。
     */
    @Synchronized
    fun attach(link: CarlifeLink) {
        this.link = link
        active = Source.PLACEHOLDER
        carlifeStarted = false
        carlifePaused = false
        exitPending = false
        huPausedAa = false
        resumeOnHuMusic = false
        lastHuResumeMs = NEVER
        gotIdr = false
        huDesayG6S = false                     // HU_INFO / STATISTIC_INFO 会在此会话中重新到来
        huVw20082120 = false                   // HU_INFO / STATISTIC_INFO 会在此会话中重新到来
        huHonda = false                        // STATISTIC_INFO 会在此会话中重新到来
        huVwBt1420 = false                     // HU_INFO 会在此会话中重新到来
        huVw20082138 = false                   // HU_INFO / STATISTIC_INFO 会在此会话中重新到来
        vwIdName = false
        vwIdChannel = false
        huHyundaiCn7 = false                   // HU_INFO 会在此会话中重新到来
        huPioneerRm56 = false                  // HU_INFO 会在此会话中重新到来
        huHonda20772121 = false                // HU_INFO / STATISTIC_INFO 会在此会话中重新到来
        huMazda20622111 = false                // STATISTIC_INFO 会在此会话中重新到来
        huVwCns2Delphi = false                 // STATISTIC_INFO 会在此会话中重新到来
        vrReply = false
        touchDecimator.reset()
        touchDecimationLogged = 1
        touchPacer.reset()
        touchPacerLogged = false
        h295TouchAt = NEVER
        h295LastTrigger = NEVER
        h295LastTouchAt = NEVER
        mediaRateLogged = MEDIA_RATE
        mediaChunkLogged = CarlifeAudio.MAX_CHUNK
        voiceChunkLogged = CarlifeAudio.MAX_CHUNK
        resampler?.reset()
        if (aaVideoSeen) placeholderText = TEXT_WAIT_FRAME
        meta.onAttach()
        link.showPlaceholder(placeholderText)
        log("BRIDGE CarLife-сессия подключена: источник = заглушка '$placeholderText' (AA ${if (aaVideoSeen) "жива" else "нет видео"})", true)
    }

    // --- CarLife 事件 ---
    @Synchronized
    fun onCarlifeStart() {
        // Desay G6S（Mazda）每次按下 NAVI/MEDIA 都会不带 PAUSE 地发送 START：声音已在播放，曲目中途重复
        // INIT 会在车机上撕裂声音（日志 2026-10-11）。仅在首个 START 和 PAUSE 之后才重置声音。
        val warm = carlifeStarted && !carlifePaused
        // 无线、PAUSE 后回归：解码器在整段暂停期间一直在吃 AA 帧（gate 只丢弃编码器输出），其
        // 状态仍存活。不等待 AA 的 IDR（VideoFocus 循环，0.2-2 秒静默），而是直接取编码器的 IDR。
        // VW BT 1420（日志 2026-10-16）会在此类 START 后 0.16-0.42 秒关闭 socket。仅针对它：
        // 其余车机（无线 Mazda G6S、倒车影像）维持原样，通过 AA 的 IDR。
        val resume = AIR_WARM_RESUME_ENABLED && huVwBt1420 && link.isAir && carlifeStarted && carlifePaused &&
            transcodeEnabled && transcoder != null && gotIdr && active == Source.AA
        // PAUSE 后的 START 仍是同一活会话：延后的 TTS STOP 立即发送，否则 resetAudio 会吞掉它，
        // 车机会一直保持提示通道打开并静音音乐直到下一条提示（h482，ix35 HSAE）。
        if (carlifeStarted && carlifePaused && ttsStopTimer.pending) {
            log("BRIDGE CarLife START после PAUSE: отложенный TTS STOP шлю сразу", true)
            stopAudio(CarlifeAudio.CH_TTS)
        }
        carlifeStarted = true
        carlifePaused = false
        if (warm) log("BRIDGE CarLife START без PAUSE: звук не трогаю, INIT не повторяю", true)
        else resetAudio()                      // 新 START：MEDIA/TTS 在首个数据包上重新 init
        if (resume) {
            lastKeyframeRequest = NEVER
            log("BRIDGE CarLife START после PAUSE (воздух): декодер жив, сразу IDR энкодера, AA не жду", true)
            startTranscoder()
            outputEnabled = true
            if (!warm) meta.onCarlifeStart()
            if (huPausedAa) log("BRIDGE CarLife START: AA на паузе после ухода ГУ на свой источник, музыку не запускаю", true)
            return
        }
        gotIdr = false
        h295TouchAt = NEVER
        waitSince = if (aaVideoSeen) clockMs() - KEYFRAME_WAIT_MS else 0L
        lastKeyframeRequest = NEVER
        log("BRIDGE CarLife START: источник=$active, AA видео ${if (aaVideoSeen) "идёт, запрашиваю ключевой кадр" else "ещё нет"}", true)
        if (transcodeEnabled) startTranscoder()
        else log("BRIDGE видео: режим passthrough (H.264 AA как есть)", true)
        if (aaVideoSeen) {
            // AA 已在播放（热会话）：不等 tick，立即走 NATIVE → PROJECTED 循环（解码器需要 AA 的 IDR）
            lastKeyframeRequest = clockMs()
            keyframeRequests++
            peer?.requestKeyFrame()
        }
        if (!warm) meta.onCarlifeStart()
        // START 时不发送 play：CarLife 音乐会立即抢占车机收音机，而导航下听收音机也是常见场景（h33）。
        // 恢复音乐只能等车机显式 MUSIC=1（onCarlifeMusicControl）。
        if (huPausedAa) log("BRIDGE CarLife START: AA на паузе после ухода ГУ на свой источник, музыку не запускаю", true)
    }

    /**
     * 车机的 MODULE_CONTROL MUSIC；应答为 MODULE_STATUS MUSIC。status=0 — 车机切到自家音源（收音机）：Desay G6S
     * （Mazda）在 PAUSE 之后发送，其他车机不带 PAUSE。AA 播放器暂停，其缓冲尾部在下次 play 之前不转发，
     * 应答 MUSIC=0——否则车机会认为 CarLife 音乐处于活动状态而与收音机冲突。
     * 尾部保留也适用于 AA 已暂停的情况（Honda：助手暂停后 MUSIC=0，缓冲尾部曾在 40 毫秒后触发 MEDIA INIT），
     * 且即使 MEDIA PAUSE 推迟到 TTS 结束也应答 MUSIC=0（Egor 日志 2026-10-13）。
     *
     * status=1 — Desay G6S 回到 CarLife 音乐（START 附近连发 10-20 次 MUSIC=1）。在我们暂停之后，这是
     * 车机上的用户选择：AA KEYCODE 126，并针对整批应答 MUSIC=1（日志 h214 2026-10-17）。单纯返回地图
     * （NAVI）不会带 MUSIC=1——那里不碰收音机。新会话也会发 MUSIC=1，但其中没有我们发起的暂停。
     */
    @Synchronized
    fun onCarlifeMusicControl(status: Int?): Boolean {
        if (HU_MUSIC_FOLLOW_ENABLED && status == 0 && carlifeStarted) {
            val p = peer
            if (meta.playing && !huPausedAa && p != null) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0${if (carlifePaused) " после PAUSE" else ""}): AA KEYCODE $KEYCODE_MEDIA_PAUSE, MEDIA на паузу", true)
                sendPauseKey(p)
                resumeOnHuMusic = true
            } else if (!huPausedAa) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0), AA не играет: хвост MEDIA не пересылаю до play", true)
                resumeOnHuMusic = false
            }
            huPausedAa = true
            requestMediaPause("ГУ MODULE_CONTROL MUSIC=0")
            return false
        }
        if (HU_MUSIC_RESUME_ENABLED && status == 1 && huDesayG6S) {
            val now = clockMs()
            val p = peer
            if (huPausedAa && resumeOnHuMusic && p != null) {
                huPausedAa = false
                resumeOnHuMusic = false
                lastHuResumeMs = now
                lastUserInputMs = now              // 车机上的音源选择属于用户输入，BT 防护不打断它
                log("BRIDGE ГУ вернулось на CarLife-музыку (MUSIC=1) после нашей паузы: AA KEYCODE $KEYCODE_MEDIA_PLAY", true)
                p.sendKey(KEYCODE_MEDIA_PLAY)
                return true
            }
            if (now - lastHuResumeMs < HU_RESUME_ANSWER_MS) return true   // 连发的 MUSIC=1，在 AA 启动期间
        }
        return musicActive
    }

    private fun sendPauseKey(p: AaPeer) {
        lastPauseKeyMs = clockMs()
        p.sendKey(KEYCODE_MEDIA_PAUSE)
    }

    /** 车机的 BT_HFP_CONNECTION：state=2 — 手机已通过蓝牙连接车机（0 — 已断开）。 */
    @Synchronized
    fun onHuBtHfpConnection(state: Int?) {
        if (state == 2) {
            lastHfpConnectMs = clockMs()
            log("BRIDGE ГУ: BT HFP подключён (автоплей AA в ближайшие $BT_AUTOPLAY_WINDOW_MS мс не снимет паузу)", true)
        } else {
            log("BRIDGE ГУ: BT HFP состояние=$state", true)
        }
    }

    /** AA 自行恢复播放（用户 play）：声音重新转发。BT HFP 之后无车机输入则视为自动播放——保持暂停。 */
    private fun releaseHuPause(why: String) {
        if (!huPausedAa) return
        val now = clockMs()
        val p = peer
        if (BT_AUTOPLAY_GUARD_ENABLED && p != null && now - lastHfpConnectMs < BT_AUTOPLAY_WINDOW_MS &&
            now - lastUserInputMs > USER_INPUT_WINDOW_MS) {
            if (now - lastPauseKeyMs >= REPAUSE_MIN_MS) {
                log("BRIDGE $why через ${now - lastHfpConnectMs} мс после BT HFP без ввода пользователя: автоплей, снова KEYCODE $KEYCODE_MEDIA_PAUSE", true)
                sendPauseKey(p)
            }
            return
        }
        huPausedAa = false
        resumeOnHuMusic = false
        log("BRIDGE $why: MEDIA снова пересылаю", true)
    }

    private fun ago(t: Long): String = if (t == NEVER) "не было" else "${clockMs() - t} мс назад"

    /** 车机的 CARLIFE_DATA_SUBSCRIBE_START（on=true）/ STOP；订阅 moduleID：0 导航，2 音乐。 */
    @Synchronized
    fun onCarlifeSubscribe(moduleIds: List<Int>, on: Boolean) {
        for (id in moduleIds) meta.onSubscribe(id, on)
    }

    /** 车机的 CHANGE_MD_THEME（h294）：深色 → AA 夜间模式，浅色 → 日间；自动（2）及其余 → 日间。 */
    fun onCarlifeTheme(mode: Int?) {
        val m = mode ?: return log("BRIDGE CHANGE_MD_THEME без themeModes, тему не меняю", true)
        val prev = huTheme
        huTheme = m
        val night = m == THEME_DARK
        log("BRIDGE тема ГУ $prev -> $m (${themeName(m)}): AA night=$night${if (peer == null) ", AA не подключён, отдам при подключении" else ""}", true)
        peer?.sendNightMode(night)
    }

    /** 车机的 VIDEO_ENCODER_PAUSE：下一次 START 为完整启动，带音频重置。 */
    @Synchronized
    fun onCarlifePause() {
        carlifePaused = true
        if (exitPending) {
            // 车机在我们的 GO_TO_DESKTOP 之后退出了 CarLife：告知 AA 没有投影（NATIVE），回归经由 START
            exitPending = false
            log("BRIDGE ГУ вышел из CarLife (PAUSE) после «Выйти»: AA VideoFocus NATIVE", true)
            peer?.sendVideoFocusNative()
        }
    }

    /** AA 中的「退出」磁贴：VIDEO_FOCUS_REQUEST NATIVE。请求车机回到桌面；向 AA 发送 NATIVE 的时机是车机发来 PAUSE 时。 */
    @Synchronized
    override fun onAaNativeFocusRequest() {
        if (link === NoLink || !carlifeStarted) {
            log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) проигнорирован: CarLife ${if (link === NoLink) "не подключён" else "ещё не START"}", true)
            return
        }
        log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) -> CarLife GO_TO_DESKTOP", true)
        exitPending = true
        link.goToDesktop()
        // AA 等待 NATIVE 的应答，此时显示启动器黑色区域。Desay G6S（Mazda）退出时不发 PAUSE、回归时也不发 START
        // （日志 2026-10-12），因此立即应答：NATIVE → PROJECTED 循环将 AA 送回主屏幕。
        lastKeyframeRequest = clockMs()
        keyframeRequests++
        peer?.requestKeyFrame()
    }

    /** 已解析车机 FEATURE_CONFIG；mode = AUDIO_TRANSMISSION_MODE 的值（null = 无此键）。 */
    fun onHuFeatureConfig(audioTransmissionMode: Int?) {
        featureConfigSeen = true
        if (audioTransmissionMode == CarlifeAudio.AUDIO_MODE_BT) {
            btAudio = true
            log("BRIDGE !!!!!! ГУ: AUDIO_TRANSMISSION_MODE=1 (звук по Bluetooth). Звук по USB НЕ шлю, AA-звук остаётся на телефоне (синки MEDIA/SPEECH не объявляю)", true)
        } else {
            log("BRIDGE AUDIO_TRANSMISSION_MODE=${audioTransmissionMode ?: "нет в списке"}: звук по USB ${if (usbAudioEnabled) "включён" else "выключен в UI"}", true)
        }
    }

    /**
     * 已解析车机 HU_INFO：通过厂商和型号识别 Desay G6S（其他 Desay SV 车机不受此限制），
     * Hyundai CN7 按 product 识别。
     */
    fun onHuInfo(manufacturer: String?, model: String?, product: String? = null) {
        if (manufacturer == DESAY_MANUFACTURER && model == DESAY_G6S_MODEL) huDesayG6S = true
        vwIdName = manufacturer == VW_20082138_MANUFACTURER
        if (manufacturer == VW_BT_1420_MANUFACTURER) huVwBt1420 = true
        updateVwId()
        if (product == HYUNDAI_CN7_PRODUCT) huHyundaiCn7 = true
        if (manufacturer == PIONEER_MANUFACTURER && model == PIONEER_RM56_MODEL) {
            if (!huPioneerRm56) log("BRIDGE ГУ Pioneer RM56E8B: потолок ${PIONEER_RM56_CEILING_BPS / 1000} кбит/с, fps ≤ $PIONEER_RM56_MAX_FPS", true)
            huPioneerRm56 = true
            // passthrough 下上限和 fps 不生效（h277 test1）；BridgeService 会在下一会话将字段恢复为设置值
            if (!transcodeEnabled) {
                transcodeEnabled = true
                log("BRIDGE ГУ Pioneer RM56E8B: passthrough отключаю, перекодирование принудительно", true)
            }
        }
    }

    private var vwIdName = false               // HU_INFO manufacturer = 车机蓝牙名 "VW BT 1538"
    private var vwIdChannel = false            // STATISTIC_INFO channel = 固件 20082138

    /**
     * VW ID（h196）— 仅限固件 20082138 且蓝牙名为 "VW BT 1538"：VW BT 1420（Pavel）也用同一通道，
     * 它不需要 2 Mbps 上限（无线和 USB 均 8 Mbps 无中断，日志 2026-10-16）。
     */
    private fun updateVwId() {
        val was = huVw20082138
        huVw20082138 = vwIdName && vwIdChannel
        if (huVw20082138 && !was) log("BRIDGE ГУ VW ID (20082138, VW BT 1538): потолок битрейта по размеру IDR", true)
    }

    /** 已解析车机 STATISTIC_INFO：Desay G6S 的备用识别标志和 VW 20082120 的识别标志——固件构建通道。 */
    fun onHuStatistic(channel: String?) {
        if (channel == DESAY_G6S_CHANNEL) huDesayG6S = true
        if (channel == HONDA_20772121_CHANNEL) huHonda20772121 = true
        if (channel == MAZDA_20622111_CHANNEL && !huMazda20622111) {
            huMazda20622111 = true
            log("BRIDGE ГУ Mazda 20622111: VIDEO_DATA режу на куски ≤ $MAZDA_20622111_MAX_VIDEO_PACKET Б, потолок ${MAZDA_20622111_CEILING_BPS / 1000} кбит/с", true)
        }
        if (channel == VW_20082120_CHANNEL || channel == VW_CNS3_20082122_CHANNEL || channel == VW_CNS3_20082134_CHANNEL ||
            channel == VW_CNS2_20082114_CHANNEL) huVw20082120 = true
        if (channel == VW_CNS2_20082114_CHANNEL && !huVwCns2Delphi) {
            huVwCns2Delphi = true
            log("BRIDGE ГУ VW CNS2 Delphi 20082114: ответ ассистента в канал VR, антидребезг кнопки голоса", true)
        }
        if (isHondaChannel(channel) && !huHonda) {
            huHonda = true
            log("BRIDGE ГУ Honda (channel $channel): roadName под приборку, стрелки после SUBSCRIBE_START, перевзвод NAVI 0->1", true)
        }
        vwIdChannel = channel == VW_20082138_CHANNEL
        updateVwId()
    }

    /** MODULE_STATUS NAVI=1 已发出（路线 / TTS / 重新置位）。Honda：车机会应答 SUBSCRIBE_START——箭头等待它。 */
    private fun naviRaised() {
        naviRaisedAt = clockMs()
        naviShownSinceRaise = false
        if (huHonda) meta.awaitSubscribe()
    }

    /**
     * Honda：在首次 SHOW（新路线、重新规划）以及静默后的再次 SHOW 之前，仪表盘需要 MODULE_STATUS
     * NAVI 0→1 的跳变（h137，2026-10-16 20:43:45：NAVI=1 保持 TTS，新路线的 SHOW 未带跳变发出——直到
     * 行程结束都没有箭头）。新鲜的 NAVI=1（晚于 NAVI_REARM_FRESH_MS，其后未曾出现箭头）不处理：跳变已经发生过。
     */
    @Synchronized
    private fun rearmNavi() {
        if (!huHonda || huDesayG6S || !ROUTE_NAVI_STATUS_ENABLED) return
        if (naviSent && !naviShownSinceRaise && clockMs() - naviRaisedAt < NAVI_REARM_FRESH_MS) {
            naviShownSinceRaise = true
            return
        }
        if (naviSent) link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
        if (link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
            naviSent = true
            naviRaised()
            log("BRIDGE CarLife MODULE_STATUS NAVI 0->1 (Honda: перевзвод приборки перед SHOW)", true)
        }
    }

    /** CarLife 会话已关闭（USB EIO / detach / 停止）。不碰 AA；from — 要关闭的链接，不解除别人的链接。 */
    fun onCarlifeClosed(from: CarlifeLink? = null) {
        if (closeCarlife(from)) mic.onCarlifeLost()      // 在核心锁之外：麦克风 router 自行加锁
    }

    @Synchronized
    private fun closeCarlife(from: CarlifeLink?): Boolean {
        if (from != null && from !== link) {
            log("BRIDGE закрытие старой CarLife-сессии, текущая уже другая: игнор", false)
            return false
        }
        carlifeStarted = false
        exitPending = false
        gotIdr = false
        resetAudio()
        stopTranscoderAsync()
        active = Source.PLACEHOLDER
        link = NoLink
        synchronized(touchLock) { fingers.clear() }
        log("BRIDGE CarLife закрыт (AA ${if (aaVideoSeen) "жива" else "без видео"}, держим)", true)
        return true
    }

    /** CarLife 触摸的手指：id 用于 AA，坐标是车机帧的最新坐标。 */
    private class Finger(val id: Int, var x: Int, var y: Int) {
        fun set(nx: Int, ny: Int) {
            x = nx
            y = ny
        }
    }

    private val touchLock = Any()

    /** 活动中的手指按车机 MotionEvent 的索引排序：x,y（TOUCH_ACTION）为索引 0，pointerx/pointery 为索引 1。 */
    private val fingers = ArrayList<Finger>(2)

    /**
     * 车机 TOUCH_ACTION -> AA。action 与 MotionEvent 相同：action&255 = 0 DOWN、1 UP、2 MOVE、5 POINTER_DOWN、6 POINTER_UP，
     * action>>8 = 手指索引。第二个手指在 (px, py) 中；0,0 = 无坐标（此时用最后已知的坐标）。
     * 最多两个手指；POINTER_UP 后剩余手指的 id 不变。车机帧坐标 -> AA 帧坐标通过
     * [VideoMode.mapTouch]（无 margin，1:1）。单指发往 AA 的方式与多点触控之前相同（id 0，action_index 0）。
     */
    fun onCarlifeTouch(action: Int, x: Int, y: Int, px: Int = 0, py: Int = 0) {
        val every = touchDecimationProvider().every
        if (every != touchDecimationLogged) {
            touchDecimationLogged = every
            log("BRIDGE тач: прореживание MOVE ${if (every > 1) "×$every" else "выкл"} (настройка)", true)
        }
        touchDecimator.onTouch(action, x, y, px, py, every)
        onHuTouch(action and 0xFF)                                            // 在 touchLock 之外：没有嵌套锁
    }

    /** 节流器输出：Delphi 上经批次 pacer，其余车机直接送入 AA。 */
    private fun routeTouch(action: Int, x: Int, y: Int, px: Int, py: Int) {
        if (huVwCns2Delphi) {
            touchPacer.onTouch(action, x, y, px, py)
            if (touchPacer.bursts == 1 && !touchPacerLogged) {
                touchPacerLogged = true
                log("BRIDGE тач: ГУ шлёт MOVE пачками, раздаю равномерно между пачками (h425)", true)
            }
        } else {
            dispatchTouch(action, x, y, px, py)
        }
    }

    /**
     * h295，Honda Civic 11（20772121）：车机在倒车影像 / 车辆菜单之后会静默重置解码器，什么都不发
     * （既无 PAUSE 也无 START），而 passthrough 下 AA 每分钟才给一个 IDR。倒车/菜单期间 CarLife 触摸中断，正常
     * 工作时点击更频繁。若在旧 IDR 情况下出现 >= [H295_TOUCH_IDLE_MS] 暂停之后的 DOWN——大概率是黑屏：立即
     * 以完整 NATIVE -> PROJECTED 循环向 AA 请求关键帧（Samsung 上软 PROJECTED 不给 IDR），gate 不关闭，
     * P 帧继续走。到达 [onFrame] 的 IDR 会从 CODEC_CONFIG 取 SPS/PPS：车机解码器重置后可能不接受裸 IDR。
     * 上一次触摸时间在更新之前读取，因此车机第二次 DOWN 重复（约 2 毫秒）不会再次触发。
     */
    @Synchronized
    private fun onHuTouch(act: Int) {
        val now = clockMs()
        val sinceTouch = now - h295LastTouchAt
        h295LastTouchAt = now
        if (act != AapMessages.TOUCH_DOWN) return
        if (!huHonda20772121 || transcoder != null || !carlifeStarted || !gotIdr) return
        val sinceIdr = now - lastIdrSentMs
        if (sinceIdr <= H295_IDR_STALE_MS || sinceTouch < H295_TOUCH_IDLE_MS || now - h295LastTrigger < H295_TRIGGER_MIN_MS) return
        h295LastTrigger = now
        h295TouchAt = now
        lastKeyframeRequest = now
        keyframeRequests++
        log("BRIDGE h295: тач после $sinceIdr мс без IDR, пауза в тачах $sinceTouch мс -> keyframe-цикл", true)
        peer?.requestKeyFrame()
    }

    /** h295：循环之后 IDR 在 [H295_WAIT_GIVEUP_MS] 内始终没来——日志和拼接操作不把别的 IDR 挂到它名下。 */
    private fun h295GiveUp(now: Long) {
        if (h295TouchAt != NEVER && now - h295TouchAt > H295_WAIT_GIVEUP_MS) h295TouchAt = NEVER
    }

    /** h295：触摸后等待的 IDR，若帧里没有 SPS/PPS，则带上 CODEC_CONFIG 中的 SPS/PPS 发出；否则帧原样发出。 */
    private fun h295Prepare(b: ByteArray, types: List<Int>): ByteArray {
        if (h295TouchAt == NEVER || 5 !in types || (AnnexB.startsWithSps(b) && 8 in types)) return b
        val cfg = aaConfig ?: return b
        return AnnexB.glue(cfg, b)
    }

    private fun h295IdrSeen(before: ByteArray, after: ByteArray) {
        if (h295TouchAt == NEVER) return
        log("BRIDGE h295: IDR через ${lastIdrSentMs - h295TouchAt} мс после тача, nal=${AnnexB.nalTypes(before)} -> ${AnnexB.nalTypes(after)}", true)
        h295TouchAt = NEVER
    }

    private fun dispatchTouch(action: Int, x: Int, y: Int, px: Int, py: Int) = synchronized(touchLock) {
        val act = action and 0xFF
        val idx = action ushr 8
        if (act == AapMessages.TOUCH_DOWN || act == AapMessages.TOUCH_POINTER_DOWN) lastUserInputMs = clockMs()
        val name = TOUCH_NAMES[act]
        if (name == null) {
            log("BRIDGE тач action=$action x=$x y=$y не передаю (нет аналога в AA)", false)
            return@synchronized
        }
        val p = peer
        if (p == null) {
            log("BRIDGE тач $name $x,$y проигнорирован: AA не подключён", false)
            fingers.clear()
            return@synchronized
        }
        val second = px != 0 || py != 0
        when (act) {
            AapMessages.TOUCH_DOWN -> {
                fingers.clear()
                fingers.add(Finger(0, x, y))
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_MOVE -> {
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))   // DOWN 丢失：和以前一样，单指 id 0
                fingers[0].set(x, y)
                if (fingers.size > 1 && second) fingers[1].set(px, py)
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_POINTER_DOWN -> {
                if (fingers.size >= 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: больше двух пальцев не передаю", false)
                    return@synchronized
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                val pos = idx.coerceAtMost(fingers.size)
                val fresh = Finger(if (fingers[0].id == 0) 1 else 0, x, y)
                fingers.add(pos, fresh)
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py) else if (fingers[1] === fresh) fresh.set(x, y)
                sendFingers(p, act, pos, name)
            }
            AapMessages.TOUCH_POINTER_UP -> {
                if (fingers.size < 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: пальцев ${fingers.size}, не передаю", false)
                    return@synchronized
                }
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py)
                sendFingers(p, act, idx, name)
                fingers.removeAt(idx)
            }
            else -> {                                              // UP（最后一个手指）
                if (fingers.size > 1) {                            // POINTER_UP 丢失：先抬起第二个手指
                    sendFingers(p, AapMessages.TOUCH_POINTER_UP, 1, "POINTER_UP")
                    fingers.removeAt(1)
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                fingers[0].set(x, y)
                sendFingers(p, act, 0, name)
                fingers.clear()
            }
        }
    }

    /** 所有活动手指 -> AA 作为单个事件；单指时日志与多点触控之前相同。 */
    private fun sendFingers(p: AaPeer, act: Int, index: Int, name: String) {
        val mode = videoMode
        val points = fingers.map { f ->
            val (ax, ay) = mode.mapTouch(f.x, f.y)
            TouchPoint(f.id, ax, ay)
        }
        val ui = act != AapMessages.TOUCH_MOVE
        if (points.size == 1) {
            val f = fingers[0]
            val a = points[0]
            if (a.x == f.x && a.y == f.y) log("BRIDGE тач $name ${f.x},${f.y} -> AA", ui)
            else log("BRIDGE тач $name ГУ ${f.x},${f.y} -> AA ${a.x},${a.y} (${mode.label})", ui)
        } else {
            val gu = fingers.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            val aa = points.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            log("BRIDGE тач $name[$index] пальцев ${points.size}: ГУ $gu -> AA $aa (${mode.label})", ui)
        }
        p.sendTouch(act, index, points)
    }

    /** 车机 TOUCH_CAR_HARD_KEY_CODE（方向盘按键）-> AA：按 [HARD_KEY_TO_ANDROID] 发送 Android KeyEvent 按下。 */
    fun onCarlifeHardKey(code: Int) {
        lastUserInputMs = clockMs()
        HARD_KEY_TO_ROTARY[code]?.let { delta ->
            val p = peer
            if (p == null) {
                log("BRIDGE шайба keycode=$code проигнорирована: AA не подключён", true)
                return
            }
            log("BRIDGE шайба keycode=$code -> AA rotary $delta", true)
            p.sendRotary(delta)
            return
        }
        val call = if (huVwCns2Delphi) phoneCallProvider() else PhoneCall.IDLE
        val android = delphiKey(code, call) ?: HARD_KEY_TO_ANDROID[code]
        if (call == PhoneCall.ACTIVE && code == HARD_KEY_SEEK_SUB) {
            log("BRIDGE руль keycode=$code: идёт звонок, назад не шлю", true)
            return
        }
        if (android == null) {
            log("BRIDGE руль keycode=$code: нет аналога в AA, игнор", true)
            return
        }
        val p = peer
        if (p == null) {
            log("BRIDGE руль keycode=$code проигнорирован: AA не подключён", true)
            return
        }
        if (android == KEYCODE_SEARCH && voiceKeyBounce("руль keycode=$code")) return
        val note = if (call != PhoneCall.IDLE) " (звонок ${call.name})" else ""
        log("BRIDGE руль keycode=$code -> AA KEYCODE $android$note", true)
        p.sendKey(android)
    }

    /**
     * 新款 VW 方向盘上没有电话按键：来电或通话期间，由快进/快退（seek）负责电话。
     * 和手机一样：左侧绿色，右侧红色。来电时：向后 = 接听（CALL），向前 = 拒接（ENDCALL）。
     * 通话中：向前 = 挂断。
     * 也没有暂停键：车机把长按快进/快退发为 keycode 0，非通话时这是暂停/播放。
     * 仅 VW CNS2 Delphi 20082114（[huVwCns2Delphi]，Dmitry）支持；其余车机一切照旧。
     */
    private fun delphiKey(code: Int, call: PhoneCall): Int? = when {
        !huVwCns2Delphi -> null
        call == PhoneCall.IDLE && code == HARD_KEY_SEEK_HOLD -> KEYCODE_MEDIA_PLAY_PAUSE
        call == PhoneCall.RINGING && code == HARD_KEY_SEEK_SUB -> KEYCODE_CALL
        call != PhoneCall.IDLE && code == HARD_KEY_SEEK_ADD -> KEYCODE_ENDCALL
        else -> null
    }

    /**
     * VW CNS2 Delphi（h425）在 1-2 毫秒内发送两次 VR_START：第二个 KEYCODE_SEARCH 会关闭刚打开的
     * 助手。间隔短于 [VOICE_KEY_DEBOUNCE_MS] 的语音键重复会被吞掉。
     */
    private fun voiceKeyBounce(what: String): Boolean {
        val now = clockMs()
        val dup = now - lastVoiceKeyMs < VOICE_KEY_DEBOUNCE_MS
        if (dup) log("BRIDGE $what: повтор кнопки голоса через ${now - lastVoiceKeyMs} мс, глотаю", true)
        else lastVoiceKeyMs = now
        return dup
    }

    /** 车机的 MODULE_CONTROL VR=1（语音按键）-> AA KEYCODE_SEARCH：AA 打开助手并自行申请麦克风。 */
    fun onCarlifeVoiceKey() {
        lastUserInputMs = clockMs()
        val p = peer
        if (p == null) {
            log("BRIDGE кнопка голоса проигнорирована: AA не подключён", true)
            return
        }
        if (voiceKeyBounce("кнопка голоса (MODULE_CONTROL VR=1)")) return
        log("BRIDGE кнопка голоса (MODULE_CONTROL VR=1) -> AA KEYCODE $KEYCODE_SEARCH", true)
        p.sendKey(KEYCODE_SEARCH)
    }

    // --- AA 事件 ---
    @Synchronized
    fun onAaConnecting() {
        resetTranscodeForAa()
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
    }

    @Synchronized
    fun onAaEnded(established: Boolean) {
        meta.onAaEnded()
        stopAllAudio("AA завершился")
        resetTranscodeForAa()
        val wasAa = active == Source.AA
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
        if (established || wasAa) placeholderText = TEXT_AA_LOST
        if (wasAa || established) {
            active = Source.PLACEHOLDER
            log("BRIDGE AA завершился: источник -> заглушка '$placeholderText'", true)
            link.showPlaceholder(placeholderText)
        }
    }

    // --- AA 声音 ---
    private fun carlifeChannel(aaChan: Int): Int? = when (aaChan) {
        AapMessages.CH_AUDIO_MEDIA -> CarlifeAudio.CH_MEDIA
        AapMessages.CH_AUDIO_SPEECH -> CarlifeAudio.CH_TTS      // 导航提示 / 语音
        else -> null                                              // SYSTEM (5)：AA 的信号不发给车机
    }

    private fun chName(ch: Int) = if (ch == CarlifeAudio.CH_MEDIA) "MEDIA" else "TTS"

    @Synchronized
    override fun onAudioStart(chan: Int) {
        log("BRIDGE AA audio START ch$chan (${AapMessages.channelName(chan)}) -> CarLife ${carlifeChannel(chan)?.let { chName(it) } ?: "не пересылается"}", true)
        if (carlifeChannel(chan) == CarlifeAudio.CH_MEDIA) releaseHuPause("AA MEDIA START")
        if (carlifeChannel(chan) == CarlifeAudio.CH_TTS && ttsStopTimer.cancel()) {
            log("BRIDGE TTS STOP отменён: SPEECH START до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        }
    }

    @Synchronized
    override fun onAudioData(chan: Int, pcm: ByteArray, ptsUs: Long) {
        val ch = carlifeChannel(chan)
        if (ch == null || !audioViaUsb || !carlifeStarted) {
            audioDropped++
            if (audioDropped <= 2 || audioDropped % 500 == 0L) {
                val why = when {
                    ch == null -> "SYSTEM-канал"
                    !audioViaUsb -> "звук по USB выключен/BT"
                    else -> "CarLife ещё не START"
                }
                log("BRIDGE AA audio ch$chan ${pcm.size} Б не пересылаю ($why), всего отброшено $audioDropped", false)
            }
            return
        }
        if (ch == CarlifeAudio.CH_TTS && (vrReply || (huVwCns2Delphi && voice && micModeProvider() != MicMode.PHONE))) {
            sendVrReply(chan, pcm)
            return
        }
        if (ch == CarlifeAudio.CH_TTS) {
            if (ttsStopTimer.cancel()) log("BRIDGE TTS STOP отменён: данные ch$chan до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        } else if (!admitMedia()) {
            return
        }
        if (audioInited[ch] != true) {
            val setting = if (ch == CarlifeAudio.CH_MEDIA) musicRateProvider() else MusicRate.AUTO
            val rate = when {
                ch != CarlifeAudio.CH_MEDIA -> 16000
                huPioneerRm56 -> MEDIA_RATE_PIONEER_RM56    // 与设置无关：USB 车机同时跑视频 + 音乐扛不住
                else -> setting.hz ?: if (huHyundaiCn7) MEDIA_RATE_CN7 else MEDIA_RATE
            }
            val channels = if (ch == CarlifeAudio.CH_MEDIA) 2 else 1
            if (ch == CarlifeAudio.CH_TTS && NAVI_STATUS_ENABLED) {
                ttsNaviHeld = true
                // 像 MD 一样：在 TTS 之前发送 MODULE_STATUS NAVI=1 并保持它
                if (!naviSent && link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                    naviSent = true
                    naviRaised()
                    log("BRIDGE CarLife MODULE_STATUS NAVI=1 (перед TTS INIT)", true)
                }
            }
            if (!link.sendAudio(ch, CarlifeAudio.initType(ch), CarlifeAudio.initBody(rate, channels, 16))) {
                audioDropped++
                return
            }
            audioInited[ch] = true
            log("BRIDGE CarLife ${chName(ch)} INIT ${rate}Гц ${channels}к 16бит (0x%08x)".format(CarlifeAudio.initType(ch)), true)
            if (ch == CarlifeAudio.CH_TTS) {
                val prerollMs = ttsPrerollProvider().ms ?: if (huVw20082120) TTS_PREROLL_MS else 0
                if (prerollMs > 0) sendTtsPreroll(prerollMs)
                ttsPackets = 0
                mediaGuard = false
                mediaReleaseTimer.cancel()
            } else {
                mediaPaused = false
                mediaGateDrops = 0
                // 频率在 MEDIA INIT 时固定；每次新的 INIT 都是从零开始的流
                resampler = if (rate == MEDIA_RATE) null else resampler?.takeIf { it.outRate == rate }?.also { it.reset() } ?: MusicResampler(rate)
                if (rate != MEDIA_RATE && rate != mediaRateLogged) {
                    val why = when {
                        huPioneerRm56 -> "авто: Pioneer RM56E8B"
                        setting.hz != null -> "настройка"
                        else -> "авто: Hyundai CN7"
                    }
                    log("BRIDGE музыка $rate Гц, ресэмпл $MEDIA_RATE→$rate ($why)", true)
                }
                mediaRateLogged = rate
                musicStatus(1)
            }
        }
        var out = pcm
        if (ch == CarlifeAudio.CH_MEDIA) {
            val eq = eqProvider()
            if (eq != eqLogged) {
                eqLogged = eq
                log("BRIDGE EQ: ${if (eq.isFlat) "без обработки" else "полосы ${eq.bandsCsv()} дБ, preamp ${eq.preamp} дБ"}", false)
            }
            equalizer.update(eq)
            equalizer.process(pcm)
            val rs = resampler
            if (rs != null) {
                out = rs.process(pcm)
                if (out.isEmpty()) return
            }
        }
        val chunkMax = when (ch) {
            CarlifeAudio.CH_MEDIA -> mediaChunkProvider().bytes ?: CarlifeAudio.MAX_CHUNK
            CarlifeAudio.CH_TTS -> voiceChunkMax()
            else -> CarlifeAudio.MAX_CHUNK
        }
        if (ch == CarlifeAudio.CH_MEDIA && chunkMax != mediaChunkLogged) {
            mediaChunkLogged = chunkMax
            log("BRIDGE музыка: пакеты по $chunkMax Б (настройка)", true)
        }
        var off = 0
        while (off < out.size) {
            val n = minOf(chunkMax, out.size - off)
            val chunk = if (off == 0 && n == out.size) out else out.copyOfRange(off, off + n)
            if (!link.sendAudio(ch, CarlifeAudio.dataType(ch), chunk)) {
                audioDropped++
            } else {
                audioBytes += n
                AaState.audioBytes = audioBytes
            }
            off += n
        }
        val c = (audioLogged[ch] ?: 0L) + 1
        audioLogged[ch] = c
        if (c <= 2 || c % 200 == 0L) log("BRIDGE AA audio ch$chan ${pcm.size} Б -> CarLife ${chName(ch)} (#$c, всего ${audioBytes / 1024} КиБ)", false)
        if (ch == CarlifeAudio.CH_TTS && ++ttsPackets <= TTS_DIAG_PACKETS) {
            val (peak, rms) = CarlifeAudio.pcm16Stats(pcm)
            log("BRIDGE TTS пакет #$ttsPackets: ${pcm.size} Б, peak=$peak rms=%.1f${if (peak == 0) " (ВСЕ НУЛИ)" else ""}".format(rms), false)
        }
    }

    @Synchronized
    override fun onAudioStop(chan: Int) {
        val ch = carlifeChannel(chan) ?: return
        log("BRIDGE AA audio STOP ch$chan", true)
        if (vrReply && ch == CarlifeAudio.CH_TTS) {
            vrReply = false
            link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_STOP, ByteArray(0))
            log("BRIDGE CarLife VR STOP (0x%08x)".format(CarlifeAudio.VR_STOP), true)
            vrModuleOff("последний VR STOP после голосовой сессии")
            return
        }
        if (ch == CarlifeAudio.CH_MEDIA) {
            requestMediaPause("AA STOP ch$chan")
        } else if (TTS_DEBOUNCE_ENABLED) {
            if (audioInited[ch] == true && !ttsStopTimer.pending) {
                log("BRIDGE CarLife TTS STOP отложен на ${TTS_STOP_DELAY_MS} мс", true)
                ttsStopTimer.start(TTS_STOP_DELAY_MS) { ttsStopNow() }
            }
        } else {
            stopAudio(ch)
        }
    }

    /**
     * 语音会话期间 AA 的语音 -> VR 通道（VR_INIT 16 kHz 单声道，VR_DATA），如同 Baidu 助手的应答。
     * VW CNS2 Delphi（h425）不播放紧跟在麦克风录音之后到达 TTS 的助手语音。
     */
    private fun sendVrReply(chan: Int, pcm: ByteArray) {
        if (!vrReply) {
            if (audioInited[CarlifeAudio.CH_TTS] == true) stopAudio(CarlifeAudio.CH_TTS)
            if (!link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_INIT, CarlifeAudio.initBody(16000, 1, 16))) {
                audioDropped++
                return
            }
            vrReply = true
            ttsPackets = 0
            log("BRIDGE CarLife VR INIT 16000Гц 1к 16бит (0x%08x): речь AA ch$chan в голосовой сессии".format(CarlifeAudio.VR_INIT), true)
        }
        val chunkMax = voiceChunkMax()
        var off = 0
        while (off < pcm.size) {
            val n = minOf(chunkMax, pcm.size - off)
            val chunk = if (off == 0 && n == pcm.size) pcm else pcm.copyOfRange(off, off + n)
            if (!link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_DATA, chunk)) audioDropped++ else audioBytes += n
            off += n
        }
        AaState.audioBytes = audioBytes
        if (++ttsPackets <= TTS_DIAG_PACKETS) log("BRIDGE VR пакет #$ttsPackets: ${pcm.size} Б", false)
    }

    /**
     * h425：VW CNS2 Delphi 在 VR 通道应答之后，会把音量键留在语音通道上，直到重新插拔
     * 线缆。关闭麦克风时会发出 MODULE_STATUS VR=0，但 VR 应答在其之后才到：因此当 AA 语音会话
     * 已结束且 VR 流已关闭时，再补发一次 VR=0。
     */
    private fun vrModuleOff(why: String) {
        if (!huVwCns2Delphi || voice || vrReply) return
        if (link.sendModuleStatus(CarlifeAudio.MODULE_VR, 0)) log("BRIDGE CarLife MODULE_STATUS VR=0 ($why)", true)
    }

    // --- 助手语音会话：MEDIA 暂停，不转发数据；退出时带批次平滑 2/2/1/2/2 ---
    @Synchronized
    override fun onVoiceSession(status: Int) {
        if (!VOICE_SESSION_ENABLED) return
        when (status) {
            1 -> {
                val cancelled = voiceExitTimer.cancel()
                if (!voice) {
                    voice = true
                    log("BRIDGE голосовая сессия: старт, MEDIA на паузу, данные MEDIA не пересылаю", true)
                    requestMediaPause("голосовая сессия")
                } else if (cancelled) {
                    log("BRIDGE голосовая сессия: выход отменён (снова status=1)", true)
                }
            }
            2 -> if (voice && !voiceExitTimer.pending) {
                voiceExitTimer.start(VOICE_EXIT_DELAY_MS) {
                    voice = false
                    log("BRIDGE голосовая сессия: выход, MEDIA поднимется через INIT на первых данных", true)
                    vrModuleOff("выход из голосовой сессии")
                }
            }
            else -> log("BRIDGE голосовая сессия: status=$status не знаю, игнор", false)
        }
    }

    // --- MEDIA 控制包（PAUSE）排队发送：TTS 初始化期间或 TTS_STOP+300 毫秒之前不发 ---
    private fun mediaBlocked() = TTS_DEBOUNCE_ENABLED && (audioInited[CarlifeAudio.CH_TTS] == true || mediaGuard)

    /** MEDIA 的期望状态 = 暂停：立即执行，或 TTS 忙时在 TTS_STOP+300 毫秒后执行。 */
    private fun requestMediaPause(why: String) {
        if (audioInited[CarlifeAudio.CH_MEDIA] != true) {
            pendingMediaPause = false
            return
        }
        if (mediaBlocked()) {
            pendingMediaPause = true
            log("BRIDGE MEDIA пауза отложена ($why): TTS занят, применю после TTS_STOP+${MEDIA_AFTER_TTS_MS} мс", true)
            return
        }
        pendingMediaPause = false
        pauseMedia(why)
    }

    private fun pauseMedia(why: String) {
        if (!MEDIA_PAUSE_ENABLED) {
            stopAudio(CarlifeAudio.CH_MEDIA)
            return
        }
        audioInited[CarlifeAudio.CH_MEDIA] = false
        mediaPaused = true
        link.sendAudio(CarlifeAudio.CH_MEDIA, CarlifeAudio.MEDIA_PAUSE, ByteArray(0))
        log("BRIDGE CarLife MEDIA PAUSE (0x%08x), $why".format(CarlifeAudio.MEDIA_PAUSE), true)
        musicStatus(0)
    }

    /** 语音包大小：显式指定或按车机选择；null = 按 AA 原样给出（其余车机上为「自动」）。 */
    private fun voiceChunkOverride(): Int? = voiceChunkProvider().bytes ?: if (huVwCns2Delphi) VOICE_CHUNK_DELPHI else null

    /** 当前发送用的语音包大小（VR 应答与 TTS）；值发生变化时只记一次日志。 */
    private fun voiceChunkMax(): Int {
        val size = voiceChunkOverride() ?: CarlifeAudio.MAX_CHUNK
        if (size != voiceChunkLogged) {
            voiceChunkLogged = size
            log("BRIDGE голос: пакеты по $size Б (${if (voiceChunkProvider().bytes != null) "настройка" else "авто ГУ"})", true)
        }
        return size
    }

    /**
     * TTS_INIT 之后立刻发送静音（VW CNS3 20082122，h222：提示音开头有嘶嘶声）。Baidu 会攒整句
     * 语音，并以 4096 字节的包快于实时速度发送（NaviTTSController.splitAndSendTTSData），车机端
     * 有缓冲余量；AA 从第一个包起按实时速率吐出语音，没有余量。零数据让车机在静音上打开
     * 通路，并使缓冲区领先 [ms]，直到整句结束。零数据块按语音包大小切分——
     * 若该大小已指定（设置项或 Delphi 上的自动 1024），否则用 TTS_PREROLL_CHUNK。
     */
    private fun sendTtsPreroll(ms: Int) {
        val zeros = ByteArray((ms * TTS_BYTES_PER_SEC / 1000) and 1.inv())
        val chunkMax = voiceChunkOverride() ?: TTS_PREROLL_CHUNK
        var off = 0
        while (off < zeros.size) {
            val n = minOf(chunkMax, zeros.size - off)
            if (!link.sendAudio(CarlifeAudio.CH_TTS, CarlifeAudio.TTS_DATA, zeros.copyOfRange(off, off + n))) {
                log("BRIDGE TTS пре-ролл: очередь полна, отправлено ${off * 1000 / TTS_BYTES_PER_SEC} из $ms мс", true)
                return
            }
            off += n
        }
        log("BRIDGE TTS пре-ролл $ms мс тишины (${zeros.size} Б) после INIT", true)
    }

    /** 实际的 TTS_STOP（debounce 定时器）；之后 MEDIA 包还要再等 MEDIA_AFTER_TTS_MS。 */
    private fun ttsStopNow() {
        stopAudio(CarlifeAudio.CH_TTS)
        mediaGuard = true
        mediaReleaseTimer.start(MEDIA_AFTER_TTS_MS) {
            mediaGuard = false
            if (pendingMediaPause) {
                pendingMediaPause = false
                if (audioInited[CarlifeAudio.CH_MEDIA] == true) pauseMedia("отложенная пауза после TTS")
            }
        }
    }

    /** 是否放行 MEDIA_DATA；否 = 数据被丢弃（语音中 / TTS 后等待 INIT）。 */
    private fun admitMedia(): Boolean {
        val why = when {
            VOICE_SESSION_ENABLED && voice -> "идёт голосовая сессия"
            huPausedAa -> "ГУ на своём источнике, AA на паузе (хвост буфера)"
            audioInited[CarlifeAudio.CH_MEDIA] != true && mediaBlocked() -> "TTS занят, MEDIA ждёт INIT"
            else -> null
        }
        if (why != null) {
            mediaGateDrops++
            if (mediaGateDrops == 1L || mediaGateDrops % 500 == 0L) log("BRIDGE AA audio MEDIA данные отброшены: $why (#$mediaGateDrops)", false)
            return false
        }
        if (pendingMediaPause) {
            pendingMediaPause = false
            log("BRIDGE отложенная пауза MEDIA отменена: данные снова идут", true)
        }
        return true
    }

    private fun musicStatus(status: Int) {
        if (MODULE_MUSIC_ENABLED) link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, status)
    }

    /** AA 播放器切换了状态（ch9）。仅当声音不走 USB 时才据此发 MUSIC：否则 MUSIC 由 MEDIA INIT/PAUSE 掌控。 */
    private fun musicByState(playing: Boolean) {
        if (playing) releaseHuPause("AA заиграл")
        else log("BRIDGE AA плеер остановился: наш KEYCODE $KEYCODE_MEDIA_PAUSE ${ago(lastPauseKeyMs)}, ввод пользователя ${ago(lastUserInputMs)}, BT HFP ${ago(lastHfpConnectMs)}", true)
        if (audioViaUsb || !MODULE_MUSIC_ENABLED || !carlifeStarted) return
        if (link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (playing) 1 else 0)) {
            log("BRIDGE CarLife MODULE_STATUS MUSIC=${if (playing) 1 else 0} (плеер AA, звук не по USB)", true)
        }
    }

    /** 路线持有 / 释放 NAVI。只有 TTS 也未持有 NAVI 时，才发出 NAVI=0。 */
    private fun routeNavi(on: Boolean) {
        if (!ROUTE_NAVI_STATUS_ENABLED) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (только вокруг TTS)", false)
            return
        }
        if (huDesayG6S) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (Desay G6S: глушит радио)", false)
            return
        }
        routeNaviHeld = on
        if (on) naviEndPending = false
        if (on && !naviSent) {
            if (link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                naviSent = true
                naviRaised()
                log("BRIDGE CarLife MODULE_STATUS NAVI=1 (маршрут)", true)
            }
        } else if (!on && naviSent && !ttsNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0 (маршрут закончен)", true)
        } else if (!on && naviSent) {
            naviEndPending = true
            log("BRIDGE маршрут закончен, NAVI=1 держит TTS", false)
        }
    }

    private fun stopAudio(ch: Int) {
        if (ch == CarlifeAudio.CH_TTS) ttsStopTimer.cancel()
        val wasInited = audioInited[ch] == true
        if (wasInited || (ch == CarlifeAudio.CH_MEDIA && mediaPaused)) {
            audioInited[ch] = false
            if (ch == CarlifeAudio.CH_MEDIA) {
                mediaPaused = false
                if (wasInited) musicStatus(0)
            }
            link.sendAudio(ch, CarlifeAudio.stopType(ch), ByteArray(0))
            log("BRIDGE CarLife ${chName(ch)} STOP (0x%08x)".format(CarlifeAudio.stopType(ch)), true)
        }
        if (ch == CarlifeAudio.CH_TTS) releaseTtsNavi()
    }

    /**
     * TTS 结束：不再需要为它保持 NAVI。如果路线已经结束（最后一句「您已到达」是在 INACTIVE
     * 之后播放的），NAVI=0 就在这里发出，否则仪表盘会一直停留在导航画面直到会话结束。
     */
    private fun releaseTtsNavi() {
        ttsNaviHeld = false
        if (naviEndPending && naviSent && !routeNaviHeld) {
            naviEndPending = false
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0 (TTS закончился, маршрута нет)", true)
        }
    }

    /** 重置 CarLife 会话的声音状态（START / 关闭）：链接已是新实例或已失效，什么也不发。 */
    private fun resetAudio() {
        audioInited.clear()
        ttsStopTimer.cancel()
        mediaReleaseTimer.cancel()
        naviSent = false
        naviEndPending = false
        ttsNaviHeld = false
        mediaPaused = false
        mediaGuard = false
        pendingMediaPause = false
        mediaGateDrops = 0
    }

    private fun stopAllAudio(why: String) {
        ttsStopTimer.cancel()
        mediaReleaseTimer.cancel()
        voiceExitTimer.cancel()
        voice = false
        mediaGuard = false
        pendingMediaPause = false
        if (audioInited.values.any { it } || mediaPaused) log("BRIDGE останавливаю звук CarLife: $why", true)
        stopAudio(CarlifeAudio.CH_MEDIA)
        stopAudio(CarlifeAudio.CH_TTS)
        ttsNaviHeld = false
        if (naviSent && !routeNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0", true)
        }
    }

    // --- AA 元数据 ---
    @Synchronized
    override fun onMediaStatus(s: AaMediaStatus) = meta.onMediaStatus(s)

    @Synchronized
    override fun onMediaMetadata(m: AaMediaMetadata) = meta.onMediaMetadata(m)

    @Synchronized
    override fun onNavCluster(start: Boolean) = meta.onNavCluster(start)

    @Synchronized
    override fun onNavStatus(status: Int) = meta.onNavStatus(status)

    @Synchronized
    override fun onNavTurn(t: AaNavTurn) = meta.onNavTurn(t)

    @Synchronized
    override fun onNavDistance(d: AaNavDistance) = meta.onNavDistance(d)

    // --- AA 视频 ---
    @Synchronized
    override fun onCodecConfig(annexB: ByteArray) {
        aaVideoSeen = true
        aaConfig = AnnexB.normalize(annexB)
        log("BRIDGE AA CODEC_CONFIG ${annexB.size} Б nal=${AnnexB.nalTypes(aaConfig!!)}", true)
        if (carlifeStarted) transcoder?.feedConfig(aaConfig!!)
    }

    @Synchronized
    override fun onFrame(annexB: ByteArray, ptsUs: Long) {
        aaVideoSeen = true
        AaState.frames.incrementAndGet()
        AaState.bytes += annexB.size
        if (!carlifeStarted) {
            drop("CarLife ещё не прислал START")
            return
        }
        val b = AnnexB.normalize(annexB)
        val types = AnnexB.nalTypes(b)
        lastAaFrameAt = clockMs()
        aaFrames++
        val t = transcoder
        if (t != null) {
            feedTranscoder(t, b, types, ptsUs)
            return
        }
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR nal=$types")
                return
            }
            val payload = if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            }
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                drop("первый IDR не принят CarLife (очередь/закрыто)")
                return
            }
            gotIdr = true
            waitSince = 0
            sent++
            idrSent++
            lastIdrSentMs = clockMs()
            h295IdrSeen(b, payload)
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> AA (первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)} (SPS/PPS ${if (payload.size != b.size) "склеены" else "в кадре"})", true)
            }
            AaState.status = "мост: AA -> CarLife"
            return
        }
        val out = h295Prepare(b, types)
        if (!link.sendVideo(out)) {
            // 帧丢失：车机解码器会错乱，等待新 IDR
            gotIdr = false
            waitSince = clockMs()
            drop("кадр не принят CarLife, жду новый IDR")
            return
        }
        sent++
        if (5 in types) {
            idrSent++
            lastIdrSentMs = clockMs()
            h295IdrSeen(b, out)
        }
        AaState.bridgeSent = sent
    }

    private fun drop(why: String) {
        dropped++
        AaState.bridgeDropped = dropped
        dropLogged++
        if (dropLogged <= 3 || dropLogged % 100 == 0L) log("BRIDGE отброшен AA-кадр (#$dropped): $why", false)
    }

    /** 每 0.5 秒一次：START 后长时间无 IDR 时请求关键帧。 */
    @Synchronized
    fun tick() {
        logStats()
        if (!carlifeStarted) return
        val now = clockMs()
        h295GiveUp(now)
        if (gotIdr || !aaVideoSeen) return
        if (waitSince == 0L) waitSince = now
        if (now - waitSince >= KEYFRAME_WAIT_MS && now - lastKeyframeRequest >= KEYFRAME_RETRY_MS) {
            lastKeyframeRequest = now
            keyframeRequests++
            log("BRIDGE жду IDR от AA ${(now - waitSince) / 1000.0} с: запрашиваю ключевой кадр (VideoFocus NATIVE -> PROJECTED) #$keyframeRequests", true)
            peer?.requestKeyFrame()
        }
    }

    // --- 转码 ---
    private fun startTranscoder() {
        outGotIdr = false
        outputEnabled = false
        var t = transcoder
        if (t == null) {
            t = link.createTranscoder(this)
            transcoder = t
            if (t == null) {
                log("BRIDGE !!!!!! транскодер не поднялся (MediaCodec), откат на passthrough", true)
                val g = videoMode
                if (g is CustomVideoMode) {
                    // TODO：通过重建 host 回退到 R720；目前车机在等 out 帧，却会收到带边距的 AA 占位帧
                    log("BRIDGE !!!!!! своё разрешение ${g.outW}x${g.outH} без транскодера не получится: ГУ ждёт ${g.outW}x${g.outH}, " +
                        "а уйдёт кадр AA ${g.aaResolution} с полями ${g.marginW}x${g.marginH}. Выключите «Своё разрешение» или задайте другой размер", true)
                }
                return
            }
        }
        log("BRIDGE видео: режим перекодирование (AA -> декодер -> энкодер, повтор кадра 100 мс)", true)
        aaConfig?.let { t.feedConfig(it) }
        t.requestSyncFrame()        // 编码器：只要帧开始流入，就在 START 时出关键帧
    }

    private fun stopTranscoderAsync() {
        val t = transcoder ?: return
        transcoder = null
        encCsd = null
        outGotIdr = false
        outputEnabled = false
        Thread({ t.stop() }, "aa-transcoder-stop").start()
    }

    private fun resetTranscodeForAa() {
        outputEnabled = false
        outGotIdr = false
        transcoder?.resetDecoder()
    }

    /** AA 帧 -> 解码器。START / 重建后第一个 IDR 之前的帧被丢弃（解码器需要 IDR）。 */
    private fun feedTranscoder(t: VideoTranscoder, b: ByteArray, types: List<Int>, ptsUs: Long) {
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR (декодер ждёт) nal=$types")
                return
            }
            val payload = if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            }
            gotIdr = true
            waitSince = 0
            outputEnabled = true
            log("BRIDGE AA IDR -> декодер (${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
            // 从占位页过渡：编码器将以其帧开始，并把它渐变为 AA（在 feedFrame 之前调用，让 GL 线程先于第一帧将它备好）
            if (active != Source.AA) link.placeholderSnapshot()?.let { t.startIntro(it) }
            t.feedFrame(payload, ptsUs)
            t.requestSyncFrame()    // 输出开启后编码器的第一帧立即为 IDR，无需等到约 1 秒
            return
        }
        t.feedFrame(b, ptsUs)
    }

    @Synchronized
    override fun onEncoderConfig(csd: ByteArray) {
        encCsd = csd
        log("BRIDGE энкодер: SPS+PPS ${csd.size} Б nal=${AnnexB.nalTypes(csd)}", false)
    }

    /** 编码器帧 -> CarLife。源的第一条消息 = SPS+PPS+IDR（SPS/PPS 拼接），IDR 之前的 P 帧丢弃。 */
    @Synchronized
    override fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (!outputEnabled || !carlifeStarted) {
            encDropped++
            return
        }
        val types = AnnexB.nalTypes(data)
        val idr = 5 in types
        if (!outGotIdr) {
            if (!idr) {
                encDropped++
                return
            }
            val payload = if (AnnexB.startsWithSps(data) && 8 in types) data else {
                val cfg = encCsd
                if (cfg == null) {
                    encDropped++
                    return
                }
                AnnexB.glue(cfg, data)
            }
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                encDropped++
                requestIdrOnOverflow()
                return
            }
            outGotIdr = true
            sent++
            idrSent++
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> AA (перекодирование, первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR энкодера -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)}", true)
            }
            AaState.status = "мост: AA -> CarLife (перекодирование)"
            if (huVw20082138) link.onEncodedKeyFrame(payload.size)
            return
        }
        if (!link.sendVideo(data)) {
            // 帧未被接收（CarLife 队列已满）：之后不能发 P 帧，下一个 IDR 之前全部由上文丢弃
            outGotIdr = false
            encDropped++
            requestIdrOnOverflow()
            return
        }
        sent++
        if (idr) idrSent++
        AaState.bridgeSent = sent
        if (idr && huVw20082138) link.onEncodedKeyFrame(data.size)
    }

    /**
     * CarLife 队列拒绝之后，编码器的 IDR 频率不超过每 1.5 秒一次（IdrThrottle）。否则大 IDR（30-160 KB）
     * 会自己堵死慢速通道。被拒后——等待已请求的或最近的自然 IDR（间隔 1 秒）。
     */
    private fun requestIdrOnOverflow() {
        val t = transcoder ?: return
        if (!idrThrottle.tryRequest(clockMs())) return
        overflowIdrRequests++
        t.requestSyncFrame()
    }

    /** 动态调整编码器码率（适配车机的消费速率）。false = 没有转码器（passthrough 或尚未创建）。 */
    @Synchronized
    fun setEncoderBitrate(bps: Int): Boolean {
        val t = transcoder ?: return false
        t.setBitrate(bps)
        return true
    }

    /** 动态调整转码器目标 fps（来自车机的 FRAME_RATE_CHANGE）。false = 没有转码器（passthrough 或尚未创建）。 */
    @Synchronized
    fun setTargetFps(fps: Int): Boolean {
        val t = transcoder ?: return false
        t.setTargetFps(fps)
        return true
    }

    @Synchronized
    override fun onNeedAaKeyFrame() {
        gotIdr = false
        waitSince = clockMs()
        log("BRIDGE декодер потерял кадр: жду новый IDR от AA", true)
    }

    @Synchronized
    override fun onTranscodeError(msg: String) {
        log("BRIDGE !!!!!! ошибка транскодера: $msg. Откат на passthrough", true)
        stopTranscoderAsync()
        gotIdr = false
        waitSince = clockMs()
    }

    /** 车机请求关键帧（VIDEO_ENCODER_RESET 等）。 */
    @Synchronized
    fun onCarlifeKeyFrameRequest() {
        val t = transcoder
        if (t != null) {
            t.requestSyncFrame()
        } else if (carlifeStarted) {
            gotIdr = false
            waitSince = clockMs() - KEYFRAME_WAIT_MS
        }
        log("BRIDGE ГУ просит ключевой кадр (${if (t != null) "sync frame энкодера" else "ждём IDR от AA"})", true)
    }

    /** 每 5 秒一次：模式、输出 fps、转码器统计；passthrough 下——AA 空闲时长（不发 keepalive）。 */
    private fun logStats() {
        if (!carlifeStarted) return
        val now = clockMs()
        if (lastStatsAt == 0L) {
            lastStatsAt = now
            lastStatsSent = sent
            lastStatsAaFrames = aaFrames
            return
        }
        if (now - lastStatsAt < 5000) return
        val sec = (now - lastStatsAt) / 1000.0
        val fps = (sent - lastStatsSent) / sec
        val aaFps = (aaFrames - lastStatsAaFrames) / sec
        val t = transcoder
        val idle = if (lastAaFrameAt > 0 && now - lastAaFrameAt > 1000) " простой AA ${(now - lastAaFrameAt) / 1000.0} с" else ""
        val target = if (t != null) "цель ${t.targetFps} fps" else "fps не регулируется"
        val idrReq = overflowIdrRequests - lastStatsIdrRequests
        val ls = link.videoStats()
        val channel = if (ls == null) "" else
            "; канал: ${if (t != null) "энкодер ${t.currentBitrate / 1000} кбит/с, " else ""}слив %.0f КиБ/с, бэклог ${ls.backlogMs} мс, очередь ${ls.queueFrames} кадр/${ls.queueBytes / 1024} КБ, запросов IDR из-за переполнения $idrReq за окно"
                .format(ls.drainBytesPerSec / 1024)
        log(
            "BRIDGE видео[${if (t != null) "перекодирование" else "passthrough"}]: AA вход %.1f fps, выход в CarLife %.1f fps ($target), отброшено энкодер/гейт $encDropped/$dropped$idle".format(aaFps, fps) +
                channel + (if (t != null) "; ${t.statsLine()}" else ""), true
        )
        lastStatsAt = now
        lastStatsSent = sent
        lastStatsAaFrames = aaFrames
        lastStatsIdrRequests = overflowIdrRequests
    }

    companion object {
        /** CarlifeThemeState.themeModes（CarLife 8.6.7 中的 SkinDisplayModeManager）。 */
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_AUTO = 2

        fun themeName(mode: Int) = when (mode) {
            THEME_LIGHT -> "светлая"; THEME_DARK -> "тёмная"; THEME_AUTO -> "авто"; else -> "?"
        }

        /** action&255 中发送给 AA 的 TOUCH_ACTION（AA 有相同的 PointerAction 代码）。 */
        private val TOUCH_NAMES = mapOf(
            AapMessages.TOUCH_DOWN to "DOWN", AapMessages.TOUCH_UP to "UP", AapMessages.TOUCH_MOVE to "MOVE",
            AapMessages.TOUCH_POINTER_DOWN to "POINTER_DOWN", AapMessages.TOUCH_POINTER_UP to "POINTER_UP",
        )

        // 新声音行为的开关（默认开启）：在车上逐个关闭。
        /** 第一个 TTS_INIT 之前发 MODULE_STATUS NAVI=1，声音停止时发 NAVI=0。 */
        @Volatile @JvmStatic var NAVI_STATUS_ENABLED = true

        /** TTS_STOP 去抖（1500 毫秒），MEDIA 控制包排队至 TTS_STOP+300 毫秒。 */
        @Volatile @JvmStatic var TTS_DEBOUNCE_ENABLED = true

        /** 助手语音会话：MEDIA 暂停，不转发 MEDIA_DATA，500 毫秒后退出。 */
        @Volatile @JvmStatic var VOICE_SESSION_ENABLED = true

        /** MODULE_STATUS MUSIC(3) 随 MEDIA INIT/PAUSE/STOP 一起发送，并应答 MODULE_CONTROL（CarLifeSession）。 */
        @Volatile @JvmStatic var MODULE_MUSIC_ENABLED = true

        /** onAudioStop(MEDIA) 发送 MEDIA_PAUSE（像 MD 那样）而非 MEDIA_STOP；关闭 = 旧行为。 */
        @Volatile @JvmStatic var MEDIA_PAUSE_ENABLED = true

        /**
         * MODULE_STATUS NAVI=1 覆盖整个路线（MetaRelay）：没有它，Honda Vezel 的仪表盘会显示「Turn by turn waiting
         * for your smartphone」。Desay G6S（Mazda CX-5）在进入 CarLife 时 NAVI=1 会压低原厂收音机（Ivan 的日志，
         * 2026-10-12），那里只在 TTS 前后发 NAVI=1（huDesayG6S）。关闭 = 所有车机都只在 TTS 前后发 NAVI=1。
         */
        @Volatile @JvmStatic var ROUTE_NAVI_STATUS_ENABLED = true

        /** 车机切回自己的音源（MODULE_CONTROL MUSIC=0）：AA 暂停；START 时不发 play。 */
        @Volatile @JvmStatic var HU_MUSIC_FOLLOW_ENABLED = true

        /** Desay G6S：我们因 MUSIC=0 暂停之后收到 MUSIC=1——发 AA play（KEYCODE 126）并应答 MUSIC=1。 */
        @Volatile @JvmStatic var HU_MUSIC_RESUME_ENABLED = true

        /** 在我们的 play 之后、MEDIA INIT 尚未到达期间，对一串 MUSIC=1 应答 MUSIC=1 的时长。 */
        const val HU_RESUME_ANSWER_MS = 3_000L

        /**
         * Mazda CX-5：AA 会在 BT_HFP_CONNECTION 之后 5-9 秒自行启动音乐。车机还停在自家音源上时，这种 play
         *（在 HFP 之后的窗口内、无车机输入）不会解除暂停，而是再次收到 KEYCODE 127。
         */
        @Volatile @JvmStatic var BT_AUTOPLAY_GUARD_ENABLED = true

        /** 无线模式，VW BT 1420：PAUSE 之后的 START 直接用编码器的 IDR（解码器仍存活），不等待 AA 的 IDR。 */
        @Volatile @JvmStatic var AIR_WARM_RESUME_ENABLED = true

        const val BT_AUTOPLAY_WINDOW_MS = 20_000L
        const val USER_INPUT_WINDOW_MS = 3_000L
        private const val REPAUSE_MIN_MS = 1_000L

        const val TTS_STOP_DELAY_MS = 1500L
        const val MEDIA_AFTER_TTS_MS = 300L
        const val VOICE_EXIT_DELAY_MS = 500L
        private const val TTS_DIAG_PACKETS = 3
        /**
         * VW 20082120/20082122/20082134（h222）上默认（自动）的 TTS 静音预填充；16 kHz 单声道 16 位，分块同 Baidu。
         * 400 毫秒未经测量取定（CNS3 提示音开头有嘶嘶声），时长由设置 [TtsPreroll] 调整。
         */
        const val TTS_PREROLL_MS = 400
        private const val TTS_BYTES_PER_SEC = 16000 * 2
        private const val TTS_PREROLL_CHUNK = 4096
        /** VW CNS2 Delphi（h425）上的自动语音包大小：MAX_CHUNK/10 时助手应答的结巴消失；AA 按 2048 字节吐出语音。 */
        private const val VOICE_CHUNK_DELPHI = 1024

        /** Android KEYCODE_SEARCH：在 AA 中启动语音助手。 */
        const val KEYCODE_SEARCH = 84
        const val VOICE_KEY_DEBOUNCE_MS = 500L
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_CALL = 5
        const val KEYCODE_ENDCALL = 6
        const val HARD_KEY_SEEK_SUB = 0x0F
        const val HARD_KEY_SEEK_ADD = 0x10
        /** VW CNS2 Delphi 20082114：长按向前/向后会以 keycode 0 到达（Dmitry，24.10）。 */
        const val HARD_KEY_SEEK_HOLD = 0

        /**
         * CarLife 硬按键（HUJ/CommonParams）-> Android KeyEvent；所有值都在 AapMessages.KEYCODES_SUPPORTED 中。
         * 音量、HOME、MEDIA/RADIO/NAVI 和数字键不做映射：车机自己处理，或者没有对应项。
         */
        val HARD_KEY_TO_ANDROID: Map<Int, Int> = mapOf(
            0x10 to 87,     // SEEK_ADD -> MEDIA_NEXT
            0x0F to 88,     // SEEK_SUB -> MEDIA_PREVIOUS
            0x1F to 126,    // MEDIA_START -> MEDIA_PLAY
            0x20 to 127,    // MEDIA_STOP -> MEDIA_PAUSE
            0x02 to 5,      // PHONE_CALL -> CALL
            0x03 to 6,      // PHONE_END -> ENDCALL
            0x0E to 4,      // BACK
            0x14 to 23,     // OK -> DPAD_CENTER
            0x15 to 21,     // MOVE_LEFT -> DPAD_LEFT
            0x16 to 22,     // MOVE_RIGHT -> DPAD_RIGHT
            0x17 to 19,     // MOVE_UP -> DPAD_UP
            0x18 to 20,     // MOVE_DOWN -> DPAD_DOWN
            0x21 to KEYCODE_SEARCH, // VR_START -> 语音助手
            0x1E to 3,      // MAIN -> HOME（应用面板）
            0x09 to 209,    // MEDIA -> MUSIC（媒体播放器）
            0x0B to 65538,  // NAVI -> AA NAVIGATION（来自 KEY_BINDING_REQUEST AA 的代码）
        )

        /** 旋钮（Mazda Commander 等）-> AA rotary：在列表间移动焦点，OK（DPAD_CENTER）确认。 */
        val HARD_KEY_TO_ROTARY: Map<Int, Int> = mapOf(
            0x06 to 1,      // SELECTOR_NEXT -> 顺时针
            0x07 to -1,     // SELECTOR_PREVIOUS -> 逆时针
        )

        const val TEXT_CONNECTING = "Подключаю Android Auto..."
        const val TEXT_START_SERVER = "Запустите сервер ГУ в Android Auto"
        const val TEXT_WAIT_FRAME = "Android Auto: жду кадр..."
        const val TEXT_AA_LOST = "Android Auto отключился, переподключаю..."
        const val DESAY_MANUFACTURER = "Desay SV Automotive"
        const val DESAY_G6S_MODEL = "G6S"
        const val DESAY_G6S_CHANNEL = "20622116"          // Mazda CX-5 的 STATISTIC_INFO.channel；Vezel - 20832125，另一台 Mazda - 21520001
        /** 本田 STATISTIC_INFO.channel 的前缀（CarLife 1.1.0，HU_INFO 为空，BT「Honda HFT」）：2077xxxx（Civic 11、UR-V），2083xxxx（Vezel 等）。 */
        val HONDA_CHANNEL_PREFIXES = listOf("2077", "2083")
        /** 本田：NAVI=1 晚于此时长且其后没有出现箭头——0->1 跳变仍是新鲜的，在首个 SHOW 之前不重新置位。 */
        const val NAVI_REARM_FRESH_MS = 5000L    // 路线开始后 AA 的首个 TURN_EVENT 约 2 秒到达

        fun isHondaChannel(channel: String?) =
            channel != null && channel.length == 8 && HONDA_CHANNEL_PREFIXES.any { channel.startsWith(it) }
        const val HONDA_20772121_CHANNEL = "20772121"     // 本田 Civic 11 2023（中国，h295）：摄像头/菜单之后车机不请求 IDR
        /** Mazda Connect 2（中国，CarLife 1.0，HU_INFO 为占位值，h238/h359）：VIDEO_DATA 16163 字节可通过，16518 和 19104 字节——车机停止读取 USB。 */
        const val MAZDA_20622111_CHANNEL = "20622111"
        const val MAZDA_20622111_MAX_VIDEO_PACKET = 15_000
        const val MAZDA_20622111_ART_BYTES = 12_000          // MEDIA_INFO 中的封面：整个包必须保持在 < 15000 字节
        const val MAZDA_20622111_CEILING_BPS = 1_500_000  // IDR 更小，以防车机不拼接分片
        const val VW_20082120_CHANNEL = "20082120"        // VW，协议 1.0，versionName="RADIO"（h93）
        const val VW_CNS3_20082122_CHANNEL = "20082122"   // VW CNS3（Tharu 等）：无 heartbeat 的 PAUSE 约 5.6 秒后断开 USB（h222）
        const val VW_CNS3_20082134_CHANNEL = "20082134"   // VW CNS3 Tiguan L Pro：无 heartbeat 的 PAUSE 约 5.1-5.7 秒后断开 Wi-Fi CarLife（h224）
        const val VW_CNS2_20082114_CHANNEL = "20082114"   // VW CNS2 Delphi（Tharu 2020）：PAUSE 6-8 秒后断开 USB（h425）
        const val VW_20082138_CHANNEL = "20082138"        // VW ID（h196）：HU_INFO manufacturer=model="VW BT 1538"
        const val VW_BT_1420_MANUFACTURER = "VW BT 1420"  // VW（Pavel）：HU_INFO manufacturer=model，通道 20082138
        const val VW_20082138_MANUFACTURER = "VW BT 1538"
        const val HYUNDAI_CN7_PRODUCT = "wp_cn7cpe_cn"    // HU_INFO.product 现代 Elantra CN7（h218）：manufacturer QTI，model "sm6150_au for arm64"
        const val PIONEER_MANUFACTURER = "Pioneer"        // 车机 Pioneer RM56E8B（东风 DF6，h277）：manufacturer="Pioneer" model="RM56E8B"
        const val PIONEER_RM56_MODEL = "RM56E8B"
        const val PIONEER_RM56_CEILING_BPS = 800_000      // 4-5 Mbps 在 25-30 秒内断开 USB，2 Mbps - 195 秒，800 kbps - 1301 秒；test1：1 Mbps + 32 kHz 音乐（约 1 Mbps PCM）在 23-140 秒内断开
        const val PIONEER_RM56_MAX_FPS = 10
        const val MEDIA_RATE = 48000
        const val MEDIA_RATE_CN7 = 32000
        const val MEDIA_RATE_PIONEER_RM56 = 22050         // h277：32 kHz 音乐在 USB 上增加约 1 Mbps，22.05 kHz - 少一半
        const val KEYFRAME_WAIT_MS = 1500L
        const val KEYFRAME_RETRY_MS = 4000L

        /** h295：passthrough 下 IDR 老于该阈值时车机 20772121 上的触摸——很可能车机重置了解码器（摄像头/菜单）。 */
        const val H295_IDR_STALE_MS = 2500L
        const val H295_TRIGGER_MIN_MS = 3000L
        const val H295_TOUCH_IDLE_MS = 4000L      // 触发前车机触摸的停顿（摄像头/菜单）
        const val H295_WAIT_GIVEUP_MS = 10_000L   // 循环之后等待 IDR 用于日志和 SPS/PPS 拼接，不超过此时长
        private const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * 维持桥接的 AA 会话：连接到车机服务器，连接被拒（connection refused）时每 3 秒重试一次，
 * 会话结束时通知核心并重新连接。车机服务器由用户启动和重启。
 */
class AaConnector(
    private val core: AaBridgeCore,
    private val creds: AapCredentials,
    private val log: (String, Boolean) -> Unit,
    private val options: () -> AapOptions = { AapOptions() },
    private val retryMs: Long = 3000,
    private val failures: ServerFailures = AaState.serverFailures,
    private val sessionFactory: (AaVideoSink, AapOptions, (String, Boolean) -> Unit, (String, Boolean) -> Unit) -> AapSession =
        { sink, o, lg, end -> AapSession(creds, sink, o, lg, onEnd = end) },
) : AaLifecycle {
    @Volatile private var stopping = false
    @Volatile private var current: AapSession? = null
    private var thread: Thread? = null
    @Volatile var attempts = 0
        private set

    override fun start() {
        val t = Thread({ loop() }, "aa-connector")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** 正确关闭 AA（ByeBye、EOF、close）并结束线程；onDone 从单独线程调用。 */
    fun stop() = stop {}

    override fun stop(onDone: () -> Unit) {
        stopping = true
        current?.requestStop("мост остановлен")
        val t = thread
        Thread({
            try {
                t?.join(10_000)
            } catch (_: InterruptedException) {
            }
            onDone()
        }, "aa-connector-stop").start()
    }

    /** 连续发生的、在会话建立前被 AA 关闭的连接（connect+EOF）。 */
    @Volatile var bareEofs = 0
        private set

    private fun bareEofBackoffMs(n: Int): Long =
        if (n <= 0) retryMs else minOf(retryMs shl minOf(n - 1, 4), BARE_EOF_BACKOFF_MAX_MS).coerceAtLeast(retryMs)

    private fun loop() {
        failures.reset()      // 上次行程的失败不计入本次
        try {
            connectLoop()
        } finally {
            failures.reset()
        }
    }

    private fun connectLoop() {
        var unavailableLogged = false
        // 声音方案取决于车机的 FEATURE_CONFIG（BT 模式）：首次连接前最多等它 2.5 秒
        if (core.usbAudioEnabled && core.expectFeatureConfig) {
            var waited = 0
            while (!core.featureConfigSeen && waited < 2500 && !stopping) {
                Thread.sleep(50)
                waited += 50
            }
            log("BRIDGE FEATURE_CONFIG ${if (core.featureConfigSeen) "получен" else "не пришёл за $waited мс"}, звук по USB: ${core.audioViaUsb}", true)
        }
        while (!stopping) {
            // 手动探测服务器（AaServerCheck，「开发者选项」里的按钮）在我们连接时不应占用 5277 服务器
            AaServerCheck.beforeBridgeConnect()
            if (stopping) break      // 等待期间可能收到停止指令：不能再连接了，handshake 中的会话无法正常中断
            val ended = CountDownLatch(1)
            var endReason = ""
            core.onAaConnecting()
            attempts++
            val s = sessionFactory(core, options(), log) { reason, _ ->
                endReason = reason
                ended.countDown()
            }
            current = s
            core.peer = s
            s.start()
            var establishedNoted = false
            while (!ended.await(500, TimeUnit.MILLISECONDS)) {
                if (stopping) s.requestStop("мост остановлен")
                core.tick()
                // 为主屏幕做的被动诊断：会话已建立——服务器存活，之前的失败不计入
                if (s.established) {
                    if (!establishedNoted) failures.onEstablished()
                    establishedNoted = true
                } else if (s.connected) {
                    failures.onTcpAccepted()
                }
            }
            core.peer = null
            current = null
            when {
                s.established -> if (!establishedNoted) failures.onEstablished()
                !s.connected && endReason.startsWith(REFUSED_REASON) -> failures.onRefused()
                s.connected && endReason.startsWith(AapSession.NO_VERSION_RESPONSE_REASON) -> failures.onNoVersionResponse()
                else -> failures.reset()
            }
            // 仅在车机确实无响应时才显示「启动车机服务器」；ByeBye 之后的短暂拒绝——显示「正在连接」
            if (failures.needsStart()) core.showStatus(AaBridgeCore.TEXT_START_SERVER)
            if (!s.connected) {
                if (!unavailableLogged) {
                    log("BRIDGE сервер ГУ Android Auto недоступен (connection refused), повторяю каждые ${retryMs / 1000.0} с", true)
                    unavailableLogged = true
                }
                AaState.status = "мост: жду сервер ГУ AA"
            } else {
                unavailableLogged = false
                log("BRIDGE AA-сессия завершена (established=${s.established}, video=${s.videoStarted})", true)
                AaState.status = "мост: AA отключился"
                core.onAaEnded(s.established)
                if (s.established) {
                    bareEofs = 0
                } else {
                    // 服务器接受了 TCP 又立即关闭（h224）：它活着，频繁重试只会把 AA 弹回屏幕。等久一点。
                    bareEofs++
                }
            }
            var left = if (s.connected && !s.established) bareEofBackoffMs(bareEofs) else retryMs
            while (left > 0 && !stopping) {
                Thread.sleep(minOf(left, 100))
                left -= 100
            }
        }
    }

    companion object {
        /** 裸 EOF 后连接间隔的上限：3、6、12、24、30 秒。 */
        const val BARE_EOF_BACKOFF_MAX_MS = 30_000L

        /** ECONNREFUSED 时会话结束的原因（其他 connect 故障——超时、EPERM——不视为服务器拒绝）。 */
        private const val REFUSED_REASON = AapSession.UNAVAILABLE_REASON + "ConnectException"
    }
}
