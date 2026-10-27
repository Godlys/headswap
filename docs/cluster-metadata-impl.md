# AA → CarLife 元数据：实现与车内验证

2026-10-11。前期的侦察：[cluster-metadata-recon.md](cluster-metadata-recon.md)。未提交，未进入发布版。

## 已完成的工作
- 解析 ch9/ch10 — `aa/AaMeta.kt`。ch9：MediaPlaybackStatus 0x8001、Metadata 0x8003。ch10：START/STOP 集群、NavigationStatus 0x8003、legacy 0x8004/0x8005、新增 0x8006（第一步： maneuvers、道路，道路为空时是 cue 文本）和 0x8007（step_distance、到下一步的时间、到目的地的距离）。通过 `AaMetaSink` 从 `AapSession.dispatch` 调用，`AaMetaSink` 实现 `AaBridgeCore`。
- 发送什么消息的决定由 `MetaRelay` 做出（同一文件，纯 JVM 逻辑）。在 `AaBridgeCore` 的 monitor 下运行。
  - 音乐。MEDIA_INFO 在切歌或换封面时发送，相同数据不重复发送。9 个 required 字段总是写入。`source` = `<media_source>#<曲目号>`，同一首歌换新封面时不变。MEDIA_PROGRESS_BAR：前进最多每秒一次，后退（拖动）立即发送。**单位：progressBar 用毫秒，duration 用秒**，同普通通道的 `MusicInfoSender`：Vezel 不是 `isSecondVehi`。如果车机上的进度大 1000 倍，把 `MetaRelay.PROGRESS_UNIT = 1`。
  - 导航。只在 SUBSCRIBE_START moduleID=0 之后发送消息。ACTIVE 或第一个 maneuver → MODULE_STATUS NAVI=1。NAVI_NEXT_TURN_INFO：首次显示用 action=1，action=2 的更新每秒最多一次，maneuver 变化不限流。没有距离就不发消息。totalDistance 在 maneuver 的第一个距离时固定下来。INACTIVE、UNAVAILABLE 或集群 STOP → action=3、nextTurn=24、roadName “目的地”，然后 NAVI=0。Action=3 只在之前显示过时才发：连接时的 INACTIVE 不会产生假的“已到达”。
  - 一个 AA 会话只取一个事件源。一旦收到 0x8006/0x8007，legacy 0x8004/0x8005 被忽略，日志里记一行。maneuver 的键 = nextTurn + road.name + 出口编号。cue 文本只在屏幕上显示，不进入键。
  - 没有路线时的 maneuver 或距离会隐式启动路线：手机可能不发状态。例外 — 路线结束后 5 秒的尾巴：这类事件被忽略，NAVI 不再拉起来。
  - MODULE_STATUS NAVI 由路线和 TTS 共用。NAVI=0 只在路线和 TTS 都不持有时发送。
  - MODULE_STATUS MUSIC。声音走 USB 时逻辑不变：MEDIA INIT/PAUSE 和 `isMediaInited`。声音不走 USB（BT 或关闭）时，MUSIC 1/0 按 ch9 的播放器状态设置。对 MODULE_CONTROL 的回复 — `bridge.musicActive`。
  - 冷启动 CarLife START（以及 USB 重连后）时，已知的曲目、进度和 maneuver 会重新发送。
- 向 CarLife 发送 — `CarLifeSession`（`sendMediaInfo` / `sendMediaProgress` / `sendNaviTurn`），通过独立线程 `cl-meta`，保序。封面 PNG 由 `MetaImages.albumJpeg` 转码：JPEG ≤256×256 q90…10，然后 160×160，上限 30720 字节；结果按字节缓存。箭头由 `MetaImages.turnIcon(nextTurn)` 绘制：PNG 128×128，白色带深色描边，按 nextTurn 缓存。构建器 — `Msgs.mediaInfo/mediaProgress/naviNextTurn`（`Protocol.kt`）。
- NAVI_NEXT_TURN_INFO 的字段 6 在“开发者选项 → 车机元数据”中切换：
  - “字段 6：图标” — bytes turnIconData，同 8.6.7；
  - “字段 6：time” — int32 time，到 maneuver 的秒数，同 apollo-DuerOS 和 CarProjection；
  - 默认两者都开（BOTH）：编号 6 下同时发 varint time 和 bytes 图标。期待另一种 wire-type 的解析器会把多余内容收进 unknown fields。车机库 apollo 通过 `MergePartialFromCodedStream` 读取。
  - 同处有“音乐”“导航”和“进度单位秒”开关：MEDIA_PROGRESS_BAR 用秒代替毫秒。所有设置都在 USB 连接时读取。当前值写入发送日志的头部。
- 日志签名已修正。`AapMessages.messageName/describe` 按通道选择名称和 schema：ch9 MEDIA_PLAYBACK_*、ch10 INSTRUMENT_CLUSTER_* / NAVIGATION_*，嵌套 schema NavigationState/CurrentPosition。媒体通道同之前一样是 MEDIA_*。二进制字段输出为 `<N B PNG>`，不再 400 KB 地 dump。
- 日志里能看到：
  - `AA RX MEDIA_PLAYBACK(9)/NAVIGATION(10) <名称> | {字段}` — 全部 ch9/ch10。播放器状态、NavigationState、位置和距离（约每秒一次）只写文件，其余也进 UI。
  - `AA META …` — 解析后的结果。
  - `META …` — relay 的决定：延迟、订阅、路线开始/结束、maneuver、发出什么。
  - `TX CMD 0x00010035 MEDIA_INFO {...albumArt=<N B JPEG>...}`、`MEDIA_PROGRESS_BAR`、`NAVI_NEXT_TURN_INFO {... time|turnIconData=…}` — 进度和 action=2 只写文件。
  - `BRIDGE CarLife MODULE_STATUS NAVI/MUSIC=…`。

## 测试
`AaMetaTest`，19 个测试（包括 legacy + 新事件的混合流、cue 文本变化而 maneuver 不变、INACTIVE 之后的尾巴）：
- ch9 和 ch10 的解析（两种事件变体、cue fallback、损坏的 payload）；
- maneuver 映射（新式和 legacy）；
- 按通道的日志名称和 schema；
- relay：延迟到 START、毫秒和限流、拖动、source、音乐订阅、导航订阅、action 1/2/3 流带 total/remain/time、假 INACTIVE、legacy、TTS 与路线共用的 NAVI、新 CarLife 会话后的重发、无 USB 声音时的 MUSIC 状态；
- MEDIA_INFO（全部 required 字段）和 NAVI_NEXT_TURN_INFO（字段 6 的 wire-type 四种模式）的构建器。

构建：`gradlew testDebugUnitTest assembleDebug` 全绿，251 个 JVM 测试，0 失败。`MetaImages`（Bitmap/Canvas）未被 JVM 测试覆盖：箭头外观和 JPEG 尺寸只在手机上验证。如果那里抛异常，会被捕获并写入日志行 `元数据: MEDIA_INFO|NAVI_NEXT_TURN_INFO`（带 trace），消息不发送，会话继续。

## 车内检查清单（Vezel）
出发前：“开发者选项” → “车机元数据”，全部开启（默认）。行程结束后保存日志（分享日志）。

音乐：
1. [ ] 通过 AA 在 YouTube Music 或 Spotify 开启曲目。车机 widget 或 CarLife 音乐页面可见歌名、歌手、专辑。日志中有 `TX CMD … MEDIA_INFO`。
2. [ ] 封面到车机。日志中 `元数据: 封面 <N B PNG> -> <M B JPEG>`，M < 30720。
3. [ ] 进度在走并与手机一致。如果大 1000 倍（或停在结尾），开启“进度单位秒”，重插 USB 再查一次。
4. [ ] 下一首：歌名和封面变化，进度从零开始。拖动：进度立即跳变。
5. [ ] 仪表盘：是否显示曲目（以及是否带进度）。
6. [ ] 暂停和播放：车机不丢声音，同之前一样（MUSIC 仍按 MEDIA INIT/PAUSE）。

导航（Google Maps，2–3 个转弯的路线）：
7. [ ] 日志中有 `SUBSCRIBE_START moduleID=[0]` 和 `META 导航订阅: START`。
8. [ ] 手机发什么事件：日志 ch10 中是 `NAVIGATION_STATE`/`NAVIGATION_CURRENT_POSITION`（新式）还是 `NAVIGATION_TURN_EVENT`/`NAVIGATION_DISTANCE_EVENT`（legacy）。记录下来。**分叉点：**
   - 路线激活时 ch10 上只有 `NAVIGATION_STATUS`/`INSTRUMENT_CLUSTER_*` 且没有 0x8004–0x8007 → 手机不发数据，切换字段 6 也没用。记录下来，跳过第 9–18 步；
   - ch10 有数据，但没有 `TX … NAVI_NEXT_TURN_INFO` → 看 `META` 行（订阅、“尾巴”、“legacy 忽略”、“无距离”）。
9. [ ] 路线开始：`MODULE_STATUS NAVI=1 (路线)`，然后 `NAVI_NEXT_TURN_INFO action=1`。
10. [ ] 车机或仪表盘上有转弯箭头、路名、距离递减（米）。
11. [ ] 箭头与 maneuver 相符：右转、左转、环岛、掉头。箭头不对就记下日志里的 nextTurn 和路上实际是什么。
12. [ ] 转弯变化：新箭头和新道路立即出现（无 1 秒延迟）。
13. [ ] 路线结束或重置：`action=3`，然后 `NAVI=0`，仪表盘提示消失。
14. [ ] 路线中的语音提示（TTS）照常播放，NAVI 不闪烁（路线中途日志里没有 NAVI=0）。

字段 6。仅当日志里有 `TX … NAVI_NEXT_TURN_INFO`、但第 10 步什么都没显示或车机报错时：
15. [ ] 只保留“图标”（time 关）→ 重插 USB → 走路线。记录：有没有箭头和距离。
16. [ ] 只保留“time”→ 同样。记录。
17. [ ] 两者都关 → 同样。记录。
18. [ ] 结论：字段 6 哪种模式有效（BOTH / ICON / TIME / NONE），车机是按 nextTurn 画自己的箭头还是用我们的图片。

如果有什么破坏了会话（MEDIA_INFO 或 NEXT_TURN 之后车机断开 USB），关掉“音乐”或“导航”并发日志。

## 未决问题
- 把 `song` 截到 32 字节（Baidu 在支持 48 kHz 时会截）没有复刻：我们发完整字符串。如果车机对长西里尔文歌名截断或出错，按 UTF-8 边界加截断。
- `mode`、`playlistNum`、`songId` 固定为：0、0、"-1"（同 Baidu 无数据时）。
- 不请求 AA 的 maneuver 图标（IMAGE 模式）：SDR 未动，NAVIGATION 保持 ENUM。