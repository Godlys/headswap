# Baidu CarLife 协议（有线 Android / AOA）

本规范根据 CarLife 开源代码（见下方列表）以及对真实车机的观察编写。第一部分（第 1-10 节）通过阅读开源仓库代码得出（1.0 版协议）；车机在 CarLife 2.0 下的具体行为见 [carlife-v2-handshake.md](carlife-v2-handshake.md)。

## 0. 如何阅读

论断上的标记：

- **[已确认]** 由代码确认（旁边的 path:line 引用）。
- **[推测]** 猜测、由间接迹象或外部知识推断；代码中没有直接确认。

路径缩写（仓库均为 GitHub 公开仓库）：

| 别名 | 路径 |
|---|---|
| `HUJ` | [milesking/apollo-DuerOS](https://github.com/milesking/apollo-DuerOS)，`CarLife-Android-Vehicle/src/com/baidu/carlifevehicle`（Baidu 车机端应用，Java，角色为 HU） |
| `HUC` | 同上，`CarLife-Vehicle-Lib/LibSource`（车机端 C++ 库） |
| `PB` | `HUC/protobuf/proto`（全部 `.proto`，proto2） |
| `DOC` | 同上，`CarLife-Vehicle-Lib/CarLifeVehicleLib动态链接库使用说明.docx`（API 说明） |
| `MDL` | [Doris-duo/CarLife-MD-Lib](https://github.com/Doris-duo/CarLife-MD-Lib)，`LibSource` |
| `CP` | [aa112901/CarProjection](https://github.com/aa112901/CarProjection)，`app/src/main/java/com/projection/car`（免 root 的 MD 客户端，走 AOA） |

各来源的实际内容：

- `HUJ` 和 `HUC`：**HU** 侧的实现。这是 1.0 版协议（`PROTOCOL_VERSION_MAJOR=1`、`MINOR=0`，`HUJ/CommonParams.java:79-82`），2015-2017 年代。
- `MDL`：`HUC` 移植到 MD 的版本。与 `HUC` 的差别仅在于新增了四个 MD→HU 的数据发送函数（video/media/vr/tts 通道）（`MDL/core/CTranRecvPackageProcess.cpp:3184-3279`，另有 `MDL/include/CConnectManager.h` 和 `CTranRecvPackageProcess.h` 中的声明）。其中没有 MD 发送 CMD 消息的函数。作为 MD 逻辑参考，该库无用。[已确认]
- `CP`：唯一的可用开源 AOA 方式 MD 实现。比较浅：只回复 5 条消息，其余忽略。[已确认]（`CP/MsgProcess.java:444-575`）
- `icar`：目录里只有 `README.md` 和图片，没有协议源码。README：“Carlife 8.4.2”，使用手机上的原版 CarLife 应用（“参考MultiService服务获取Carlife状态和触摸事件”，“系统权限基于Shizuku”，“音频同步使用蓝牙传输车机”）。也就是说 icar 不实现协议，而是寄生在原版 Baidu 应用上。对本规范没有任何贡献；间接证实了真实车机中声音可能走蓝牙而非 USB（见 §7.4）。[已确认] 仓库 README：[yenole/icar](https://github.com/yenole/icar)。
- Android Auto（aasdk、headunit-revived）与 CarLife 无关，见 [aa-headunit.md](aa-headunit.md)。

应用层消息一律使用 Protocol Buffers **proto2** 编码（`.proto` 中几乎所有字段都是 `required`）。

---

## 1. AOA（USB Accessory）

HU 角色 = USB host（我们的测试中是 PC）。手机（MD）被切换到 accessory mode。

### 1.1 标识字符串 [已确认]

`HUJ/connect/AOAHostSetup.java:71-76`：

| index (wIndex) | 字段 | 值 |
|---|---|---|
| 0 | manufacturer | `Baidu` |
| 1 | model | `CarLife` |
| 2 | description | `Baidu CarLife` |
| 3 | version | `1.0.0` |
| 4 | URI | `http://carlife.baidu.com/` |
| 5 | serial | `0720SerialNo.` |

字符串以 `String.getBytes()` 发送，**不带结尾 NUL**（`AOAHostSetup.java:511-538`）。长度 = wLength。[已确认]

MD 客户端 CarProjection 按 accessory filter `manufacturer="Baidu" model="CarLife" version="1.0.0"` 匹配（`CP/../res/xml/accessory_filter.xml:4-7`，通过 `USB_ACCESSORY_ATTACHED` 在 `AndroidManifest.xml` 中注册）。[已确认] 也就是说我们的 MD 应用必须声明同样的 filter（最好只留 manufacturer+model，不受版本影响）。真实 Honda 车机实际发送什么字符串未知 [推测]：合理推断是相同的，因为 CarProjection 能配合真实车机工作。

### 1.2 HU 的请求顺序 [已确认]

所有请求都以 vendor control transfer 发到 EP0，超时为 0（无限）(`AOAHostSetup.java:425-439`，`TIME_OUT=0` 在 :64)。

| 步骤 | 请求 | bmRequestType | bRequest | wValue | wIndex | 数据 | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | GET_PROTOCOL | `0xC0` (IN\|VENDOR) | 51 | 0 | 0 | 读 2 字节，LE 版本号：`buf[1]<<8 \| buf[0]`；允许 1 或 2，否则拒绝 | :495-509, :66 |
| 2 | SEND_IDENT x6 | `0x40` (OUT\|VENDOR) | 52 | 0 | 0..5 | 字符串（表 1.1） | :511-538, :67 |
| 3 | START_ACCESSORY | `0x40` | 53 | 0 | 0 | 无 | :540-547, :68 |

其他：

- `AOA_AUDIO_SUPPORT = 58` 已声明（:69），但**从未被调用**。HID 请求（54-57）不存在。AOA-audio 没有声音。[已确认]
- 在此之前 HU 会过滤设备：Apple（VID `0x05AC`）以及只有一个 mass storage 接口（class 8、subclass 6、protocol 80）的设备（`AOAHostSetup.java:203-219`、`HUJ/util/CarlifeUtil.java:54-58,390-407`）。[已确认]
- 如果设备已在 accessory mode（见 1.3），跳过步骤 1-3（`AOAHostSetup.java:249-296`）。
- `START_ACCESSORY` 之后手机会重新枚举。HU 每秒扫一次 USB 列表（`AOAConnectManager.java:39,225-229`），切换尝试最多 10 次、切换后空扫最多 5 次（`AOAHostSetup.java:103-104,146-158,256-262`）。[已确认]

### 1.3 切换后的 VID/PID [已确认]

`AOAHostSetup.java:47-55,441-472`：当 VID = `0x18D1` 且 PID 为 `0x2D00`（accessory）、`0x2D01`（accessory+adb）、`0x2D02`（audio）、`0x2D03`（audio+adb）、`0x2D04`（accessory+audio）、`0x2D05`（accessory+audio+adb）之一时，HU 认为设备处于 accessory mode。常量 `PID_ACCESSORY = 0x2D05` 已声明但未在逻辑中使用。

我们的测试预期 `18D1:2D00` 或 `18D1:2D01`（开启了 ADB 的手机）。[推测] 依据 AOA 规范，非代码。

### 1.4 接口、端点、缓冲区

- HU 取接口 `getInterface(0)`，`claimInterface(force=true)`（`AOAHostSetup.java:239-241`）。[已确认]
- 端点**按索引**取：`getEndpoint(0)` = IN，`getEndpoint(1)` = OUT（:62-63,272-273）。地址常量 `0x81` / `0x02` 已声明（:60-61）但未使用。[已确认] 对 pyusb，应按方向找端点（`usb.util.endpoint_direction`），不要按索引。[推测]
- 全部为 bulk 传输。块大小 **16 KiB**：`AOA_MAX_BUFFER_BYTES = 16*1024`（:98）；超过 16 KiB 的读写都切成 16 KiB 的块（:327-350, :378-397）。[已确认]
- 通道头部（8 字节，见 §2）HU 用**单独的 bulk-transfer** 写，随后消息体单独写（`bulkTransferOut(head,8,msg,len)`：两次连续写，:413-423）。读取相同：先精确读 8 字节，再读消息体（`AOAConnectManager.java:266-290`）。[已确认]
- 单条通道消息上限：`AOA_MAX_BYTES = 64 MiB`，下限 8（`AOAConnectManager.java:45,277`）。[已确认]
- [推测] 为什么这很重要：Android 设备读 accessory fd 时按 USB-transfer 边界返回数据；未塞进缓冲区的 transfer 余量会丢失（内核 `f_accessory`，`BULK_BUFFER_SIZE=16384`；CarLife 开源代码里没有这些，这是外部知识）。MD 客户端 CarProjection 先 `read(8)` 再 `read(msgLen)`（`CP/MsgProcess.java:447-455`），只有 HU 把头部和消息体分开发送时才能工作。因此假 HU **必须**把 8 字节外部头部作为独立 bulk 写、消息体随后独立写（每次写入最多 16384 字节）。对 MD 应用：循环读取直到凑够所需字节数。

---

## 2. 单条 bulk 上的通道复用

### 2.1 外部头部（仅 USB/AOA）[已确认]

任何通道的每条消息（双向）都以 8 字节 big-endian 前缀开头：

```
 0       4       8
 +-------+-------+----------------------------+
 | chan  | len   |  inner message (len bytes) |
 | u32BE | u32BE |                            |
 +-------+-------+----------------------------+
```

- `chan`：通道 id（表 2.2）。HU 只接受 1..6（`AOAConnectManager.java:277`）。
- `len`：“内部消息”的长度（内部头部 + 数据），不含这 8 字节。HU 按 `lenMsgHead + lenMsgData` 计算（`AOAConnectManager.java:493-495`）。
- 证据：HU 侧组包 `AOAConnectManager.java:341-364,493,505`；HU 侧解析 :266-314；MD 侧（CarProjection）`Utils.java:19-22`、`MsgProcess.java:198-201,448-452,612-617`：`head[3]=channel`、`intToBytes2(len, head, 4)`。

### 2.2 通道 id [已确认]

`HUJ/CommonParams.java:450-455`、`HUC/include/CTranRecvPackageProcess.h:50-57`：

| id | 名称 | 内部头部 | 写入方 |
|---|---|---|---|
| 1 | CMD | 8 字节 | 双方 |
| 2 | VIDEO | 12 字节 | MD→HU |
| 3 | MEDIA（音乐） | 12 字节 | MD→HU |
| 4 | TTS（导航提示） | 12 字节 | MD→HU |
| 5 | VR（语音） | 12 字节 | MD→HU：应答声音；HU→MD：麦克风 |
| 6 | TOUCH/CTRL | 8 字节 | HU→MD（除罕见的 `UI_ACTION_SOUND`） |

### 2.3 内部头部 [已确认]

CMD 和 TOUCH 通道（8 字节）（`HUJ/connect/CarlifeCmdMessage.java:29-99`、`HUC/core/CTranRecvPackageProcess.cpp:348-420,1591-1621`）：

```
0       2       4               8
+-------+-------+---------------+---------------+
| len   | resv  | service type  | data (len B)  |
| u16BE | u16BE | u32BE         |               |
+-------+-------+---------------+---------------+
```

`len` — protobuf 数据长度（最大 32 KiB：`MSG_CMD_MAX_DATA_LEN`、`CommonParams.java:63`；C++ 缓冲区 40 KiB、`CTranRecvPackageProcess.h:30`）。`resv` 恒为 0（`CarlifeCmdMessage.java:36`；C++ 中字节未初始化，`new u8[]`，实际为 0）[已确认/推测]。

VIDEO/MEDIA/TTS/VR 通道（12 字节）：

```
0       4       8       12
+-------+-------+-------+----------------+
| len   | time  | type  | data (len B)   |
| u32BE | u32BE | u32BE |                |
+-------+-------+-------+----------------+
```

`len` — 数据长度（不含 12 字节）。`time` — 时间戳（见 §5）。HU 侧（`CTranRecvPackageProcess.cpp:348-420`、`HUJ/util/DecodeUtil.java:296-320`、`PackageHeadAnalyseModule.java:75-97`）和 MD 侧（`CP/Utils.java:144-166`）均已确认。

因此 VIDEO 通道在线上为：`[00 00 00 02][outerLen u32]` + `[dataLen u32][ts u32][00 02 00 01]` + H.264。CMD 通道为：`[00 00 00 01][outerLen u32]` + `[dataLen u16][00 00][type u32]` + protobuf。

### 2.4 与 TCP/Wi-Fi 模式的区别 [已确认]

- TCP 模式**没有外部头部**。每个通道是独立的 TCP 连接，内部消息流原样传输。
- 端口（`HUJ/CommonParams.java:425-437`、`HUC/include/CConnectManager.h:23-44`）：MD 监听 7240（CMD）、8240（VIDEO）、9240（MEDIA）、9241（TTS）、9242（VR）、9340（TOUCH）。Wi-Fi 模式下 HU 以客户端身份连接手机的这些端口。ADB 模式下 C++ 库的 HU 做 `adb forward tcp:7200 tcp:7240` 等，连接本地 7200、8200、9200、9201、9202、9300。
- **AOA 模式**下 Java-HU 自行在 127.0.0.1:7200/8200/9200/9201/9202/9300 上拉起本地 TCP 服务器（`AOAConnectManager.java:133-157`），以相同端口作为客户端连接自己（`ConnectManager.java:122-131,298-351`），并在这些 socket 与 USB 之间“搬运”字节，添加/去除外部头部。内部消息与 TCP 模式完全相同。对我们重要的只有：USB 上有外部头部，内部结构与 TCP 一致。端口我们不需要。
- HU 的“连接完成” = 6 个 socket 全部建立（`TOTAL_SOCKET=6`、`ConnectManager.java:87,469-475`）；之后 HU 立刻发 `HU_PROTOCOL_VERSION`。从打开 accessory 到连接 socket 之间 HU 睡 1 秒（`AOAHostSetup.java:286-291`）。[已确认]

---

## 3. CMD 通道消息（通道 id 1）

方向编码在代码本身中：低字节的 `0x8000` 位 = HU→MD，无此位 = MD→HU。例外：`GEAR_INFO`（0x00010029，HU→MD，无此位）。[已确认] 依据 `HUC/include/CTranRecvPackageProcess.h:63-165` 与 `HUJ/CommonParams.java:89-234` 的枚举。

“proto”列按 `PB/*.proto`；“空” = `len=0`，无数据。

| 代码 | 名称 | proto | 方向 | 备注 |
|---|---|---|---|---|
| 0x00018001 | HU_PROTOCOL_VERSION | CarlifeProtocolVersion{majorVersion,minorVersion} | HU→MD | HU 先发 `{1,0}`（`CarlifeProtocolVersionInfoManager.java:51-57,79-92`） |
| 0x00010002 | PROTOCOL_VERSION_MATCH_STATUS | CarlifeProtocolVersionMatchStatus{matchStatus} | MD→HU | 1=匹配，2=不匹配（`CommonParams.java:82-83`） |
| 0x00018003 | HU_INFO | CarlifeDeviceInfo | HU→MD | 字段 Build.* 的 HU 信息（`CarlifeDeviceInfoManager.java:51-94`） |
| 0x00010004 | MD_INFO | CarlifeDeviceInfo | MD→HU | HU 仅保存；AOA 时检查 `sdk_int >= 21`（`CarlifeActivity.java:502-518`） |
| 0x00018005 | HU_BT_OOB_INFO（C++：HU_BT_PAIR_INFO） | CarlifeBTPairInfo | HU→MD | |
| 0x00010006 | MD_BT_OOB_INFO | CarlifeBTPairInfo | MD→HU | |
| 0x00018007 | VIDEO_ENCODER_INIT | CarlifeVideoEncoderInfo{width,height,frameRate} | HU→MD | |
| 0x00010008 | VIDEO_ENCODER_INIT_DONE | CarlifeVideoEncoderInfo | MD→HU | 编码器实际尺寸 |
| 0x00018009 | VIDEO_ENCODER_START | 空 | HU→MD | |
| 0x0001800A | VIDEO_ENCODER_PAUSE | 空 | HU→MD | HU 在 `onPause` 中发送（`CarlifeActivity.java:285`） |
| 0x0001800B | VIDEO_ENCODER_RESET | 空 | HU→MD | Java-HU 未使用 |
| 0x0001800C | VIDEO_ENCODER_FRAME_RATE_CHANGE | CarlifeVideoFrameRate{frameRate} | HU→MD | 自适应 fps（`DecodeUtil.java:336-345,655-667`） |
| 0x0001000D | VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE | CarlifeVideoFrameRate | MD→HU | Java-HU 不处理 |
| 0x0001800E | PAUSE_MEDIA（已废弃） | 空 | HU→MD | |
| 0x0001800F | CAR_VELOCITY | CarlifeCarSpeed | HU→MD | |
| 0x00018010 | CAR_GPS | CarlifeCarGps | HU→MD | |
| 0x00018011 | CAR_GYROSCOPE | CarlifeGyroscope | HU→MD | |
| 0x00018012 | CAR_ACCELERATION | CarlifeAcceleration | HU→MD | |
| 0x00018013 | CAR_OIL | CarlifeOil | HU→MD | |
| 0x00010014 | TELE_STATE_CHANGE_INCOMING | 空 | MD→HU | |
| 0x00010015 | TELE_STATE_CHANGE_OUTGOING | 空 | MD→HU | |
| 0x00010016 | TELE_STATE_CHANGE_IDLE | 空 | MD→HU | |
| 0x00010017 | TELE_STATE_CHANGE_INCALLING | 空 | MD→HU | |
| 0x00010018 | SCREEN_ON | 空 | MD→HU | |
| 0x00010019 | SCREEN_OFF | 空 | MD→HU | |
| 0x0001001A | SCREEN_USERPRESENT | 空 | MD→HU | |
| 0x0001001B | FOREGROUND | 空 | MD→HU | CarProjection 在 INIT_DONE 之后立刻发（`MsgProcess.java:636-646`） |
| 0x0001001C | BACKGROUND | 空 | MD→HU | HU 显示占位屏幕（`CarlifeActivity.java:530-536`） |
| 0x0001801D | LAUNCH_MODE_NORMAL | 空 | HU→MD | |
| 0x0001801E | LAUNCH_MODE_PHONE | 空 | HU→MD | |
| 0x0001801F | LAUNCH_MODE_MAP | 空 | HU→MD | |
| 0x00018020 | LAUNCH_MODE_MUSIC | 空 | HU→MD | |
| 0x00010021 | GO_TO_DESKTOP | 空 | MD→HU | |
| 0x00010022 | MIC_RECORD_WAKEUP_START | 空 | MD→HU | |
| 0x00010023 | MIC_RECORD_END | 空 | MD→HU | |
| 0x00010024 | MIC_RECORD_RECOG_START | 空 | MD→HU | |
| 0x00018025 | GO_TO_FOREGROUND | 空 | HU→MD | `CarlifeUtil.java:295-301` |
| 0x00010026 | MODULE_STATUS | CarlifeModuleStatusList{cnt, repeated CarlifeModuleStatus{moduleID,statusID}} | MD→HU | 见 4.9 |
| 0x00018027 | STATISTIC_INFO | CarlifeStatisticsInfo | HU→MD | |
| 0x00018028 | MODULE_CONTROL | CarlifeModuleStatus{moduleID,statusID} | HU→MD | |
| 0x00010029 | GEAR_INFO | CarlifeGearInfo{gear} | HU→MD | 仅 C++ |
| 0x00010030 | NAVI_NEXT_TURN_INFO | CarlifeNaviNextTurnInfo | MD→HU | |
| 0x00010031 | CAR_DATA_SUBSCRIBE（Java：_REQ） | CarlifeVehicleInfoList | MD→HU | |
| 0x00010032 (C++) / 0x00018032 (Java) | CAR_DATA_SUBSCRIBE_DONE / _RSP | CarlifeVehicleInfoList | HU→MD | **代码不一致**：`HUC/.../CTranRecvPackageProcess.h:125` 与 `HUJ/CommonParams.java:210` 不同；[已确认] 两个值都在代码里，真实 MD 等的是哪个未知 |
| 0x00010033 | CAR_DATA_START_REQ（C++：SUBSCRIBE_START） | CarlifeVehicleInfoList（Java 按单个 CarlifeVehicleInfo 解析） | MD→HU | |
| 0x00010034 | CAR_DATA_STOP_REQ（C++：SUBSCRIBE_STOP） | 同上 | MD→HU | |
| 0x00010035 | MEDIA_INFO | CarlifeMediaInfo | MD→HU | |
| 0x00010036 | MEDIA_PROGRESS_BAR | CarlifeMediaProgressBar{progressBar} | MD→HU | |
| 0x00010037 | CONNECT_EXCEPTION | CarlifeConnectException{exceptionType} | MD→HU | |
| 0x00010038 | REQUEST_GO_TO_FOREGROUND | 空 | MD→HU | HU 回复 `GO_TO_FOREGROUND` |
| 0x00010039 | UI_ACTION_SOUND | 空 | MD→HU | |
| 0x00010040 | BT_HFP_REQUEST | CarlifeBTHfpRequest | MD→HU | |
| 0x00018041 | BT_HFP_INDICATION | CarlifeBTHfpIndication | HU→MD | |
| 0x00018042 | BT_HFP_CONNECTION | CarlifeBTHfpConnection | HU→MD | |
| 0x00018043 | CARLIFE_DATA_SUBSCRIBE | CarlifeSubscribeMobileCarLifeInfoList | HU→MD | 仅 C++ |
| 0x00010044 | CARLIFE_DATA_SUBSCRIBE_DONE | 同上 | MD→HU | 仅 C++ |
| 0x00018045 | CARLIFE_DATA_SUBSCRIBE_START | 同上 | HU→MD | 仅 C++ |
| 0x00018046 | CARLIFE_DATA_SUBSCRIBE_STOP | 同上 | HU→MD | 仅 C++ |
| 0x00010047 | NAVI_ASSITANTGUIDE_INFO | CarlifeNaviAssitantGuideInfo | MD→HU | 仅 C++ |
| 0x00018048 | HU_AUTHEN_REQUEST | CarlifeAuthenRequest{randomValue:string} | HU→MD | 见 4.3 |
| 0x00010049 | MD_AUTHEN_RESPONSE | CarlifeAuthenResponse{encryptValue:string} | MD→HU | |
| 0x0001804A | HU_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | HU→MD | |
| 0x0001004B | MD_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | MD→HU | C++ 注释里误写为 “0x0001804B”，代码和 Java 中为 0x0001004B（DOC、changelog：“修复MSG_CMD_MD_AUTHEN_RESULT消息的错误定义”） |
| 0x0001004C | GO_TO_FOREGROUND_RESPONSE | 空 | MD→HU | MD 若 5 秒内未到前台就发送（DOC） |
| 0x0001004D | START_BT_AUTOPAIR_REQUEST | CarlifeBTStartPairReq | MD→HU | |
| 0x0001804E | BT_HFP_RESPONSE | CarlifeBTHfpResponse | HU→MD | |
| 0x0001004F | BT_HFP_STATUS_REQUEST | CarlifeBTHfpStatusRequest{type} | MD→HU | 主要用于查询车机麦克风状态（DOC） |
| 0x00018050 | BT_HFP_STATUS_RESPONSE | CarlifeBTHfpStatusResponse | HU→MD | |
| 0x00010051 | MD_FEATURE_CONFIG_REQUEST | 空 | MD→HU | |
| 0x00018052 | HU_FEATURE_CONFIG_RESPONSE | CarlifeFeatureConfigList{cnt, repeated {key:string, value:int32}} | HU→MD | |
| 0x00018053 | BT_START_IDENTIFY_REQ | CarlifeBTStartIdentifyReq | HU→MD | |
| 0x00010054 | BT_IDENTIFY_RESULT_IND | CarlifeBTIdentifyResultInd | MD→HU | |
| 0x00018055 | ERROR_CODE | CarlifeErrorCode{errorCode:string} | HU→MD | |
| 0x00018056 | VIDEO_ENCODER_JPEG | 空 | HU→MD | “用 JPEG 代替 H.264”（DOC） |
| 0x00010057 | VIDEO_ENCODER_JPEG_ACK | 空 | MD→HU | |
| 0x00010058 | BT_HFP_CALL_STATUS_COVER | CarlifeBTHfpCallStatusCover{state,phoneNum,name} | MD→HU | 仅 Java，C++ 中 “deprecated” |
| 0x00010059 | MD_EXIT | 空 | MD→HU | HU 视为连接断开（`CarlifeActivity.java:855-857`） |
| 0x0001006A | MD_RSA_PUBLIC_KEY_REQUEST | 空 | MD→HU | 仅 Java，§8 |
| 0x0001806B | HU_RSA_PUBLIC_KEY_RESPONSE | CarlifeHuRsaPublicKeyResponse{rsaPublicKey:string} | HU→MD | |
| 0x0001006C | MD_AES_KEY_SEND_REQUEST | CarlifeMdAesKeyRequest{aesKey:string} | MD→HU | |
| 0x0001806D | HU_AES_REC_RESPONSE | 空 | HU→MD | |
| 0x0001006E | MD_ENCRYPT_READY | 空 | MD→HU | |

表格来源：名称与代码 `HUJ/CommonParams.java:89-234`、C++ 枚举 `HUC/include/CTranRecvPackageProcess.h:63-165`，方向按发送函数 `HUC/core/CTranRecvPackageProcess.cpp:1630-3131` 和接收处理器 `:420-878,971-1590`。仅 Java 消息的 `.proto` 文件（`HuRsaPublicKeyResponse`、`MdAesKeyRequest`、`BTHfpCallStatusCover`）只有生成的 Java（apollo-DuerOS，`CarLife-Android-Vehicle/src/com/baidu/carlife/protobuf/*.java`，描述符内嵌）。其模式为：`rsaPublicKey = 1 (required string)`、`aesKey = 1 (required string)`、`state = 1 (int32), phoneNum = 2 (string), name = 3 (string)`（全部 required）。[已确认]

最小集的关键消息体（proto2 字段全部为 varint/字符串）：

- `CarlifeProtocolVersion`：`08 <major> 10 <minor>`；对 {1,0}：`08 01 10 00`。
- `CarlifeProtocolVersionMatchStatus`：`08 <status>`；匹配 = `08 01`。
- `CarlifeVideoEncoderInfo`：`08 <width> 10 <height> 18 <frameRate>`（varint）。1280x720、fps 0：`08 80 0A 10 D0 05 18 00`。
- `CarlifeAuthenResult`：`08 01`（true）。
- `CarlifeTouchAction`：`08 <action> 10 <x> 18 <y>`。
- `CarlifeCarHardKeyCode`：`08 <keycode>`。
- `CarlifeMusicInit` / `CarlifeTTSInit`：`08 <sampleRate> 10 <channelConfig> 18 <sampleFormat>`。
- `CarlifeStatisticsInfo`：cuid(1,string)、versionName(2,string)、versionCode(3,int32)、channel(4,string)、connectCount(5,int32)、connectSuccessCount(6,int32)、connectTime(7,int32)、crashLog(8,optional string)。
- `CarlifeDeviceInfo`（23 个字段，全部 optional）：os(1)、board(2)、bootloader(3)、brand(4)、cpu_abi(5)、cpu_abi2(6)、device(7)、display(8)、fingerprint(9)、hardware(10)、host(11)、cid(12)、manufacturer(13)、model(14)、product(15)、serial(16)、codename(17)、incremental(18)、release(19)、sdk(20, string)、sdk_int(21, int32)、token(22)、btaddress(23)。
- 完整示例：见 §9。

---

## 4. 逐步握手

序列取自 Java-HU（`CarlifeActivity.java`）、C++ 测试程序（`HUC/CarLifeLibTest.cpp`）和 MD 客户端 CarProjection。真实 Baidu MD 的行为没有源码；只能通过 HU 处理的消息来推断。

### 4.1 起点

1. HU 做 AOA 切换（§1），打开 6 个本地 socket，睡 1 秒，然后连接它们（§2.4）。[已确认]
2. HU **先发** `HU_PROTOCOL_VERSION`。连 C++ 测试程序在所有场景下都先发它：“it is necessary to send MSG_CMD_HU_PROTOCOL_VERSION (0x00018001) message”（`HUC/CarLifeLibTest.cpp:253-293`）。[已确认] Java 中的条件：连接类型为 AOA（`!= USBMUXD`）（`CarlifeActivity.java:341-346`）。

### 4.2 协议版本

- HU→MD `HU_PROTOCOL_VERSION {major=1, minor=0}`。
- MD→HU `PROTOCOL_VERSION_MATCH_STATUS {matchStatus}`。1 = 匹配，2 = 不匹配。CarProjection 无条件回 1（`MsgProcess.java:466-473`）。[已确认]
- HU 若收到非 1：断开连接（`CarlifeActivity.java:465-470`）。[已确认]
- 代码里**没有** `MD_PROTOCOL_VERSION` 消息：HU 不获取 MD 的版本。[已确认]

### 4.3 认证（AUTHEN）

来源中的情况：

- **HU 的 API**：`cmdHuAuthenRequest(randomValue)` → 0x00018048；`cmdHuAuthenResult(bool)` → 0x0001804A；接收 0x00010049 和 0x0001004B（`HUC/core/CTranRecvPackageProcess.cpp:2542-2610`、`HUC/libwrapper/CCarLifeLib.cpp:530-546`）。[已确认]
- **设想的方案**（DOC，“Command channel”一节）：HU 生成随机字符串，放进 `HU_AUTHEN_REQUEST` 发送；MD 用**私钥**加密并放入 `MD_AUTHEN_RESPONSE.encryptValue` 返回；HU 校验（用公钥）并发 `HU_AUTHEN_RESULT`；MD 发 `MD_AUTHEN_RESULT`。“握手”成功后 MD 向 HU 请求功能项（`MD_FEATURE_CONFIG_REQUEST`，DOC：“双方握手认证通过后，手机端会发送该消息来请求车机端的功能定制项”）。算法、密钥格式和字符串编码在代码中未展示。[DOC 已确认；细节推测]
- **Baidu 的 Java-HU（`HUJ`）不实现认证**：`HU_AUTHEN_REQUEST`、`MD_AUTHEN_RESPONSE`、`HU_AUTHEN_RESULT`、`MD_AUTHEN_RESULT` 只出现在 `CommonParams.java` 中（grep `HUJ` 结果）。既不发送也不处理。[已确认]
- **C++ 测试程序**：`cmdHuAuthenRequest(&authenRequest)` 调用被注释掉，`randomValue = "CarLife_1.0.0"`（`HUC/CarLifeLibTest.cpp:130,264-266`）；响应处理器只打印 `encryptValue`（:738-741）。[已确认]
- **CarProjection（MD）**：完全不处理 `HU_AUTHEN_REQUEST`（CMD 类型 switch 中没有该 case，`MsgProcess.java:465-545`），也不发 `MD_AUTHEN_RESPONSE`。唯一相关的：`STATISTIC_INFO` 之后 MD 立刻发 `MD_AUTHEN_RESULT {result=true}`（`MsgProcess.java:524-541`；其本地 `.proto` 将字段命名为 `result`，编号 1，bool 类型，与 `authenResult` wire 兼容，`CarProjection/app/src/main/proto/CarlifeAuthenResultProto.proto`）。按该项目的用途看，这能建立与真实车机的可用连接。[代码已确认，“可用”为推测]
- **icar**：无源码。
- **Baidu CarLife 8.6.7（MD）** 用签名回复（`com.baidu.carlife.authorization.PrivateKeyManager.encryptContentProcess`，`PRIVATE_KEY` 内嵌在应用中）：`randomValue` 先经过 `versionVerify`（第一个 `_` 之后的后缀与应用版本 “8.6.7” 逐段比较：更旧或长度不同 -> `<去掉后缀的字符串>_00.00.00`，否则原样），结果用**私钥**加密（`RSA/ECB/PKCS1Padding`、`Cipher.ENCRYPT_MODE`），Base64 NO_WRAP 后放进 `MD_AUTHEN_RESPONSE.encryptValue`。对 `"txGaIEnzYVCaFOEArS9O_0"` 签名的是 `"txGaIEnzYVCaFOEArS9O_00.00.00"`。若 `randomValue.split(";")` 得到 >= 2 段且第一段为 `"2.0"`，则使用原生 `EncryptionUtils.getVerifyCode(ctx, brand + model + sdk + <第二段>)`（brand/model/sdk 取自 MD_INFO，`CarlifeDeviceInfoManager.java:174`）。在 `libencryption.so` 中，它会校验 APK 签名是否等于 `APP_SIGNATURE_CARLIFE`（是别人的签名就返回 null），返回 `md5hex("CARLIFE" + str)` 小写（`MD5_HEAD = "CARLIFE"`，类 `MD5` 来自 `md5.cpp`）；`getVerifyResult(info, code)` 对车机来说是同样的 md5 加 `strcmp`。我们这边是 `HuAuth.verifyCode`。[反汇编确认]
- 对我们的结论：收到 `HU_AUTHEN_REQUEST` 时，像 Baidu 一样立刻用 Baidu 密钥签名回复（`Handshake.kt` 中的 `HuAuth`）；`STATISTIC_INFO` 之后无论如何都发 `MD_AUTHEN_RESULT{true}`（Baidu 也这么做）。如果 HU 不发 `HU_AUTHEN_REQUEST`（Honda Vezel 2024），就只有 `MD_AUTHEN_RESULT{true}`，不启用加密。VW Tharu XR 2026 车机会发 `HU_AUTHEN_REQUEST`（`randomValue="txGaIEnzYVCaFOEArS9O_0"`），且 inner 头部与消息体是分开的外部帧；由 `InnerAssembler`（`Protocol.kt`）组包。见 §10。

### 4.4 统计与设备信息

`MATCH_STATUS = 1` 之后 HU 按此顺序（`CarlifeActivity.java:439-457`）：

1. 启动“心跳”监控（4.8）；
2. `STATISTIC_INFO`（HU→MD）（`CarlifeUtil.java:303-326`）；
3. `HU_INFO`（HU→MD）（`CarlifeDeviceInfoManager.java:108-121`）；
4. `VIDEO_ENCODER_INIT`（HU→MD）（见 4.5），**除非** HU 开了 `CONTENT_ENCRYPTION` 且 `AES_ENCRYPT_AS_BEGINE=false`：此时 HU 等 `MD_ENCRYPT_READY`（§8）；
5. 启动解码器、PCM 播放器、touch；
6. `MODULE_CONTROL {moduleID=5 (CONNECT), statusID=2 (AOA)}`（`ConnectManager.java:659-671`、`ModuleStatusModel.java:43`）。

`MD_INFO` 由 MD 自行发送；CarProjection 用它回复 `HU_INFO`（`MsgProcess.java:474-495`）。HU 对 `MD_INFO` 只保存并检查 `sdk_int >= LOLLIPOP`；否则 2 秒后显示“不支持 AOA”（`CarlifeActivity.java:502-518`）。因此 `MD_INFO` 中必须填 `sdk_int >= 21`。[已确认]

### 4.5 视频：INIT / INIT_DONE / START

- HU→MD `VIDEO_ENCODER_INIT {width, height, frameRate}`。Java-HU：`width/height` = 自己 SurfaceView 的像素尺寸，`frameRate = 0`（“if 0, means no limit to frame rate”，`CarlifeUtil.java:233-248`；调用处 `CarlifeActivity.java:447`）。C++ 测试程序：768x480@30（`CarLifeLibTest.cpp:120`）。[已确认]
- MD→HU `VIDEO_ENCODER_INIT_DONE {width, height, frameRate}`：MD 将要编码的**实际**尺寸。HU 用**这些值**初始化 MediaCodec，并作为“phone container”换算触控坐标（`CarlifeActivity.java:472-494`）。也就是说 MD 有权回不同的分辨率（例如按宽高比调整过）。CarProjection 回显收到的 `msgdata`（`MsgProcess.java:515`；之前构造的 `builder` 未使用）。[已确认]
- INIT_DONE 之后 HU：`initDecoder(surface, w, h)`；发 `VIDEO_ENCODER_START`；发 `LAUNCH_MODE_*`（`CarlifeActivity.java:486-497`）。[已确认]
- MD 在 `VIDEO_ENCODER_START` 之后开始发视频。CarProjection 在收到 `START` 时先发 `MEDIA_INIT`（48000/2/16），然后启动 MediaProjection+MediaCodec（`MsgProcess.java:651-670`）。[已确认]
- `onResume` 时 HU 发 `VIDEO_ENCODER_START`，前面没有 `INIT`（`CarlifeActivity.java:231-233`）；`onPause` 时发 `VIDEO_ENCODER_PAUSE`（:285）。MD 必须能扛住重复的 `START`/`PAUSE`。[已确认]

### 4.6 前台 / 后台

- MD→HU `FOREGROUND`（空）：HU 在解码器就绪后 2 秒显示视频（`CarlifeActivity.java:537-542`）。CarProjection 在 `INIT_DONE` 后立刻发（`MsgProcess.java:636-646`）。
- MD→HU `BACKGROUND`：HU 显示占位图（`CarlifeActivity.java:530-536`）。
- 解码器输出 16 帧后视为就绪（`DecodeUtil.java:555,616`、`mOutputCount >= 16`），之后 HU 显示投影画面。[已确认]

### 4.7 Feature config

- MD→HU `MD_FEATURE_CONFIG_REQUEST`（空）→ HU→MD `HU_FEATURE_CONFIG_RESPONSE {cnt, [key,value]...}`（`HUJ/logic/FeatureConfigManager.java:87-138`）。若 HU 一条记录都没有，就**什么都不回**（:94 处 `getFeatureConfigCount() > 0`）。[已确认]
- Java-HU 的键（`FeatureConfigManager.java:117-137`，名称 `CarlifeConfUtil.java:166-188`）：`VOICE_WAKEUP`、`BLUETOOTH_AUTO_PAIR`、`BLUETOOTH_INTERNAL_UI`、`FOCUS_UI`、`VOICE_MIC`（0 = 车机麦克风，1 = 手机麦克风，2 = 无）、`MEDIA_SAMPLE_RATE`（0 = 原始，1 = 48 kHz）、`AUDIO_TRANSMISSION_MODE`、`CONTENT_ENCRYPTION`、`ENGINE_TYPE`、`INPUT_DISABLE`。值来自加密配置 `assets/bdcf`（内容未读）。[键已确认；值推测]
- CarProjection 不发 `MD_FEATURE_CONFIG_REQUEST`（代码里没有），即按 HU 默认设置工作。[已确认]
- 真实 MD 通常在认证成功后发此请求（DOC）。[DOC 已确认]

### 4.8 心跳

- 没有单独的 CMD 心跳。“心跳”活在 VIDEO 通道上：MD 发送视频消息；`len = 0` 的消息 HU 视为心跳（`DecodeUtil.java:320-323`：“empty package, used for heart beat”），其类型为 `MSG_VIDEO_HEARTBEAT = 0x00020002`（`CommonParams.java:198`，处理 `HUC/core/CTranRecvPackageProcess.cpp:746`）。[已确认]
- HU 每秒数一次视频 socket 的读取调用（`numOfHeartBeat`、`ConnectManager.java:521-528`）；AOA（非 Wi-Fi/tether/usbmuxd）下连续 6 个 tick 没有读取就断开连接（计时器 5 秒后启动，之后每 1 秒；`ConnectManager.java:585-633`），即最后一次视频读取后约 11 秒。[已确认]
- 总之 MD 至少每 ~5 秒要向 VIDEO 发一条消息：帧或空 `VIDEO_HEARTBEAT`。CarProjection 专门重复帧（`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100000 us`、`MediaCodecTool.java:74`），不发心跳。真实 MD 的周期未知。[推测]
- 断开：HU 在收到 `MD_EXIT` 或任何读/写错误时（`ConnectClient.setIsConnected(false)`）。

### 4.9 模块（MODULE_STATUS）

MD→HU `MODULE_STATUS {cnt, [moduleID,statusID]...}`，模块状态变化时发送。id（`HUJ/model/ModuleStatusModel.java:20-48`）：PHONE=1（0 idle、1 incoming、2 outgoing）、NAVI=2（0 idle、1 start、2 stop）、MUSIC=3（0 idle、1 running）、VR=4（0 idle、1 running）、CONNECT=5（HU→MD，仅 iOS 连接类型；Java-HU 对 AOA 发 `2`）、MIC=6（0 车机麦克风、1 手机麦克风、2 不支持）。HU 保存状态并响应：电话——显示“通话中”占位，VR——语音 UI。最小集下 MD 可以完全不发 `MODULE_STATUS`。[已确认]

### 4.10 其他

- `STATISTIC_INFO.cuid` — 车机 ID；CarProjection 把它显示给用户作为 “VIS ID”（`MsgProcess.java:526-537`）。
- `HU_LOG = 0x7FFF0001` 只在 Java-HU 内部存在（`CommonParams.java:89`），不走线上。

---

## 5. 视频通道（id 2）

### 5.1 帧格式 [已确认]

MD→HU 消息：`[outer: 00 00 00 02, len]` + `[dataLen u32][timestamp u32][00 02 00 01]` + 数据。

- 类型：`MSG_VIDEO_DATA = 0x00020001`；心跳 `0x00020002` 且 `dataLen = 0`。
- 数据：一帧编码后的 **H.264、Annex-B、4 字节起始码** `00 00 00 01`。HU 将 `MediaCodec` 配置为 `video/avc`（`DecodeUtil.java:195-197`），并自行解析码流。
- 最大帧：Java-HU `1920*1080*3/2 = 3 110 400` 字节（`DecodeUtil.java:26,304-306`）；C++ 缓冲区 600 KiB（`CTranRecvPackageProcess.h:33`）。
- `timestamp`：Java-HU 读取后**忽略**（`DecodeUtil.java:310-314`）。CarProjection 填 `(int)System.currentTimeMillis()`（`Utils.java:149,161`）；C++ MD-Lib 中同样只是参数 `timeStamp`。[已确认]
- 加密（若启用）：只加密数据块，头部中的 `dataLen` = 密文大小（`DecodeUtil.java:354-362`）。

### 5.2 SPS/PPS 与 IDR [已确认]

Java-HU 的要求（`DecodeUtil.java:485-520`）：

- 第一帧接收时必须从 SPS（NAL type 7）开始：检查 `(buf[4] & 0x1F) == 7`。HU 寻找接下来的两个起始码 `00 00 00 01`（`findFlag2`，:465-485），即要求**第一帧开头连续 SPS 和 PPS**，并把这段前缀记作 `mSpsPps`。如果第一帧不以 SPS 开头，则静默丢弃（`byteCount == 0`，return）。
- 如果 SPS+PPS 之后紧跟的不是 IDR，则等待后面的 IDR。
- 解码器内部重置后，HU 把保存的 `mSpsPps` 补到**直接以 IDR 开头**（`(buf[4] & 0x1F) == 5`、`isIFrame`、:455-463；:375-398）的帧前；重置状态中非“裸 IDR”的帧被丢弃。
- CarProjection 的做法：SPS+PPS（BUFFER_FLAG_CODEC_CONFIG）**只**贴在第一个关键帧上，之后的关键帧不带 SPS/PPS（`MediaCodecTool.java:97-124`），`I_FRAME_INTERVAL = 1`（秒）（:73）。建议：照抄这种做法（第一帧 = 一条消息里 SPS+PPS+IDR；后续 IDR 为裸的）。如果每个 IDR 都带 SPS/PPS，则重置状态中该帧不会被识别为 IDR，会被丢弃直到下一个“裸”IDR。[HU 代码已确认；风险结论为推测]

### 5.3 档次、分辨率、fps

- HU 代码里**没有**对 profile/level/分辨率的限制；`MediaFormat` 只用 INIT_DONE 里的 width/height 创建，不传 csd（SPS 从码流中取）（`DecodeUtil.java:195-203`）。[已确认]
- CarProjection 编码 `COLOR_FormatSurface`，未指定 profile（`profile=1`、`level=256` 被注释；:77-78），默认码率 3 000 000，fps 30（`MainActivity.java:176-177`；`MediaCodecTool.java:70-72` 中实参写反了，实际 bit rate 拿到的是 “frame” = 3 000 000，frame rate 拿到的是 “bit” = 30）。原始分辨率 1280x720（`MsgProcess.java:106-107`）。[已确认]
- 建议 [推测]：Baseline/Main/High 4:2:0，无 B 帧（Changan 分支的 HU 设 `disreorder=1`，`DecodeUtil.java:25,198-202`，说明解码器默认开了 reorder）、尺寸 16 的倍数、720p@30。
- fps 控制：HU 自己把目标 fps 从 30 降到 3，并发 `VIDEO_ENCODER_FRAME_RATE_CHANGE`（`DecodeUtil.java:336-346,636-666`）；MD 应改变编码器帧率并（按协议）回复 `FRAME_RATE_CHANGE_DONE`。CarProjection 两者都不处理。[已确认]

---

## 6. 触摸与控制（TOUCH 通道，id 6）

内部头部 8 字节，与 CMD 相同。方向 HU→MD。[已确认]

### 6.1 主模式（MSG_TOUCH_ACTION = 0x00068001）

`CarlifeTouchAction {action:int32 = 1, x:int32 = 2, y:int32 = 3, optional pointerx:int32 = 4, optional pointery:int32 = 5}`（`PB/CarlifeTouchActionProto.proto`；字段 4、5 出现在 `re/carlife-8.6.7/resources/CarlifeTouchActionProto.proto`）。

- `action`：`MotionEvent` 代码：0 = DOWN、1 = UP、2 = MOVE（HU 写 `event.getAction()`，`HUJ/touch/TouchListenerManager.java:207-231`；MD 侧 `CP/Utils.java:24-26`、`MsgProcess.java:242-310`）。协议中有多点触控（最多两根手指），见 §6.1.1。[已确认]
- `x`、`y`：**编码视频坐标系中的像素**，由车机屏幕坐标换算：`x*phoneW/carW`、`y*phoneH/carH`，其中 `phoneW/H` = `VIDEO_ENCODER_INIT_DONE` 中的尺寸，`carW/H` = 车机 SurfaceView 尺寸（`TouchListenerManager.java:211-212`、:488-494 `CarlifeActivity`）。原点在左上角。[已确认]
- 模式由车机配置的 `TRANSPARENT_SEND_TOUCH_EVENT` 标志开启：`true` → 本模式（`CarlifeActivity.java:453-454`、`TouchListenerManager.java:60-73,88-101`）。真实 Honda 用哪种模式未知。[推测]
- CarProjection 的 MD 侧**只**处理 `MSG_TOUCH_ACTION` 和 `MSG_TOUCH_CAR_HARD_KEY_CODE`（`MsgProcess.java:546-575`），忽略其余类型。也就是说我们的 MD 实现这两个，其余（下文）接受后丢弃或映射。[已确认]

#### 6.1.1 多点触控（第二根手指）

手机侧处理范本：Baidu CarLife 8.6.7 的 `CarlifeTouchManager.onActionEvent`（`re/carlife-8.6.7/sources/com/baidu/carlife/core/screen/touch/CarlifeTouchManager.java:640-662`）。[已确认]

- `action` 同 `MotionEvent`：`action & 255` = 代码（0 DOWN、1 UP、2 MOVE、5 POINTER_DOWN、6 POINTER_UP），`action >> 8` = 手指索引。第二根手指以 `261`（5|1<<8）/ `262`（6|1<<8）落下/抬起；`6` = 索引 0 的手指抬起。Baidu 不处理超过两根手指（丢弃 `action > 261/262`）。[已确认]
- `x, y` = 索引 0 的手指，`pointerx, pointery` = 索引 1 的手指，MOVE 中同样如此。`pointerx == 0 && pointery == 0` = 没有第二根手指：POINTER_DOWN 时 Baidu 把第二根手指放在 (x, y)，POINTER_UP 时取它上次 MOVE 的坐标。[已确认]
- POINTER_UP（索引 0）之后，剩余手指进入 x, y；在 Headswap 中它的 AA pointer_id 不变（1）。[推测]
- 车机是否发第二根手指取决于车机；Headswap 写入日志 `TOUCH action=… 手指数=… 消息体=… 字节`，以及一次性行 `== 多点触控`。如果双指缩放时日志里只有 action 0/1/2 且消息体最多 8 字节，说明车机不发第二根手指。[推测]

### 6.2 “手势”模式（如果 `TRANSPARENT_SEND_TOUCH_EVENT=false`）

HU 用 `GestureDetector` 解析触摸并发送，消息体均为 `CarlifeTouchSinglePoint {x=1, y=2, optional pointerx=3, optional pointery=4}`，同样以像素为单位（`TouchListenerManager.java:233-355`、`GestureListenerImp.java:36-161`）：

| 代码 | 名称 | 时机 |
|---|---|---|
| 0x0006800A | TOUCH_ACTION_BEGIN | `onDown` |
| 0x00068002 | TOUCH_ACTION_DOWN | 滚动开始（或若 `SEND_ACTION_DOWN` 则在 `onDown`） |
| 0x00068004 | TOUCH_ACTION_MOVE | `onScroll` |
| 0x00068003 | TOUCH_ACTION_UP | fling / 最后一次 MOVE 后 150 毫秒超时 / 长按 |
| 0x00068005 | TOUCH_SINGLE_CLICK | `onSingleTapUp` |
| 0x00068006 | TOUCH_DOUBLE_CLICK | 双击 |
| 0x00068007 | TOUCH_LONG_PRESS | `onShowPress` |
| 0x0006800B | TOUCH_ACTION_POINTER_DOWN | 第二根手指落下；Baidu：相当于无 pointer 字段的 action 261 |
| 0x0006800C | TOUCH_ACTION_POINTER_UP | 索引 0 的手指抬起；Baidu：相当于 action 6 |
| 0x0006800D | TOUCH_ACTION_OTHERPOINTER_UP | 第二根手指抬起；Baidu：相当于 action 262 |

代码 0x6800B-D 和 `CarlifeTouchSinglePoint {x=1, y=2, pointerx=3, pointery=4}` 的 pointer 字段只有 Baidu 8.6.7 手机侧知道（`CarlifeTouchManager.java:519-561`）；哪些车机发它们未知。[推测]

`PB/` 中还有 `CarlifeTouchEvent`、`TouchFling`、`TouchScroll`、`TouchEventDevice/AllDevice`（用于原始 evdev 事件和手势）；Java-HU 和 C++ HU API 均未使用。[已确认]

### 6.3 实体按键

`MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008`，消息体 `CarlifeCarHardKeyCode {keycode = 1}`（`TouchListenerManager.java:399-415`）。keycode（`HUJ/CommonParams.java:244-292`）：HOME=1、PHONE_CALL=2、PHONE_END=3、PHONE_END_MUTE=4、HFP=5、SELECTOR_NEXT=6、SELECTOR_PREVIOUS=7、SETTING=8、MEDIA=9、RADIO=0x0A、NAVI=0x0B、SRC=0x0C、MODE=0x0D、BACK=0x0E、SEEK_SUB=0x0F、SEEK_ADD=0x10、VOLUME_SUB=0x11、VOLUME_ADD=0x12、MUTE=0x13、OK=0x14、MOVE_LEFT=0x15、MOVE_RIGHT=0x16、MOVE_UP=0x17、MOVE_DOWN=0x18、MOVE_UP_LEFT=0x19、MOVE_UP_RIGHT=0x1A、MOVE_DOWN_LEFT=0x1B、MOVE_DOWN_RIGHT=0x1C、TEL=0x1D、MAIN=0x1E、MEDIA_START=0x1F、MEDIA_STOP=0x20、VR_START=0x21、VR_STOP=0x22、NUMBER_0..9 = 0x23..0x2C、STAR=0x2D、POUND=0x2E、DEL=0x2F、CLEAR=0x30、ADD=0x31。CarProjection 对 SEEK_SUB/SEEK_ADD 有反应（上一首/下一首）（`MsgProcess.java:549-564`）。C++ 枚举中的名称到 0x1E 为止（`CTranRecvPackageProcess.h:208-237`）。[已确认]

`MSG_TOUCH_UI_ACTION_SOUND = 0x00060009`（MD→HU，按键音效）在两个代码中都定义了；HU 库在 CTRL 通道上什么都不收（`CTranRecvPackageProcess.cpp:293-345` 没有 CTRL_CHANNEL 分支）；对我们来说是死代码。[已确认]

---

## 7. 音频

### 7.1 通道

| 通道 | id | 用途 | 来源 → 去向 |
|---|---|---|---|
| MEDIA | 3 | 音乐 | MD→HU |
| TTS | 4 | 导航语音提示 | MD→HU |
| VR | 5 | 语音助手：应答 MD→HU，麦克风 HU→MD | 双向 |

三者格式一致：**未压缩 PCM**，无编解码器。协议里没有任何 AAC/Opus。[已确认]（`HUJ/audioplayer/audiotrackmanager/AudioTrackManagerSingle.java:84-134` 用 `ENCODING_PCM_8BIT/16BIT` 创建 `AudioTrack`；`MEDIA_DATA` 原样写入 `AudioTrack`）。

### 7.2 Init 消息

发起方：**MD**（HU 自己不请求任何东西）。数据流之前 MD 发送 `*_INIT`，带 `CarlifeMusicInit`（media/VR）或 `CarlifeTTSInit`（tts），字段相同：

`{sampleRate:int32 = 1, channelConfig:int32 = 2, sampleFormat:int32 = 3}`，其中 `channelConfig` = **声道数**（1 = 单声道，2 = 立体声；其他值按立体声处理），`sampleFormat` = **位深**（8 → PCM 8 位，其他 → 16 位）（`AudioTrackManagerSingle.java:113-125`）。[已确认]

HU 解析失败时的默认值：音乐 44100/2/16，TTS 16000/1/16（`PackageHeadAnalyseModule.java:208,256`）。CarProjection 发 `MEDIA_INIT` 48000/2/16（`MsgProcess.java:654-658`）和数据：抓取内部声音（`AudioPlaybackCapture` 或 REMOTE_SUBMIX）48000 Hz、立体声、PCM16，每块 2560 字节（`MsgProcess.java:345-372,386-397`）。[已确认]

代码：

| 通道 | INIT | DATA | 其他 |
|---|---|---|---|
| MEDIA | 0x00030001 | 0x00030006 | STOP 0x30002、PAUSE 0x30003、RESUME_PLAY 0x30004、SEEK_TO 0x30005 |
| TTS | 0x00040001 | 0x00040003 | END/STOP 0x40002 |
| VR（MD→HU） | 0x00050002 (INIT) | 0x00050003 (DATA) | STOP 0x50004、INTERRUPT 0x50006（仅 Java） |
| VR（HU→MD） | | 0x00058001 MIC DATA | |

（`CommonParams.java:200-224`；C++ `CTranRecvPackageProcess.h:169-196`；Java 中 `MSG_VR_DATA = 0x00058001` 是麦克风，C++ 中同一常量名为 `MSG_VR_MIC_DATA`，而 `MSG_VR_DATA = 0x00050003` 是应答声音。）[已确认]

大小：音乐每条消息最多 100 KiB（`PCMPlayerUtils.java:103`、`CTranRecvPackageProcess.h:36`），TTS/VR 最多 50 KiB（:39,42）。头部 12 字节，同 §2.3。[已确认]

### 7.3 麦克风（VR record）

- HU→MD 走 VR 通道（id 5），type `0x00058001`，头部 12 字节，`timestamp = 0`（`HUJ/logic/voice/PcmSender.java:188-217`；C++ `sendMicData`、`CTranRecvPackageProcess.cpp:2865-2886`）。数据：PCM16 单声道 **16 kHz**（`logic/voice/PcmRecorder.java:29-35,56`），可选通过简单抽稀压到 8 kHz（`PcmSender.java:151-181`）。每块最多 320 个队列元素。[已确认]
- HU 何时写麦克风：按 MD 的状态。`MIC_RECORD_WAKEUP_START` (0x00010022)、`MIC_RECORD_RECOG_START` (0x00010024)、`MIC_RECORD_END` (0x00010023) 以及 VR 模块的 `MODULE_STATUS`（`logic/voice/VoiceManager.java:84-120,160-190`）。车机麦克风在 `VOICE_MIC = 0` 时才会被使用。[已确认]

### 7.4 声音传输模式

HU 配置 `AUDIO_TRANSMISSION_MODE`（`FeatureConfigManager.java:129-130`）和 `PCMPlayerUtils.isBTTransmissionMode()`：如果声音走蓝牙（A2DP），HU 丢弃 USB 传来的 PCM，`if (!PCMPlayerUtils.isBTTransmissionMode()) mQueue.add(...)`（`MediaReceiveProcessor.java:126,148,171`）。值未解密。[标志已确认；值推测]

---

## 8. AES / RSA 加密 [已确认]

文件：`HUJ/encryption/{EncryptConfig,EncryptSetupManager,RSAManager,AESManager}.java`。

- **Java-HU 默认不强制开启。** 只在 MD 主动发起时开启：MD 发 `MD_RSA_PUBLIC_KEY_REQUEST` (0x0001006A)。否则 `isEncryptEnable()` = false（`EncryptConfig.java:22-24`：`DEBUG_ENABLE = true`、`AES_ENCRYPT_AS_BEGINE = false`；`EncryptSetupManager.java:87-97`）。CarProjection 不使用加密。[已确认]
- 例外：如果 HU 配置含 `CONTENT_ENCRYPTION = 1`，HU 在 `MATCH_STATUS` 之后**不发** `VIDEO_ENCODER_INIT`，而是等 `MD_ENCRYPT_READY`（`CarlifeActivity.java:445-449,902-905`）。此时 MD 必须发起 RSA/AES。[已确认]
- 若把 `AES_ENCRYPT_AS_BEGINE = true`（测试模式），双方从一开始就用密钥 `"1234567890123456"` 加密所有内容（`EncryptSetupManager.java:37,87-97`、`AESManager.java:32-40`）。[已确认]

序列（CMD 通道）：

1. MD→HU `MD_RSA_PUBLIC_KEY_REQUEST`（空）。
2. HU→MD `HU_RSA_PUBLIC_KEY_RESPONSE {rsaPublicKey}`：RSA-2048，密钥在 HU 启动时生成（`RSAManager.java:122-134`），形式为 `PublicKey.getEncoded()` 的 Base64（NO_WRAP），即 DER X.509 SubjectPublicKeyInfo（:133-134）。（HU 中的解析器 `getmPubkey` 不知道为什么用 `PKCS8EncodedKeySpec`，但它没被调用。）
3. MD→HU `MD_AES_KEY_SEND_REQUEST {aesKey}`：16 字符密钥（ASCII），用 HU 的公钥按 RSA `RSA/ECB/PKCS1Padding` 加密，然后 Base64（NO_WRAP）（`RSAManager.java:80-120`、`EncryptConfig.java:29`；`EncryptSetupManager.java:67-85`）。HU 解密后**立即启用加密**，在发送响应之前（`CarlifeActivity.java:890-901`）。
4. HU→MD `HU_AES_REC_RESPONSE`（空；消息体为零长，因此不加密）。
5. MD→HU `MD_ENCRYPT_READY`（空）。HU 以发送 `VIDEO_ENCODER_INIT` 回应（`CarlifeActivity.java:902-905`）。

启用之后：

- 所有通道、两个方向上**任何长度 > 0 的消息体**都会被加密：CMD 和 TOUCH（`ConnectSocket.java:140-152,258-266`）、VIDEO（`DecodeUtil.java:354-362`）、MEDIA/TTS/VR 的数据和 init（`MediaReceiveProcessor.java:114`、`NaviReceiveProcessor.java:101`、`VRReceiveProcessor.java:108`、`PackageHeadAnalyseModule.java:185,233`）、麦克风（`PcmSender.java:203-212`）。头部明文。
- 算法：Android 上 `Cipher.getInstance("AES")` = **AES/ECB/PKCS5Padding**，16 字节密钥 = 字符串 `aesKey` 的字节（`AESManager.java:56-63`）。[已确认，“AES”默认 = ECB/PKCS5 是依 JCE 知识的推测]
- 头部中 `len` 字段 = 密文长度（`ConnectSocket.java:146`）。

---

## 9. 最小路径“MD 显示 720p 视频并接收触摸”

前提：两端使用 §2.1 的外部头部，8 字节通道头部用**独立的** bulk-transfer 发送，随后消息体（§1.4）。不使用加密和认证。

### 9.1 序列

| # | 方向 | 通道 | 消息 | 消息体 |
|---|---|---|---|---|
| 0 | HU | USB | AOA：GET_PROTOCOL、6x SEND_IDENT、START_ACCESSORY，等 `18D1:2D0x`，claim interface 0 | 见 §1 |
| 1 | HU→MD | CMD | `HU_PROTOCOL_VERSION` 0x00018001 | `08 01 10 00` |
| 2 | MD→HU | CMD | `PROTOCOL_VERSION_MATCH_STATUS` 0x00010002 | `08 01` |
| 3 | HU→MD | CMD | `STATISTIC_INFO` 0x00018027 | cuid 等（MD 可忽略） |
| 4 | HU→MD | CMD | `HU_INFO` 0x00018003 | CarlifeDeviceInfo |
| 5 | MD→HU | CMD | `MD_INFO` 0x00010004 | CarlifeDeviceInfo，带 `os="Android"`、`sdk_int >= 21` |
| 6 | MD→HU | CMD | `MD_AUTHEN_RESULT` 0x0001004B | `08 01`（参照 CarProjection，在第 3 步之后） |
| 7 | HU→MD | CMD | `VIDEO_ENCODER_INIT` 0x00018007 | `08 80 0A 10 D0 05 18 00`（1280x720、fps 0） |
| 8 | MD→HU | CMD | `VIDEO_ENCODER_INIT_DONE` 0x00010008 | `08 80 0A 10 D0 05 18 1E`（1280x720、30） |
| 9 | MD→HU | CMD | `FOREGROUND` 0x0001001B | 空 |
| 10 | HU→MD | CMD | `VIDEO_ENCODER_START` 0x00018009 | 空 |
| 11 | HU→MD | CMD | `LAUNCH_MODE_NORMAL` 0x0001801D | 空（可选） |
| 12 | HU→MD | CMD | `MODULE_CONTROL` 0x00018028 (CONNECT=2) | `08 05 10 02` |
| 13 | MD→HU | VIDEO | `VIDEO_DATA` 0x00020001：第一帧 SPS+PPS+IDR，之后 P 帧/裸 IDR；空闲时发心跳 `0x00020002`（len 0） | H.264 Annex-B |
| 14 | HU→MD | TOUCH | `TOUCH_ACTION` 0x00068001（DOWN=0、MOVE=2、UP=1） | `08 act 10 x 18 y` |
| 15 | HU→MD | TOUCH | `TOUCH_CAR_HARD_KEY_CODE` 0x00068008（可选） | `08 key` |

最小视频+触控不必需：MEDIA/TTS/VR、MODULE_STATUS、FEATURE_CONFIG、FRAME_RATE_CHANGE（但最好接收且不崩溃）。

### 9.2 线上字节（示例）

`HU_PROTOCOL_VERSION`：
```
outer: 00 00 00 01  00 00 00 0C
inner: 00 04 00 00  00 01 80 01  08 01 10 00
```
`MATCH_STATUS`：
```
outer: 00 00 00 01  00 00 00 0A
inner: 00 02 00 00  00 01 00 02  08 01
```
`VIDEO_ENCODER_INIT` 1280x720、fps 0：
```
outer: 00 00 00 01  00 00 00 10
inner: 00 08 00 00  00 01 80 07  08 80 0A 10 D0 05 18 00
```
`VIDEO_ENCODER_START`：
```
outer: 00 00 00 01  00 00 00 08
inner: 00 00 00 00  00 01 80 09
```
`TOUCH_ACTION` DOWN (640,360)：
```
outer: 00 00 00 06  00 00 00 10
inner: 00 08 00 00  00 06 80 01  08 00 10 80 05 18 E8 02
```
`VIDEO_DATA`（n 字节 H.264）：
```
outer: 00 00 00 02  <u32: n+12>
inner: <u32 n> <u32 ts>  00 02 00 01  <n 字节 Annex-B>
```

### 9.3 要实现的

**假 HU（Python，pyusb + libusb/WinUSB）**：

- 切换 AOA 并打开接口 0；端点按方向查找。
- 写入：`write(outer8)` 独立 transfer，然后 `write(body)` 按 ≤ 16384 的块。
- 读取：大缓冲区（16384），字节积累成流，按外部头部 `chan/len` 解析，再按通道内部头部解析（§2.3）。解析时不要依赖 transfer 边界。
- 状态：CONNECTED → 发 VERSION → 等 MATCH（若 ≠1 则停止）→ STATISTIC、HU_INFO、INIT(1280x720,0) → 等 INIT_DONE → START → 收视频，让解码器处理（ffplay/PyAV）→ 发 TOUCH_ACTION。
- “MD 是否存活”超时：> 6 秒没有视频消息 = 断开（与真实 HU 一致）。
- 接收并记录所有未知类型，不崩溃。

**MD 应用（Android）**：

- `uses-feature android.hardware.usb.accessory`，accessory filter `manufacturer="Baidu" model="CarLife"`、`USB_ACCESSORY_ATTACHED`；`UsbManager.openAccessory()` → `FileInputStream/FileOutputStream`（参照 `CP/MainActivity.java:238-262`）。
- 按外部头部流式读取，按 `chan` 分发，CMD 走内部 8 字节解析。
- 回复步骤 2、5、6、8、9；收到 `START` 启动编码器（MediaCodec、Surface、H.264）并向 VIDEO 发帧；至少每 ~5 秒一条消息（帧或心跳）。
- 触控：从 `TOUCH_ACTION`（x、y 为 INIT_DONE 帧的像素）生成 Android Auto 的事件（或给显示 AA 的主机）。INIT_DONE 填什么尺寸，在集成 AA 时决定。
- 只用一个线程写：头部 + 消息体连续写，不同通道消息不交错。

---

## 10. 未决问题与风险

1. **其他车机上的 AUTHEN。** 开源代码中，无论 Baidu HU（Java）还是 MD（CarProjection）都不校验签名。DOC 中的方案（随机字符串、MD 侧签名）在无第三方密钥时无法复现。已验证的 Honda Vezel 2024 车机不发 `HU_AUTHEN_REQUEST` 并接受“假的”`MD_AUTHEN_RESULT{true}`；其他车机可能不一样。[推测]
2. **真实车机的协议版本。** 源码是 1.0 版（2015-2017）；`icar` 指向 CarLife 8.4.2。新版本可能有别的字段、强制加密（`CONTENT_ENCRYPTION`）、额外消息。我们不知道车机会在 `HU_FEATURE_CONFIG_RESPONSE` 里声明什么。[推测]
3. **车机上的 AOA 字符串。** 我们应用的 filter 是 `Baidu/CarLife`；如果车机发其他字符串（OEM 变体），应用不会收到 `USB_ACCESSORY_ATTACHED`。需要抓包。[推测]
4. **AOA 字符串中的 NUL 结尾。** Baidu 发的不带 NUL（§1.1）。车机 host 实际怎么做、Android 怎么处理，取决于内核；PC 测试照抄 Baidu。如果手机不接受，再试带 NUL 的。[推测]
5. **transfer 边界。** 关键：头部用独立 transfer 发送，消息体在 ≤ 16 KiB 的单个 transfer 中（§1.4）。对车机作为 host 的情况，验证真实车机是否也这么做（按 Baidu 代码是这么做的）。我们的 MD 要流式读取，不能依赖一次 `read` = 一条消息。[推测，内核外部知识]
6. **Windows/WinUSB/libusb。** PC 测试时，普通模式下的手机需要绑定 WinUSB（Zadig）；切换成 18D1:2D0x 后它作为新设备出现，需要重新绑定 WinUSB 驱动；`claimInterface(0)`。开源资料里没有相关信息。[推测]
7. **MD 发来的帧大小。** 真实 Baidu HU 接受约 3.1 MB 的帧；Honda 车机的硬件解码器可能在 profile/level 和 reorder 帧数上更严格。从 Baseline、720p、无 B 帧、fps 30、码率 3-4 Mbps 开始。[推测]
8. **SPS/PPS 是每个 IDR 都带还是只带第一帧。** Java-HU 代码期望第一帧带 SPS+PPS、重置后是“裸”IDR（§5.2）。真实 Honda HU 的行为可能不同。做成可配置。[推测]
9. **心跳。** 真实 MD 大概会周期性发 `VIDEO_HEARTBEAT`（周期未知）。画面静止且编码器不重复帧时，需要发心跳或强制发帧。[推测]
10. **`CAR_DATA_SUBSCRIBE_DONE` 代码不一致（C++ 中 0x00010032 vs Java 中 0x00018032）** 及 `CAR_DATA_START` 的消息体类型（列表 vs 单个）。只影响车辆数据（速度/GPS），不影响视频和触摸。[已确认，两个值都在代码中]
11. **车机的触摸模式。** `TRANSPARENT_SEND_TOUCH_EVENT` 模式（单个 `TOUCH_ACTION` vs 一组手势消息）由车机配置决定；两种都要接受。多点触控：最多两根手指，通过 `action>>8` 和 pointerx/pointery（§6.1.1）；某车机是否发它，只能看日志。[已确认/推测]
12. **声音。** 如果车机处于 BT 音频模式（`AUDIO_TRANSMISSION_MODE`），USB 传入的 PCM 会被忽略，声音得走 A2DP。[HU 标志已确认，值为推测]
13. **`MD_INFO.sdk_int`。** `sdk_int < 21` 时 Java-HU 显示“不支持 AOA”（§4.4）；填手机真实 SDK。[已确认]
14. **`icar` 与 `CarLife-MD-Lib`。** 对 MD 侧来说它们不是可信来源（`icar` 无代码，`MDL` 无 MD 命令）。MD 逻辑的唯一范本是 `CarProjection`，而且它还是删减版（无 feature config、无心跳、无 FRAME_RATE_CHANGE、无加密、无多媒体元数据）。[已确认]