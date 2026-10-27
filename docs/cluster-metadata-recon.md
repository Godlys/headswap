# AA → CarLife 音乐与导航元数据（车机仪表盘）

侦察 2026-10-11。起因：CL2AA（ISO_QUARTZ，Toyota Levin）能通过 CarLife 在仪表盘上显示带进度的曲目和分步提示（“Proceed to …, 110 m”带箭头）。
路径：RE = `re/carlife-8.6.7/sources/com/baidu/carlife/`，AND = `android/app/src/main/java/com/yegorkapomidorka/headswap/`。

## 结论
- 音乐：没有阻碍。手机已经通过 AA 在 ch9 上发送 song/artist/album/封面/进度，桥接层把它们忽略了。
- 导航：Vezel 订阅 moduleID=0（SUBSCRIBE_START），我们回复“不发数据”。手机发哪些 nav 事件未知（日志里没有带路线的行车记录）。

## CarLife，MD→HU，CMD 通道（proto2 字段全部 required）
| 内容 | 代码 | Proto / 字段 | Baidu 在哪里发 | 条件 |
|---|---|---|---|---|
| MEDIA_INFO | 0x10035 | CarlifeMediaInfo: source1, song2, artist3, album4, albumArt5 (bytes), duration6, playlistNum7, songId8, mode9 | RE/core/base/subscribe/MusicInfoSender.java:160-262 | 无 init；音乐订阅默认开启（:43），STOP moduleID=2 关闭 |
| MEDIA_PROGRESS_BAR | 0x10036 | CarlifeMediaProgressBar{progressBar=1} | MusicInfoSender.java:293-316 | 1 秒限流 |
| NAVI_NEXT_TURN_INFO | 0x10030 | CarlifeNaviNextTurnInfo: action1, nextTurn2, roadName3, totalDistance4, remainDistance5, 字段6 | RE/core/base/subscribe/RGEventHUDCollection.java:109-125 | 需要 moduleID=0 订阅（SUBSCRIBE_START） |
| MODULE_STATUS NAVI | 0x10026 | {moduleID=2, statusID} | RGEventHUDCollection.java:183,194 | 1 导航开始，0 结束 |
| NAVI_ASSITANTGUIDE_INFO | 0x10047 | — | 8.6.7 中不发送 | DONE supportFlag=false |

细节：
- 字段 6 NextTurn：8.6.7 中是 `bytes turnIconData`；refs/apollo-DuerOS 和 refs/CarProjection 中是 `int32 time`。wire-type 不同，Vezel 期待哪个未知。
- action：1 首次显示、2 更新、3 结束（结束：nextTurn=24、roadName “目的地”、图标为空，:186-193）。
- remainDistance 单位米；totalDistance — 换路时固定的值（:261-269）。
- 没有图标 Baidu 不发转弯更新（:245,277）。图标 PNG < 30720 字节，否则 160×160（:157-173）。
- duration 单位秒（MusicInfoSender.java:184）。progressBar 的单位未确认。
- 封面 JPEG < 30720 字节（CarlifeUtil.java:398），否则 160×160。
- 对通道 20832125（Vezel）8.6.7 没有特殊分支。
- 订阅：HU→MD 0x18043 → DONE 0x10044（CarlifeDataSubscribe.java:18-53）→ START 0x18045 / STOP 0x18046。moduleID：0 导航、1 assistant guide、2 音乐。

## AA，收到什么
- SDR 已经声明 MEDIA_PLAYBACK id=9 和 NAVIGATION id=10（type=2 = ENUM/ImageCodesOnly、minimum_interval_ms=1000）— AND/aa/AapMessages.kt:340-341。ENUM 模式不会有 maneuver 图片。
- ch9：0x8001 MediaPlaybackStatus{state1 (2=PLAYING)、media_source2、playback_seconds3、shuffle4、repeat5、repeat_one6}；0x8003 MediaPlaybackMetadata{song1、artist2、album3、album_art4、playlist5、duration_seconds6、rating7}。refs/aasdk/protobuf/aap_protobuf/service/mediaplayback/。
- ch10：0x8001/0x8002 START/STOP；0x8003 NavigationStatus（0 UNAVAILABLE、1 ACTIVE、2 INACTIVE、3 REROUTING）；0x8004 NextTurnEvent 和 0x8005 NextTurnDistanceEvent（deprecated）；0x8006 NavigationState（steps/maneuver/road/lanes/cue）；0x8007 NavigationCurrentPosition（step_distance、destination_distances、current_road）。解析：refs/headunit-revived/.../aap/AapNavigation.kt:28-130。
- 我们的日志里已经能看到这些，但签名是错的（AapMessages.kt:159-167,395-402 把媒体名称套到所有通道上）：ch9 上的 “MEDIA_START 0x8001” = MediaPlaybackStatus；“MEDIA_CONFIG 0x8003 {status=…, max_unacked=…, f4=PNG}” 在 ch9 = 带 256×256 90–127 KB PNG 封面的 Metadata；ch10 上的 “{status=2}” = NavigationStatus INACTIVE。

## AA → CarLife 映射
音乐：song/artist/album → 同名；album_art PNG → < 30 KB 的 JPEG；duration_seconds → duration；playback_seconds → progressBar（单位待验证）；media_source → source；state → MODULE_STATUS MUSIC 1/0；mode 不明。
导航：NavigationStatus ACTIVE/INACTIVE → MODULE_STATUS NAVI 1/0 + action 1..3；road name → roadName；距离米 → remainDistance；totalDistance 自己算；没有 ETA 字段。
nextTurn（RE/core/base/subscribe/RouteGuideParams.java）：1 直行、2 右前方、3 右转、4 右后方、5 掉头、6 左后方、7 左转、8 左前方、9 进环岛、10 出环岛、11–22 出口/分叉、23 出发、24 到达目的地、29/30 轮渡。AA 按语义映射：STRAIGHT→1、SLIGHT R/L→2/8、NORMAL R/L→3/7、SHARP→4/6、U_TURN→5、ROUNDABOUT_ENTER/EXIT→9/10、FORK/KEEP/MERGE/RAMP→11–22、DEPART→23、DESTINATION→24。

## 桥接现状与嵌入点
- ch9/10 会打开，数据被忽略：AND/aa/AapSession.kt:608（dispatch :590）。
- CarLife：订阅被接受，START 只记日志（CarLifeSession.kt:324-336）；DONE supportFlag=true 对 0 和 2（Protocol.kt:552-562）；MODULE_STATUS — Protocol.kt:529-545；MEDIA_INFO/PROGRESS/NEXT_TURN 只在名称表里（Protocol.kt:136,142,143）。
- 嵌入点：ch9/10 解析 — AapSession.kt:608；CarlifeLink 接口 — AaBridge.kt:10-38；实现位于 CarLifeSession.kt:578，通过 sendCmd（:689）；订阅标志 — CarLifeSession.kt:333-336；构建器 — Protocol.kt:529-573；日志 schema — Proto.kt:131-210。SDR 不动。
- AaBridgeCore 无 Android 依赖（AaBridge.kt:49-52，JVM 测试）→ PNG→JPEG 放在 CarLifeSession 或 Android helper 中。不要与按 isMediaInited 的 MODULE_STATUS MUSIC 冲突（CarLifeSession.kt:377-380）。

## Vezel 车机（logs/car-20261007-4/files/probe-20261007-094730.log）
协议 2.0，通道 20832125，FEATURE_CONFIG 4 个键（全 0），无 NAVI_TYPE。订阅 moduleID=0 + START。不订阅 moduleID=2（不妨碍发送）。仪表盘是否绘制 — 日志看不出来。

## 未知项（车里验证）
- 手机发哪些 nav 消息（legacy 0x8004/5 还是 0x8006/7）— 带路线和完整 ch10 日志的行车。
- 字段 6 的格式（图标还是 time），车机是按 nextTurn 画箭头还是按图标；ENUM 模式下自己画图标（一套箭头）。
- Vezel 是否在未订阅 moduleID=2 时显示音乐；progressBar 的单位；封面/图标的允许尺寸。