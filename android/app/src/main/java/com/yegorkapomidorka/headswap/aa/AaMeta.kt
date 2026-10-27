package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbField
import com.yegorkapomidorka.headswap.PbReader

/**
 * AA 元数据 (ch9 MEDIA_PLAYBACK, ch10 NAVIGATION) -> CarLife (MEDIA_INFO, MEDIA_PROGRESS_BAR, NAVI_NEXT_TURN_INFO)。
 * 纯 JVM 逻辑：解析、转向动作映射、决定发送什么及何时发送。图片处理（PNG -> JPEG、箭头）由 CarLife 侧完成。
 * 侦察记录：docs/cluster-metadata-recon.md。
 */

/** ch9 0x8001 MediaPlaybackStatus。state: 1 STOPPED, 2 PLAYING, 3 PAUSED。 */
class AaMediaStatus(
    val state: Int?,
    val source: String?,
    val playbackSeconds: Int?,
    val shuffle: Boolean?,
    val repeat: Boolean?,
    val repeatOne: Boolean?,
) {
    val playing: Boolean get() = state == AaMeta.STATE_PLAYING
    override fun toString() = "state=${AaMeta.stateName(state)} source=$source pos=${playbackSeconds}s shuffle=$shuffle repeat=$repeat repeat_one=$repeatOne"
}

/** ch9 0x8003 MediaPlaybackMetadata；art 通常为 PNG 256x256。 */
class AaMediaMetadata(
    val song: String?,
    val artist: String?,
    val album: String?,
    val art: ByteArray?,
    val playlist: String?,
    val durationSeconds: Int?,
    val rating: Int?,
) {
    override fun toString() =
        "song=\"$song\" artist=\"$artist\" album=\"$album\" duration=${durationSeconds}s playlist=$playlist rating=$rating art=${AaMeta.blobInfo(art)}"
}

/** 下一个转向动作：来自 legacy 0x8004 或 0x8006 的第一步。nextTurn 已是 CarLife 编码 (RouteGuideParams)。 */
class AaNavTurn(
    val nextTurn: Int,
    val road: String,
    val maneuver: String,
    val roundaboutExit: Int?,
    val image: ByteArray?,
    /** 来自 legacy 0x8004（否则来自 0x8006）。 */
    val legacy: Boolean = false,
    /** 转向动作键对应的道路：仅 road.name，不含 cue 文本（cue 会随接近而变化）。 */
    val keyRoad: String = road,
) {
    override fun toString() = "maneuver=$maneuver -> nextTurn=$nextTurn road=\"$road\"" +
        (if (roundaboutExit != null) " exit=$roundaboutExit" else "") + (if (image != null) " image=${AaMeta.blobInfo(image)}" else "")
}

/** 距转向动作的距离：legacy 0x8005 或 0x8007 中的 step_distance。 */
class AaNavDistance(
    val meters: Int,
    val timeSeconds: Int?,
    val destinationMeters: Int? = null,
    val currentRoad: String? = null,
    /** 来自 legacy 0x8005（否则来自 0x8007）。 */
    val legacy: Boolean = false,
) {
    override fun toString() = "remain=${meters}m time=${timeSeconds}s" +
        (if (destinationMeters != null) " до цели=${destinationMeters}m" else "") + (if (currentRoad != null) " текущая=\"$currentRoad\"" else "")
}

/** AA 元数据接收器（由 AaBridgeCore 实现）；从 AA 会话线程调用。 */
interface AaMetaSink {
    fun onMediaStatus(s: AaMediaStatus) {}
    fun onMediaMetadata(m: AaMediaMetadata) {}

    /** ch10 INSTRUMENT_CLUSTER_START (true) / STOP (false)。 */
    fun onNavCluster(start: Boolean) {}

    /** ch10 0x8003 NavigationStatus: 0 UNAVAILABLE, 1 ACTIVE, 2 INACTIVE, 3 REROUTING。 */
    fun onNavStatus(status: Int) {}
    fun onNavTurn(t: AaNavTurn) {}
    fun onNavDistance(d: AaNavDistance) {}
}

/** 一条 CarLife MEDIA_INFO 消息；art 为来自 AA 的原始数据 (PNG)，由 CarLife 侧重新编码。 */
class CarlifeMediaInfo(
    val source: String,
    val song: String,
    val artist: String,
    val album: String,
    val art: ByteArray?,
    val durationSeconds: Int,
    val playlistNum: Int = 0,
    val songId: String = "-1",
    val mode: Int = 0,
) {
    override fun toString() = "song=\"$song\" artist=\"$artist\" album=\"$album\" duration=${durationSeconds}s source=$source art=${AaMeta.blobInfo(art)}"
}

/** 一条 CarLife NAVI_NEXT_TURN_INFO 消息（字段 6 和图标由 CarLife 侧按设置选择）。 */
class NaviTurnInfo(
    val action: Int,
    val nextTurn: Int,
    val roadName: String,
    val totalDistance: Int,
    val remainDistance: Int,
    val timeSeconds: Int,
) {
    override fun toString() = "action=$action nextTurn=$nextTurn road=\"$roadName\" total=${totalDistance}m remain=${remainDistance}m time=${timeSeconds}s"
}

/**
 * NAVI_NEXT_TURN_INFO 字段 6 放什么：8.6.7 中是 bytes turnIconData (PNG)，apollo-DuerOS/CarProjection 中是 int32 time。
 * BOTH - 两者都放在 6 号字段：等待另一种 wire-type 的解析器会把异类数据收进 unknown fields。选择在「开发者选项」中。
 */
enum class NaviField6(val icon: Boolean, val time: Boolean) {
    BOTH(true, true), ICON(true, false), TIME(false, true), NONE(false, false);

    companion object {
        fun of(icon: Boolean, time: Boolean) = values().first { it.icon == icon && it.time == time }
    }
}

object AaMeta {
    // ch9 MediaPlaybackStatusMessageId
    const val MEDIA_STATUS = 0x8001
    const val MEDIA_INPUT = 0x8002
    const val MEDIA_METADATA = 0x8003

    // ch10 NavigationStatusMessageId
    const val NAV_START = 0x8001
    const val NAV_STOP = 0x8002
    const val NAV_STATUS = 0x8003
    const val NAV_TURN = 0x8004
    const val NAV_DISTANCE = 0x8005
    const val NAV_STATE = 0x8006
    const val NAV_POSITION = 0x8007

    const val STATE_PLAYING = 2

    const val NAV_UNAVAILABLE = 0
    const val NAV_ACTIVE = 1
    const val NAV_INACTIVE = 2
    const val NAV_REROUTING = 3

    // CarLife nextTurn (RE/core/base/subscribe/RouteGuideParams.java, gTurnIconName)
    const val T_FRONT = 1
    const val T_RIGHT_FRONT = 2
    const val T_RIGHT = 3
    const val T_RIGHT_BACK = 4
    const val T_BACK = 5
    const val T_LEFT_BACK = 6
    const val T_LEFT = 7
    const val T_LEFT_FRONT = 8
    const val T_RING = 9
    const val T_RING_OUT = 10
    const val T_LEFT_SIDE = 11
    const val T_RIGHT_SIDE = 12
    const val T_LEFT_SIDE_MAIN = 13
    const val T_BRANCH_LEFT_STRAIGHT = 14
    const val T_RIGHT_SIDE_MAIN = 15
    const val T_BRANCH_RIGHT_STRAIGHT = 16
    const val T_BRANCH_CENTER = 17
    const val T_LEFT_SIDE_IC = 18
    const val T_RIGHT_SIDE_IC = 19
    const val T_BRANCH_LEFT = 20
    const val T_BRANCH_RIGHT = 21
    const val T_START = 23
    const val T_DEST = 24
    const val T_IN_FERRY = 29

    /** 路线终点，同 Baidu onNaviStateChange(false)：nextTurn 24, roadName「目的地」。 */
    const val END_ROAD = "目的地"

    /**
     * Honda：AA 发来空街道名时（Google Maps 驶出小区场景，legacy 0x8004 road=""）使用的转向动作签名。
     * Baidu 此时发送「无名路」，Yandex 自行替换为「向左转」。文本不超过 16 字节 UTF-8，
     * HondaClusterRoad 对它们不截断直接放行。
     */
    fun noRoadName(nextTurn: Int): String = when (nextTurn) {
        T_FRONT, T_BRANCH_CENTER -> "Прямо"
        T_RIGHT_FRONT, T_RIGHT, T_RIGHT_BACK -> "Направо"
        T_BACK -> "Разворот"
        T_LEFT_FRONT, T_LEFT, T_LEFT_BACK -> "Налево"
        T_RING, T_RING_OUT -> "Круг"
        T_LEFT_SIDE, T_LEFT_SIDE_MAIN, T_BRANCH_LEFT_STRAIGHT, T_LEFT_SIDE_IC, T_BRANCH_LEFT -> "Левее"
        T_RIGHT_SIDE, T_RIGHT_SIDE_MAIN, T_BRANCH_RIGHT_STRAIGHT, T_RIGHT_SIDE_IC, T_BRANCH_RIGHT -> "Правее"
        T_DEST -> "Финиш"
        else -> "Вперёд"
    }

    fun stateName(s: Int?) = when (s) {
        1 -> "STOPPED"
        2 -> "PLAYING"
        3 -> "PAUSED"
        null -> "-"
        else -> "?$s"
    }

    fun navStatusName(s: Int) = when (s) {
        0 -> "UNAVAILABLE"
        1 -> "ACTIVE"
        2 -> "INACTIVE"
        3 -> "REROUTING"
        else -> "?$s"
    }

    /** 日志用的 "<N 字节 PNG>" 格式。 */
    fun blobInfo(b: ByteArray?): String {
        if (b == null) return "нет"
        val kind = when {
            b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "PNG"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "JPEG"
            b.isEmpty() -> "пусто"
            else -> "bin"
        }
        return "<${b.size} Б $kind>"
    }

    // --- 解析 ---
    private fun fields(p: ByteArray): List<PbField>? = PbReader.parse(p)
    private fun int(f: List<PbField>, n: Int): Int? = PbReader.int(f, n)
    private fun bool(f: List<PbField>, n: Int): Boolean? = int(f, n)?.let { it != 0 }
    private fun str(f: List<PbField>, n: Int): String? = PbReader.str(f, n)
    private fun blob(f: List<PbField>, n: Int): ByteArray? = f.firstOrNull { it.number == n && it.wire == 2 }?.data
    private fun sub(f: List<PbField>, n: Int): List<PbField>? = blob(f, n)?.let { PbReader.parse(it) }

    fun parseMediaStatus(p: ByteArray): AaMediaStatus? {
        val f = fields(p) ?: return null
        return AaMediaStatus(int(f, 1), str(f, 2), int(f, 3), bool(f, 4), bool(f, 5), bool(f, 6))
    }

    fun parseMediaMetadata(p: ByteArray): AaMediaMetadata? {
        val f = fields(p) ?: return null
        return AaMediaMetadata(str(f, 1), str(f, 2), str(f, 3), blob(f, 4), str(f, 5), int(f, 6), int(f, 7))
    }

    fun parseNavStatus(p: ByteArray): Int? = fields(p)?.let { int(it, 1) }

    /** Legacy 0x8004 NextTurnEvent{road 1, turn_side 2, event 3, image 4, turn_number 5, turn_angle 6}。 */
    fun parseLegacyTurn(p: ByteArray): AaNavTurn? {
        val f = fields(p) ?: return null
        val event = int(f, 3) ?: 0
        val side = int(f, 2)
        return AaNavTurn(
            turnFromLegacy(event, side), str(f, 1) ?: "", "${legacyName(event)}/${sideName(side)}", int(f, 5), blob(f, 4),
            legacy = true,
        )
    }

    /** Legacy 0x8005 NextTurnDistanceEvent{distance_meters 1, time_to_turn_seconds 2, ...}。 */
    fun parseLegacyDistance(p: ByteArray): AaNavDistance? {
        val f = fields(p) ?: return null
        val m = int(f, 1) ?: return null
        return AaNavDistance(m, int(f, 2), legacy = true)
    }

    /**
     * 0x8006 NavigationState{steps 1, destinations 2}：下一个转向动作 = 第一步。
     * NavigationStep{maneuver 1 {type 1, roundabout_exit_number 2}, road 2 {name 1}, lanes 3, cue 4 {alternate_text 1}}。
     */
    fun parseNavState(p: ByteArray): AaNavTurn? {
        val f = fields(p) ?: return null
        val step = sub(f, 1) ?: return null
        val man = sub(step, 1)
        val type = man?.let { int(it, 1) } ?: 0
        val name = sub(step, 2)?.let { str(it, 1) }?.takeIf { it.isNotBlank() }
        val road = name ?: sub(step, 4)?.let { str(it, 1) }?.takeIf { it.isNotBlank() } ?: ""
        return AaNavTurn(turnFromManeuver(type), road, maneuverName(type), man?.let { int(it, 2) }, null, keyRoad = name ?: "")
    }

    /**
     * 0x8007 NavigationCurrentPosition{step_distance 1 {distance 1 {meters 1}, time_to_step_seconds 2},
     * destination_distances 2 {distance 1 {meters 1}}, current_road 3 {name 1}}。若没有到下一步的距离则为 null。
     */
    fun parseNavPosition(p: ByteArray): AaNavDistance? {
        val f = fields(p) ?: return null
        val sd = sub(f, 1) ?: return null
        val meters = sub(sd, 1)?.let { int(it, 1) } ?: return null
        val time = PbReader.all(sd, 2).firstOrNull { it.wire == 0 }?.num?.toInt()
        val dest = sub(f, 2)?.let { sub(it, 1) }?.let { int(it, 1) }
        val cur = sub(f, 3)?.let { str(it, 1) }
        return AaNavDistance(meters, time, dest, cur)
    }

    /**
     * ch9/ch10 消息 -> 接收器。返回供日志使用的一行摘要（若类型与元数据无关则返回 null）。
     * 解析错误不会使会话崩溃：返回错误文本。
     */
    fun dispatch(chan: Int, type: Int, payload: ByteArray, sink: AaMetaSink?): String? {
        if (sink == null) return null
        return try {
            when (chan) {
                AapMessages.CH_MEDIA_PLAYBACK -> when (type) {
                    MEDIA_STATUS -> parseMediaStatus(payload)?.also { sink.onMediaStatus(it) }?.toString() ?: "MediaPlaybackStatus не разобран"
                    MEDIA_METADATA -> parseMediaMetadata(payload)?.also { sink.onMediaMetadata(it) }?.toString() ?: "MediaPlaybackMetadata не разобран"
                    else -> null
                }
                AapMessages.CH_NAVIGATION -> when (type) {
                    NAV_START -> "cluster START".also { sink.onNavCluster(true) }
                    NAV_STOP -> "cluster STOP".also { sink.onNavCluster(false) }
                    NAV_STATUS -> parseNavStatus(payload)?.let { sink.onNavStatus(it); "status=${navStatusName(it)}" } ?: "NavigationStatus не разобран"
                    NAV_TURN -> parseLegacyTurn(payload)?.also { sink.onNavTurn(it) }?.let { "legacy $it" } ?: "NextTurnEvent не разобран"
                    NAV_DISTANCE -> parseLegacyDistance(payload)?.also { sink.onNavDistance(it) }?.let { "legacy $it" } ?: "NextTurnDistanceEvent не разобран"
                    NAV_STATE -> parseNavState(payload)?.also { sink.onNavTurn(it) }?.let { "state $it" } ?: "NavigationState без шагов"
                    NAV_POSITION -> parseNavPosition(payload)?.also { sink.onNavDistance(it) }?.let { "position $it" } ?: "NavigationCurrentPosition без step_distance"
                    else -> null
                }
                else -> null
            }
        } catch (e: Throwable) {
            "сбой разбора: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // --- AA 转向动作 -> CarLife nextTurn 映射 ---
    /** NavigationManeuver.NavigationType (0x8006) -> nextTurn。未知值 -> 1（直行），同 Baidu。 */
    fun turnFromManeuver(type: Int): Int = when (type) {
        1 -> T_START                                    // DEPART
        2 -> T_FRONT                                    // NAME_CHANGE
        3 -> T_BRANCH_LEFT_STRAIGHT                     // KEEP_LEFT
        4 -> T_BRANCH_RIGHT_STRAIGHT                    // KEEP_RIGHT
        5 -> T_LEFT_FRONT                               // TURN_SLIGHT_LEFT
        6 -> T_RIGHT_FRONT                              // TURN_SLIGHT_RIGHT
        7 -> T_LEFT                                     // TURN_NORMAL_LEFT
        8 -> T_RIGHT                                    // TURN_NORMAL_RIGHT
        9 -> T_LEFT_BACK                                // TURN_SHARP_LEFT
        10 -> T_RIGHT_BACK                              // TURN_SHARP_RIGHT
        11, 12, 19, 20 -> T_BACK                        // U_TURN_*, ON_RAMP_U_TURN_*
        13, 15, 17 -> T_LEFT_SIDE_IC                    // ON_RAMP_*_LEFT
        14, 16, 18 -> T_RIGHT_SIDE_IC                   // ON_RAMP_*_RIGHT
        21, 23 -> T_LEFT_SIDE                           // OFF_RAMP_*_LEFT
        22, 24 -> T_RIGHT_SIDE                          // OFF_RAMP_*_RIGHT
        25 -> T_BRANCH_LEFT                             // FORK_LEFT
        26 -> T_BRANCH_RIGHT                            // FORK_RIGHT
        27 -> T_LEFT_SIDE_MAIN                          // MERGE_LEFT
        28 -> T_RIGHT_SIDE_MAIN                         // MERGE_RIGHT
        29 -> T_FRONT                                   // MERGE_SIDE_UNSPECIFIED
        30, 32, 33, 34, 35 -> T_RING                    // ROUNDABOUT_ENTER(_AND_EXIT*)
        31 -> T_RING_OUT                                // ROUNDABOUT_EXIT
        36 -> T_FRONT                                   // STRAIGHT
        37, 38 -> T_IN_FERRY                            // FERRY_*
        39, 40, 41, 42 -> T_DEST                        // DESTINATION*
        else -> T_FRONT
    }

    /** Legacy NextTurnEnum (0x8004) x TurnSide (1 LEFT, 2 RIGHT, 3 UNSPECIFIED) -> nextTurn。 */
    fun turnFromLegacy(event: Int, side: Int?): Int {
        val left = side == 1
        val right = side == 2
        fun lr(l: Int, r: Int, none: Int = T_FRONT) = if (left) l else if (right) r else none
        return when (event) {
            1 -> T_START                                        // DEPART
            2 -> T_FRONT                                        // NAME_CHANGE
            3 -> lr(T_LEFT_FRONT, T_RIGHT_FRONT)                // SLIGHT_TURN
            4 -> lr(T_LEFT, T_RIGHT)                            // TURN
            5 -> lr(T_LEFT_BACK, T_RIGHT_BACK)                  // SHARP_TURN
            6 -> T_BACK                                         // U_TURN
            7 -> lr(T_LEFT_SIDE_IC, T_RIGHT_SIDE_IC)            // ON_RAMP
            8 -> lr(T_LEFT_SIDE, T_RIGHT_SIDE)                  // OFF_RAMP
            9 -> lr(T_BRANCH_LEFT, T_BRANCH_RIGHT, T_BRANCH_CENTER) // FORK
            10 -> lr(T_LEFT_SIDE_MAIN, T_RIGHT_SIDE_MAIN)       // MERGE
            11, 13 -> T_RING                                    // ROUNDABOUT_ENTER(_AND_EXIT)
            12 -> T_RING_OUT                                    // ROUNDABOUT_EXIT
            14 -> T_FRONT                                       // STRAIGHT
            16, 17 -> T_IN_FERRY                                // FERRY_*
            19 -> T_DEST                                        // DESTINATION
            else -> T_FRONT
        }
    }

    private val MANEUVER_NAMES = arrayOf(
        "UNKNOWN", "DEPART", "NAME_CHANGE", "KEEP_LEFT", "KEEP_RIGHT", "TURN_SLIGHT_LEFT", "TURN_SLIGHT_RIGHT",
        "TURN_NORMAL_LEFT", "TURN_NORMAL_RIGHT", "TURN_SHARP_LEFT", "TURN_SHARP_RIGHT", "U_TURN_LEFT", "U_TURN_RIGHT",
        "ON_RAMP_SLIGHT_LEFT", "ON_RAMP_SLIGHT_RIGHT", "ON_RAMP_NORMAL_LEFT", "ON_RAMP_NORMAL_RIGHT", "ON_RAMP_SHARP_LEFT",
        "ON_RAMP_SHARP_RIGHT", "ON_RAMP_U_TURN_LEFT", "ON_RAMP_U_TURN_RIGHT", "OFF_RAMP_SLIGHT_LEFT", "OFF_RAMP_SLIGHT_RIGHT",
        "OFF_RAMP_NORMAL_LEFT", "OFF_RAMP_NORMAL_RIGHT", "FORK_LEFT", "FORK_RIGHT", "MERGE_LEFT", "MERGE_RIGHT",
        "MERGE_SIDE_UNSPECIFIED", "ROUNDABOUT_ENTER", "ROUNDABOUT_EXIT", "ROUNDABOUT_ENTER_AND_EXIT_CW",
        "ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE", "ROUNDABOUT_ENTER_AND_EXIT_CCW", "ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE",
        "STRAIGHT", "FERRY_BOAT", "FERRY_TRAIN", "DESTINATION", "DESTINATION_STRAIGHT", "DESTINATION_LEFT", "DESTINATION_RIGHT",
    )

    fun maneuverName(t: Int) = MANEUVER_NAMES.getOrNull(t) ?: "?$t"

    private fun legacyName(e: Int) = when (e) {
        0 -> "UNKNOWN"; 1 -> "DEPART"; 2 -> "NAME_CHANGE"; 3 -> "SLIGHT_TURN"; 4 -> "TURN"; 5 -> "SHARP_TURN"
        6 -> "U_TURN"; 7 -> "ON_RAMP"; 8 -> "OFF_RAMP"; 9 -> "FORK"; 10 -> "MERGE"; 11 -> "ROUNDABOUT_ENTER"
        12 -> "ROUNDABOUT_EXIT"; 13 -> "ROUNDABOUT_ENTER_AND_EXIT"; 14 -> "STRAIGHT"; 16 -> "FERRY_BOAT"
        17 -> "FERRY_TRAIN"; 19 -> "DESTINATION"; else -> "?$e"
    }

    private fun sideName(s: Int?) = when (s) {
        1 -> "LEFT"; 2 -> "RIGHT"; 3 -> "UNSPECIFIED"; null -> "-"; else -> "?$s"
    }

    /**
     * 如何为 nextTurn 绘制箭头：类型和出口角度（0 朝上，+ 朝右）。
     * 实际绘制由 Android 辅助类 TurnIcons 完成；这里只有供 JVM 测试校验的表格。
     */
    enum class GlyphKind { ARROW, U_TURN, RING, RING_OUT, DEST, START }

    class Glyph(val kind: GlyphKind, val angle: Int)

    fun glyph(nextTurn: Int): Glyph = when (nextTurn) {
        T_FRONT, T_BRANCH_CENTER, 22, T_IN_FERRY, 30 -> Glyph(GlyphKind.ARROW, 0)
        T_RIGHT_FRONT -> Glyph(GlyphKind.ARROW, 45)
        T_RIGHT -> Glyph(GlyphKind.ARROW, 90)
        T_RIGHT_BACK -> Glyph(GlyphKind.ARROW, 135)
        T_LEFT_FRONT -> Glyph(GlyphKind.ARROW, -45)
        T_LEFT -> Glyph(GlyphKind.ARROW, -90)
        T_LEFT_BACK -> Glyph(GlyphKind.ARROW, -135)
        T_BACK -> Glyph(GlyphKind.U_TURN, -180)
        T_RING -> Glyph(GlyphKind.RING, 45)
        T_RING_OUT -> Glyph(GlyphKind.RING_OUT, 45)
        T_LEFT_SIDE, T_LEFT_SIDE_MAIN, T_BRANCH_LEFT_STRAIGHT -> Glyph(GlyphKind.ARROW, -25)
        T_RIGHT_SIDE, T_RIGHT_SIDE_MAIN, T_BRANCH_RIGHT_STRAIGHT -> Glyph(GlyphKind.ARROW, 25)
        T_LEFT_SIDE_IC, T_BRANCH_LEFT -> Glyph(GlyphKind.ARROW, -40)
        T_RIGHT_SIDE_IC, T_BRANCH_RIGHT -> Glyph(GlyphKind.ARROW, 40)
        T_START -> Glyph(GlyphKind.START, 0)
        T_DEST -> Glyph(GlyphKind.DEST, 0)
        else -> Glyph(GlyphKind.ARROW, 0)
    }
}

/**
 * 根据 AA 元数据决定向 CarLife 发送什么、何时发送。无锁：在 AaBridgeCore 的监视器下被调用。
 * ready() - CarLife START 已发生且引用有效；navHold(on) - 是否为路线保持 MODULE_STATUS NAVI
 * （核心会将其与为 TTS 保持 NAVI 的需求合并处理）。
 */
class MetaRelay(
    private val log: (String, Boolean) -> Unit,
    private val clockMs: () -> Long,
    private val link: () -> CarlifeLink,
    private val ready: () -> Boolean,
    private val navHold: (Boolean) -> Unit,
    private val musicState: (Boolean) -> Unit,
    /** 在首次 SHOW 及静默后的重复 SHOW 之前：若 head unit 要求，重新触发 MODULE_STATUS NAVI 0->1 (Honda)。 */
    private val navRearm: () -> Unit = {},
    /** 用转向动作签名替换空街道名 (AaMeta.noRoadName) - Honda。 */
    private val emptyRoadText: () -> Boolean = { false },
) {
    // head unit 订阅 (CARLIFE_DATA_SUBSCRIBE_START/STOP)：音乐默认开启，导航仅在 START moduleID=0 时开启
    var musicSubscribed = true
        private set
    var navSubscribed = false
        private set

    // 音乐
    var playing = false
        private set
    private var mediaSource = ""
    private var info: CarlifeMediaInfo? = null
    private var infoKey: String? = null
    private var infoPending = false            // MEDIA_INFO 挂起，直到 AA 开始播放
    private var trackSeq = 0
    private var posMs: Int? = null
    private var lastProgressMs: Int? = null
    private var lastProgressAt = NEVER

    // 导航
    var routeActive = false
        private set
    private var turn: AaNavTurn? = null
    private var turnKey: String? = null
    private var remain: AaNavDistance? = null
    private var total = 0
    private var shown = false
    private var turnDirty = false
    private var lastTurnAt = NEVER
    private var lastShowAt = NEVER
    private var lastSent: String? = null
    private var routeEndedAt = NEVER
    private var navNewSeen = false             // 本次 AA 会话中出现过 0x8006/0x8007
    private var legacySeen = false
    private var legacyIgnoredLogged = false
    private var awaitSubAt = NEVER             // Honda：我们发 NAVI 0->1 后，箭头积攒到 head unit 的 SUBSCRIBE_START 为止
    private var heldTurns = 0

    /** 新 CarLife 会话：订阅恢复默认，head unit 上尚未显示任何内容。 */
    fun onAttach() {
        musicSubscribed = true
        navSubscribed = false
        shown = false
        lastSent = null
        awaitSubAt = NEVER
        lastProgressMs = null
        lastProgressAt = NEVER
    }

    /** CarLife START（冷启动）：重新发送已知内容，head unit 可能还不知道曲目和转向动作。 */
    fun onCarlifeStart() {
        shown = false
        lastSent = null
        awaitSubAt = NEVER
        replayMusic("CarLife START")
        if (routeActive) {
            navHold(true)
            emitTurn(force = true, why = "CarLife START")
        }
    }

    /** head unit 的 SUBSCRIBE_START/STOP：moduleID 0 导航，2 音乐（订阅编号，非 MODULE_STATUS）。 */
    fun onSubscribe(moduleId: Int, on: Boolean) {
        when (moduleId) {
            SUB_NAVI -> {
                navSubscribed = on
                log("META подписка навигации: ${if (on) "START, шлю NAVI_NEXT_TURN_INFO" else "STOP"}", true)
                if (on && awaitSubAt != NEVER) {
                    log("META ГУ подписалась через ${clockMs() - awaitSubAt} мс после NAVI=1, придержано стрелок: $heldTurns", true)
                    awaitSubAt = NEVER
                }
                // CarLife START 之前不发送：START 时全部重发 (onCarlifeStart)
                if (on && routeActive && ready()) {
                    // head unit (Honda) 每次 NAVI=1 后重新订阅，且只从订阅后的 SHOW 中取箭头。
                    // Honda Breeze 2026-10-17 16:15/16:18：SHOW 在 SUBSCRIBE_START 之前发出，之后只有 UPDATE -
                    // HUD 上只有距离没有箭头，仪表盘直到路线结束都是空的。
                    shown = false
                    lastSent = null
                    navHold(true)
                    emitTurn(force = true, why = "SUBSCRIBE_START", rearm = false)
                }
            }
            SUB_MUSIC -> {
                musicSubscribed = on
                log("META подписка музыки: ${if (on) "START" else "STOP, MEDIA_INFO не шлю"}", true)
                if (on) replayMusic("SUBSCRIBE_START")
            }
            else -> log("META подписка moduleID=$moduleId ${if (on) "START" else "STOP"}: не поддерживаю", false)
        }
    }

    // --- 音乐 ---
    fun onMediaStatus(s: AaMediaStatus) {
        s.source?.takeIf { it.isNotBlank() }?.let { mediaSource = it }
        s.playbackSeconds?.let { posMs = it * PROGRESS_UNIT }
        if (s.state != null && s.playing != playing) {
            playing = s.playing
            log("META музыка: ${AaMeta.stateName(s.state)}", true)
            musicState(playing)
            if (playing && infoPending) {
                replayMusic("AA заиграл")
                return
            }
        }
        sendProgress(force = false)
    }

    fun onMediaMetadata(m: AaMediaMetadata) {
        val key = listOf(m.song, m.artist, m.album, m.durationSeconds, m.art?.contentHashCode(), m.art?.size).joinToString("|")
        if (key == infoKey) return
        // 同一曲目换了新封面 - 来源不变 (head unit 按 source 区分曲目)，新曲目 - 新键值
        val sameTrack = info?.let { it.song == (m.song ?: "") && it.artist == (m.artist ?: "") && it.album == (m.album ?: "") } == true
        if (!sameTrack) trackSeq++
        infoKey = key
        info = CarlifeMediaInfo(
            source = "${mediaSource.ifEmpty { "aa" }}#$trackSeq",
            song = m.song ?: "",
            artist = m.artist ?: "",
            album = m.album ?: "",
            art = m.art,
            durationSeconds = m.durationSeconds ?: 0,
        )
        if (!sameTrack) lastProgressMs = null
        sendInfo(if (sameTrack) "обложка/поля трека обновились" else "новый трек")
    }

    private fun replayMusic(why: String) {
        lastProgressMs = null
        lastProgressAt = NEVER
        if (info != null) sendInfo(why)
        else sendProgress(force = true)
    }

    private fun sendInfo(why: String) {
        val i = info ?: return
        // 暂停的曲目不显示：head unit (Desay G6S, Mazda) 认为 CarLife 音乐在播放而关闭收音机
        if (!playing) {
            infoPending = true
            log("META MEDIA_INFO отложен ($why): AA не играет; $i", false)
            return
        }
        infoPending = false
        if (!ready() || !musicSubscribed) {
            log("META MEDIA_INFO отложен ($why): ${if (!ready()) "CarLife не START" else "музыка не подписана"}; $i", false)
            return
        }
        if (link().sendMediaInfo(i)) log("META -> MEDIA_INFO ($why): $i", true)
        sendProgress(force = true)
    }

    private fun sendProgress(force: Boolean) {
        val p = posMs ?: return
        if (!playing || !ready() || !musicSubscribed || info == null) return
        val now = clockMs()
        val last = lastProgressMs
        if (!force && last != null) {
            if (p == last) return
            // 同 MusicInfoSender：前进每秒至多一次，后退（回退）立即发送
            if (p > last && now - lastProgressAt < PROGRESS_MIN_MS) return
        }
        if (link().sendMediaProgress(p)) {
            lastProgressMs = p
            lastProgressAt = now
            log("META -> MEDIA_PROGRESS_BAR progressBar=$p (${p / PROGRESS_UNIT} с)", false)
        }
    }

    // --- 导航 ---
    fun onNavCluster(start: Boolean) {
        log("META навигация: instrument cluster ${if (start) "START" else "STOP"}", true)
        if (!start) endRoute("cluster STOP")
    }

    /**
     * Honda：核心刚把 MODULE_STATUS NAVI 置为 1 - head unit 会回应 SUBSCRIBE_START，且只从其后的 SHOW 中取箭头。
     * 订阅之前（或 SUB_WAIT_MS 超时前）不发送箭头，先积攒：turn/remain 保存最新值，之后一并发出。
     */
    fun awaitSubscribe() {
        awaitSubAt = clockMs()
        heldTurns = 0
    }

    fun onNavStatus(status: Int) {
        when (status) {
            AaMeta.NAV_ACTIVE, AaMeta.NAV_REROUTING -> startRoute("status ${AaMeta.navStatusName(status)}")
            else -> {
                if (routeActive) endRoute("status ${AaMeta.navStatusName(status)}")
                else log("META навигация: status ${AaMeta.navStatusName(status)}, маршрута нет", false)
            }
        }
    }

    /**
     * 是否接受转向动作/距离事件。每个 AA 会话只有一个来源：一旦收到新的 0x8006/0x8007，
     * legacy 0x8004/0x8005 即被忽略（否则转向动作键会在两个名称之间跳动）。没有路线时，
     * 事件会隐式启动路线（手机可能不发送状态），路线结束后的 TAIL_MS 尾巴除外。
     */
    private fun acceptNav(legacy: Boolean, what: String): Boolean {
        if (!legacy && !navNewSeen) {
            navNewSeen = true
            if (legacySeen) log("META навигация: пришли новые 0x8006/0x8007, legacy 0x8004/0x8005 дальше игнорирую", true)
        }
        if (legacy) {
            if (navNewSeen) {
                if (!legacyIgnoredLogged) log("META навигация: legacy $what игнорирую, источник - 0x8006/0x8007", false)
                legacyIgnoredLogged = true
                return false
            }
            legacySeen = true
        }
        if (!routeActive) {
            if (clockMs() - routeEndedAt < TAIL_MS) {
                log("META навигация: $what через ${clockMs() - routeEndedAt} мс после конца маршрута - хвост, игнор", false)
                return false
            }
            startRoute("пришёл $what")
        }
        return true
    }

    fun onNavTurn(t: AaNavTurn) {
        if (!acceptNav(t.legacy, "манёвр")) return
        val key = "${t.nextTurn}|${t.keyRoad}|${t.roundaboutExit}"
        val changed = key != turnKey
        turn = t
        if (changed) {
            turnKey = key
            remain = null              // 旧距离属于上一个转向动作
            total = 0
            turnDirty = true           // 新转向动作的第一条距离不加节流直接发送
            log("META манёвр: $t", true)
        }
        emitTurn(force = changed, why = if (changed) "новый манёвр" else "манёвр повторён")
    }

    fun onNavDistance(d: AaNavDistance) {
        if (!acceptNav(d.legacy, "дистанция")) return
        // 连续两个相同的转弯（相同 nextTurn|road|exit）：键不变，新转向动作只能通过距离向上跳变看出
        // (h137: 10 -> 170 米)。否则 total 会保留上一转弯的值。
        val prev = remain
        if (prev != null && d.meters - prev.meters >= NEW_TURN_JUMP_M) {
            total = 0
            turnDirty = true
            log("META манёвр: тот же ключ, дистанция ${prev.meters} -> ${d.meters} м - новый поворот", true)
        }
        remain = d
        // totalDistance - 转向动作切换时的距离 (Baidu 在换路时固定此值)
        if (total == 0 || d.meters > total) total = d.meters
        emitTurn(force = false, why = "дистанция")
    }

    private fun startRoute(why: String) {
        if (routeActive) return
        routeActive = true
        shown = false
        lastSent = null
        log("META маршрут начат ($why)", true)
        // 无订阅时 NAVI=1：VW BT 1538 (h244) 仅在 NAVI=1 之后发送 SUBSCRIBE_START
        if (ready()) navHold(true)
    }

    private fun endRoute(why: String) {
        if (!routeActive) return
        routeActive = false
        routeEndedAt = clockMs()
        log("META маршрут закончен ($why)", true)
        if (shown && ready() && navSubscribed) {
            val end = NaviTurnInfo(ACTION_END, AaMeta.T_DEST, AaMeta.END_ROAD, 0, 0, 0)
            if (link().sendNaviTurn(end)) log("META -> NAVI_NEXT_TURN_INFO $end", true)
        }
        shown = false
        turnDirty = false
        turn = null
        turnKey = null
        remain = null
        total = 0
        lastSent = null
        navHold(false)
    }

    /** rearm=false：SHOW 是对 SUBSCRIBE_START 的回应 - 订阅本身就是 head unit 对 0->1 的应答，否则重新触发会死循环。 */
    private fun emitTurn(force: Boolean, why: String, rearm: Boolean = true) {
        val t = turn ?: return
        val d = remain ?: return                    // 没有距离不发送：head unit 上会显示「0 米」
        if (!ready() || !navSubscribed) return
        val now = clockMs()
        val idle = now - lastTurnAt
        if (!force && !turnDirty && idle < TURN_MIN_MS) return
        // h137 (Honda Civic, 空 HU_INFO)：约 40 秒无数据包后仪表盘熄灭提示，UPDATE 也无法唤醒
        val reshow = shown && idle >= NAVI_RESHOW_MS
        // h243：车辆服务消息或仪表模式切换会熄灭提示，head unit 不会通知 -
        // 周期性 SHOW 会在 NAVI_SHOW_EVERY_MS 内将其恢复
        val periodic = shown && now - lastShowAt >= NAVI_SHOW_EVERY_MS
        val road = if (t.road.isBlank() && emptyRoadText()) AaMeta.noRoadName(t.nextTurn) else t.road
        val info = NaviTurnInfo(
            // h137：熄灭的提示只能通过 SHOW 恢复，因此每个转向动作的第一个包是 SHOW
            if (shown && !reshow && !turnDirty && !periodic) ACTION_UPDATE else ACTION_SHOW, t.nextTurn, road, maxOf(total, d.meters), d.meters, d.timeSeconds ?: 0
        )
        val sig = "${info.action}|${info.nextTurn}|${info.roadName}|${info.totalDistance}|${info.remainDistance}|${info.timeSeconds}"
        // 停车时 AA 发送相同内容：每 NAVI_KEEPALIVE_MS 重复一次，防止 head unit 认为路线已结束
        val keepalive = sig == lastSent
        if (keepalive && idle < NAVI_KEEPALIVE_MS) return
        if (awaitSubAt != NEVER && now - awaitSubAt >= SUB_WAIT_MS) {
            log("META SUBSCRIBE_START от ГУ не пришёл за ${now - awaitSubAt} мс, шлю без него (придержано $heldTurns)", true)
            awaitSubAt = NEVER
        }
        // Honda：仅在首次 SHOW 和静默后的重复 SHOW 之前重新触发 NAVI 0->1（不在转向动作 SHOW 和周期性 SHOW 时）
        if (awaitSubAt == NEVER && info.action == ACTION_SHOW && rearm && (!shown || reshow)) navRearm()   // 可能会进入等待状态
        if (awaitSubAt != NEVER) {
            if (heldTurns++ == 0) log("META стрелку придерживаю: жду SUBSCRIBE_START от ГУ ($why)", true)
            return
        }
        if (link().sendNaviTurn(info)) {
            val loud = !shown || turnDirty || reshow
            val tag = if (reshow) "$why, повторный показ после ${idle / 1000} с"
                else if (keepalive) "$why, keepalive"
                else if (periodic && !turnDirty) "$why, периодический SHOW"
                else why
            if (info.action == ACTION_SHOW) lastShowAt = now
            shown = true
            turnDirty = false
            lastTurnAt = now
            lastSent = sig
            log("META -> NAVI_NEXT_TURN_INFO ($tag) $info", loud)
        }
    }

    /** AA 会话已结束：路线和播放器随之消失。 */
    fun onAaEnded() {
        endRoute("AA-сессия закончилась")
        navNewSeen = false
        legacySeen = false
        legacyIgnoredLogged = false
        routeEndedAt = NEVER
        if (playing) {
            playing = false
            musicState(false)
        }
        posMs = null
        // 曲目随 AA 一同消亡：重启后不再显示过期内容
        info = null
        infoKey = null
        infoPending = false
    }

    companion object {
        const val SUB_NAVI = 0
        const val SUB_MUSIC = 2

        const val ACTION_SHOW = 1
        const val ACTION_UPDATE = 2
        const val ACTION_END = 3

        /**
         * progressBar 以毫秒为单位：MusicInfoSender（普通通道，非 isSecondVehi）以毫秒发送 curTime，并按差值 < 1000 节流。
         * MEDIA_INFO 中的 duration 以秒为单位。若 head unit 显示的进度大 1000 倍 - 改为 1。
         */
        @Volatile @JvmStatic var PROGRESS_UNIT = 1000

        const val PROGRESS_MIN_MS = 1000L
        const val TURN_MIN_MS = 1000L
        /** 路线激活期间重复未变化的转向动作（停车时 AA 发送相同数据，去重会将其静音）。 */
        const val NAVI_KEEPALIVE_MS = 5000L
        /** 静默超过此时长：下一个包重新用 SHOW，head unit 可能已移除提示。 */
        const val NAVI_RESHOW_MS = 10_000L
        /** Honda：NAVI=1 后等待 SUBSCRIBE_START 的时长（日志中为 14-670 毫秒）。在下一次 AA 事件（约 1 秒）时检查。 */
        const val SUB_WAIT_MS = 1500L
        /** 相同转向动作键下距离增大：说明已是下一个相同转弯（AA 步长为 10 米）。 */
        const val NEW_TURN_JUMP_M = 40
        /** 路线激活期间 SHOW 不低于此频率：仪表盘可能自行熄灭了提示 (h243, h137)。 */
        const val NAVI_SHOW_EVERY_MS = 10_000L

        /** 路线结束后迟到的转向动作/距离在此窗口内不会重新启动路线。 */
        const val TAIL_MS = 5000L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
