# Android Auto：carlife-aa 的车机端（HU）侧

本文根据开源代码（headunit-revived、aasdk）、Android for Cars 文档以及对真实手机的观察编写。第一部分（第 1-9 节）写于实现之前，因此许多论断标有 **[推测]**；第 10-11 节描述 `android/` 中已完成的工作。

记号说明。标记 **[已确认]** = 已确认（在代码/文档中读到，给出路径:行号或 URL）。**[推测]** = 猜测/推断，代码或文档中未直接说明。

路径缩写：
- `HUR` = [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived)，`app/src/main/java/com/andrerinas/openheadunit`
- `HUR-PROTO` = 同上，`app/src/main/proto`
- `AASDK` = [opencardev/aasdk](https://github.com/opencardev/aasdk)（[f1xpl/aasdk](https://github.com/f1xpl/aasdk) 的延续）

关于版本的重要说明。Headunit-revived（README、`HUR/connection/self/SelfLauncherManager.kt:122`）将世界划分为 AA < 17.4 和 AA >= 17.4。应用在 AA 17.x 上验证过，即“17.4 起”分支；“17.4 之前”分支在 headunit-revived 代码中称为 LEGACY，走的是向 Gearhead 发 intent 的路径，而非 5277 服务器。但 AA 中的 “Start head unit server” 独立于版本存在并工作（DHU 文档，见第 1.3 节），因此对本项目而言主路径是 5277。**[推测]**（旧版本如 15.2 上 5277 服务器是否存在且行为相同：无直接确认，仅对 “16.4+” 和 “17.xx” 确认过，见第 9 节）。

---

## 1. headunit-revived 如何实现 self mode

### 1.1 两条分支

分支的选择：`HUR/connection/self/SelfLauncherManager.kt:122-137`。按 AA 版本（`getPackageInfo("com.google.android.projection.gearhead").versionName`，解析 major.minor，`SelfLauncherManager.kt:58-71`）**[已确认]**：

| AA | 分支 | 谁连接谁 |
|---|---|---|
| >= 17.4 | `SelfLauncherV17_4`：HU 应用自行打开到 `127.0.0.1:5277` 的 TCP（`HUR/connection/self/launchers/SelfLauncherV17_4.kt:21`） | HU = TCP 客户端，AA “head unit server” = TCP 服务器 **[已确认]** |
| < 17.4 | `SelfLauncherLegacy` + 2 个 fallback：应用在 5288 端口上拉起 TCP 服务器并向 AA 发 intent，让 Gearhead 自行连接 127.0.0.1:5288（`SelfLauncherManager.kt:129-137`） | HU = TCP 服务器，AA = 客户端 **[已确认]** |

5277 在旧版本上也可用的确认（Android 10 + AA “17.xx” 的讨论中，作者们正是推荐用该服务器绕过 AA 16.4+ 的 intent 封锁）：https://github.com/andreknieriem/open-headunit/discussions/533 **[已确认]**（同处：“Both methods were confirmed working”）。

### 1.2 5277 分支（carlife-aa 推荐）

- 用户手动操作：AA -> 设置 -> 连续点按“版本”10 次 -> “三点”菜单 -> “Start head unit server”。出现常驻通知（AA 的 foreground service）。来源：headunit-revived README（“3. Headunit Server”一节）、wiki https://github.com/andreknieriem/open-headunit/wiki/Troubleshooting、DHU 文档 https://developer.android.com/training/cars/testing/dhu **[已确认]**。
- 默认端口 5277，由 DHU 文档（`adb forward tcp:5277 tcp:5277`）和代码 `HUR/automation/AutomationCommandPolicy.kt:53-54`（`HEADUNIT_SERVER_PORT = 5277`）确认 **[已确认]**。
- 无需任何 Gearhead 的 intent/组件来启动投影：应用执行 `Socket.connect(127.0.0.1:5277, timeout 5000)`（`HUR/connection/projection/SocketProjectionConnection.kt:174-177`），loopback 不绑定网络（`LoopbackBindPolicy.kt:19-33`、`SocketProjectionConnection.kt:75`）**[已确认]**。只需要 `INTERNET` 权限（`AndroidManifest.xml:66`）。
- AA 服务器本身不讲 AAP：“它是代理，不是 AAP 服务器：接受 socket，通过 binder 转交给自己的 car service，然后无超时等待。Accept 循环是串行的：只要一个连接挂着，后面所有连接都停在 backlog 里，既不被服务也不被关闭”（`HUR/connection/UnresponsivePeerPolicy.kt:6-11`）**[已确认]**。后果：（a）服务器一生只服务一个连接，不能“试连再关”；（b）如果上一次会话未干净关闭就断开，服务器会卡死（`HUR/connection/TeardownGuard.kt:6`、`HUR/connection/wifi/LinkLossTeardownPolicy.kt:20`），只能手动 Stop/Start 服务器来恢复，有时需要 Force stop AA（`HUR/aap/AapTransport.kt:1041-1050`）**[已确认]**。
- 服务器绑定 loopback（README：“or on the same device (Self-Mode)”）；也可通过手机 Wi-Fi IP 供外部车机访问（README、wiki）。**[已确认]**
- headunit-revived 无法从第三方应用以编程方式启动服务器：在 5277 分支上，应用只是弹 toast“请在开发者设置中启动 Headunit Server”并打开 `com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity`（`SelfLauncherV17_4.kt:17-25`、`SelfLauncherManager.kt:73-94`）**[已确认]**。我们的应用曾通过无障碍服务尝试自动启动，后来移除了（见第 10 节），服务器由用户手动开启。

### 1.3 LEGACY 分支（AA < 17.4，我们不使用）

步骤（`HUR/connection/self/launchers/SelfLauncherLegacy.kt:52-75`）**[已确认]**：
1. 拉起 `WirelessServer`：在 `0.0.0.0:5288` 上开 `ServerSocket`，reuseAddress，3 次 bind 尝试、每次间隔 700 毫秒（`HUR/connection/wifi/server/WirelessServer.kt:100-114`）。self mode 下无需 NSD 注册 `_aawireless._tcp`（`SelfLauncherLegacy.kt:52-63`）。
2. 等待活动网络（最多 1 秒）（`SelfLauncherLegacy.kt:60-64`）。没有网络的车机会创建一个虚拟 VPN 接口（`HUR/../github/.../DummyVpnService.kt:12-47`：地址 10.0.0.2/24，路由 0.0.0.0/0），确保 `ConnectivityManager.activeNetwork != null` **[已确认]**。普通手机有 Wi-Fi/移动网络时，这一步多半不需要 **[推测]**。
3. 用显式 intent 启动 Gearhead 活动（`SelfLauncherLegacy.kt:29-43`）：
   - 组件：包名 `com.google.android.projection.gearhead`，类 `com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity`，flag `FLAG_ACTIVITY_NEW_TASK`；
   - extras：`PARAM_HOST_ADDRESS="127.0.0.1"`（String），`PARAM_SERVICE_PORT=5288`（int），`PARAM_SERVICE_WIFI_NETWORK` = `android.net.Network`（活动网络，或从 `Parcel` 构造的 netId 0 的假网络，`SelfLauncherServices.kt:19-32`），`wifi_info` = 反射构造的 SSID 为 `"Headunit-Fake-Wifi"` 的 `WifiInfo`（`SelfLauncherServices.kt:35-48`）。
4. Gearhead 自行连接 127.0.0.1:5288；`WirelessServer` 把接受的 socket 交给 `CommManager.connect(socket)`（`WirelessServer.kt:129-146`）。实测：Gearhead 耗时 15-20 秒（UNISOC 上），应用 deadline 为 30 秒（`SelfLaunchTimeoutPolicy.kt:34`）**[已确认]**。
5. Fallback 1（仅 API >= 30）：向 `com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver` 发 broadcast，action `...wirelessstartup.START`，extras 为 `ip_address`、`projection_port=5288`、`PARAM_SERVICE_WIFI_NETWORK`、`wifi_info`（`SelfLauncherBroadcast.kt:24-31`）。Fallback 2：向 `com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver` 发 broadcast，action `com.google.android.projection.gearhead.START_WIRELESS_PROJECTION`，extra `DEVICE_ADDRESS`=MAC（`SelfLauncherBTDiscovery.kt:29-37`）**[已确认]**。
6. 需要 AA 是系统应用/更新过的系统应用（`FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP`），否则应用会报错（`HUR/connection/self/SelfLaunchResolveHelper.kt:49-57, 83-105`），另见 https://github.com/andreknieriem/open-headunit/issues/994（CN HyperOS，AA 非系统应用）**[已确认]**。在 Android 10 及更低版本上，AA >= 16.4 会封锁启动（README，Known Issues 一节）。
7. 事先会检查权限：`com.google.android.projection.gearhead.companion.RequestManifestPermissionsActivity`（`HUR/connection/self/AAPermissionTrampolineActivity.kt:48-52`）**[已确认]**。

README 明确写道：“AA 17.4+ 会破坏几乎所有第三方触发器，包括 Self-Mode”（headunit-revived README，NOTE 块）**[已确认]**。“LEGACY 在 15.2/Android 16 上是否也失效”需单独讨论：**[推测]**：Android 16 上后台启动其他应用的活动（BAL）限制更严；headunit-revived 为此持有 `SYSTEM_ALERT_WINDOW`（`AndroidManifest.xml:63`）并从 foreground service 启动，但在 Android 16 上这一点并未在源码中验证过。

### 1.4 是否需要 developer mode 和“车机服务器”？

- 5277 分支：需要，且必须手动开启（第 1.2 节）**[已确认]**。
- LEGACY：不需要 developer mode（走 `WirelessStartupActivity` 的 intent）**[已确认]**，但在 >= 17.4 及 Android <= 10 + AA >= 16.4 上会失效 **[已确认]**。

### 1.5 Android 14+/16 的限制（我们应用这一侧）

- Foreground service：headunit-revived 持有 `AapService`，`foregroundServiceType="connectedDevice|mediaPlayback|microphone"`（`AndroidManifest.xml:251-255`），运行时声明 `CONNECTED_DEVICE | MEDIA_PLAYBACK`（`HUR/app/ForegroundServiceTypePolicy.kt:28-33`），麦克风仅在真正开始采集时才加入：从 Android 14 起 mic 是 while-in-use 类型，即使已授予 RECORD_AUDIO，从后台启动也会被拒绝（`ForegroundServiceTypePolicy.kt:9-19`）**[已确认]**。
- 要使用 `connectedDevice`，至少需要以下之一：`CHANGE_NETWORK_STATE`、`CHANGE_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`NFC`、`TRANSMIT_IR`、运行时 `BLUETOOTH_*`/`UWB_RANGING`，或 `UsbManager.requestPermission()`；再加 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_CONNECTED_DEVICE`（https://developer.android.com/develop/background-work/services/fgs/service-types）**[已确认]**。对我们适用的是 `CHANGE_NETWORK_STATE`（normal 权限）。`mediaPlayback` 同理需要 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`。从 Android 15 起，不能从 `BOOT_COMPLETED` 启动 mediaPlayback/dataSync 类型的 FGS（同上）**[已确认]**。
- Mic 的 FGS 和 `RECORD_AUDIO`：仅当我们要把车机麦克风的声音发给 AA 时才需要（见第 6 节）**[推测]**：第一版“视频 + 触控”不需要。
- Local Network Permission（`ACCESS_LOCAL_NETWORK`）：targetSdk 37（Android 17）的应用访问 LAN 时必需（https://developer.android.com/privacy-and-security/local-network-permission）**[已确认]**；loopback 127.0.0.1 是否豁免未直接查到 **[推测]**（大概率没问题；Android 16 且 targetSdk < 37 不强制）。
- headunit-revived 在 manifest 中声明且与我们相关的权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`FOREGROUND_SERVICE*`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`RECORD_AUDIO`/`FOREGROUND_SERVICE_MICROPHONE`（如果用麦克风）（`AndroidManifest.xml:55-88`）。Package visibility：`<queries><package android:name="com.google.android.projection.gearhead"/>`（`AndroidManifest.xml:14`）供 `getPackageInfo` 查询 Gearhead（判断 AA 版本）用 **[已确认]**。

---

## 2. AAP 传输与分帧

### 2.1 帧（frame）

TCP socket 上收发两方向的格式 **[已确认]**：

```
byte 0     channel id
byte 1     flags (битовое поле)
bytes 2-3  длина тела кадра, big-endian uint16 (без заголовка)
[bytes 4-7 только у FIRST-фрагмента НЕ-bulk сообщения: общий размер сообщения, big-endian uint32]
тело       payload (после рукопожатия: один TLS-record)
```

来源：`HUR/aap/AapMessage.kt:106`（HEADER_SIZE=4）、`HUR/aap/AapMessageIncoming.kt:11-31`（decode：chan=buf[0]，flags=buf[1]，enc_len = BE16 buf[2..3]）、`HUR/aap/AapReadSingleMessage.kt:63-83`（当 `flags==0x09` 时多读 4 字节 total）、`AASDK/src/Messenger/FrameSize.cpp:35-46,73-75`（SHORT = 2 字节，EXTENDED = 6 字节：2 帧长 + 4 总长）。

标志位（`AASDK/include/aasdk/Messenger/FrameType.hpp`、`EncryptionType.hpp`、`MessageType.hpp`；`HUR/aap/AapMessageFraming.kt:47-67`）：

| 位 | 含义 |
|---|---|
| 0x01 | FIRST（帧打开一条消息） |
| 0x02 | LAST（帧结束一条消息） |
| 0x04 | CONTROL（消息类型为“通用/control”，位于非 control 通道上） |
| 0x08 | ENCRYPTED（内容走 TLS） |

典型取值：`0x03` = 明文 bulk（版本、SSL 握手、auth）；`0x0b` = 加密 bulk（普通消息）；`0x0f` = 加密 + control 标志（非 control 通道上的 control 类型 1..26，`AapMessage.kt:112-118`）；入站分片：`0x09` first、`0x08` middle、`0x0a` last（加密，`AapMessageFraming.kt:9-14`）**[已确认]**。

我们的场景中 HU 发出的消息总是很小，无需分片 **[推测]**（headunit-revived 不分片：`AapTransport.kt:669-689` 加密后一个帧发出去）。入站视频按 16 KB 分片（`AapMessageFraming.kt:33-35`）**[已确认]**。

加密帧的“长度”字段 = TLS-record 长度（wrap 之后）。明文帧 = `payload 大小 + 2`（类型），见 `Messages.createRawMessage`（`HUR/aap/protocol/messages/Messages.kt:20-32`：`[chan][flags][len=size+2 BE][type BE16][data]`）**[已确认]**。

消息体内部（解密后）：`[type BE16][protobuf]`；media DATA 为：`[type=0][timestamp u64 BE µs][数据]`（`AudioMediaPayload.kt:5-28`，视频：`VideoFragmentAssembler.kt:221-225`：带 timestamp 的 DATA 偏移 10，CODEC_CONFIG 偏移 2）**[已确认]**。

组包：socket 每条消息一次阻塞读取（`AapRead.kt:190-213`），因为 TCP 上的帧只能按长度拼接；按长度 `readFully`（`AapReadSingleMessage.kt:99`）**[已确认]**。

### 2.2 协议版本

HU 先发 Version Request（`Messages.kt:8-9,18`）：`00 03 00 06 00 01 00 01 00 02`（chan 0、flags 0x03、len 6、type 0x0001、major=1、minor=2）**[已确认]**。最多连续尝试 3 次，每次等响应 2 秒，总预算 10 秒（`AapTransport.kt:958-1037`、`HANDSHAKE_TIMEOUT_MS=10_000` :1209）**[已确认]**。

手机响应：`chan 0, flags 0x03, type 0x0002, major(2) minor(2) status(2)` + 可选的 `VersionResponseOptions`（含 ping timeout 的 ConnectionConfiguration 等）（`HUR/aap/AapVersionNegotiation.kt:74-127`；`HUR-PROTO/control.proto:291-302`）**[已确认]**。minor 由手机自己选；“1.6+”的消息在选了更低版本时不能发（`AapVersionNegotiation.kt:87-88`）**[已确认]**。AA 具体选哪个版本：**[推测]**（headunit-revived 日志里典型为 “1.7”；代码中未固定）。

如果 5277 上整个期间一个字节都没收到，说明服务器“卡在”上一次会话上了（`AapTransport.kt:1039-1051`、`HandshakeFailure.PEER_SILENT`）**[已确认]**。

### 2.3 帧之上的 TLS

- HU = TLS 客户端（`sslEngine.useClientMode = true`，`HUR/aap/AapSslContext.kt:157-158`），通过 `SSLEngine`（有 Conscrypt 用 Conscrypt，否则用系统 JSSE；`createSslContext`、`AapSslContext.kt:393-413`），`createSSLEngine("android-auto", 5277)` **[已确认]**。关闭对手机证书的校验（`NoCheckTrustManager`、`HUR/ssl/NoCheckTrustManager.kt:7-17`；`AapSslContext.kt:410`）**[已确认]**。
- 握手：按 `getHandshakeStatus()` 循环。NEED_WRAP：`wrap(emptyArray())` -> 作为明文帧发出 `[chan 0][flags 0x03][len][type 0x0003 ENCAPSULATED_SSL][TLS bytes]`（`AapSslContext.kt:76-83`、`createRawMessage(0, 3, 3, …)`）。NEED_UNWRAP：读明文帧（头部 6 字节，然后 payload；payloadLength = len-2），把字节喂给 `unwrap`（`AapSslContext.kt:123-151`）。一条 AAP 消息可能只包含不完整的 record，此时累积到足够大小（`BUFFER_UNDERFLOW`，:62-68）。握手上限 15 秒（`SSL_HANDSHAKE_TIMEOUT_MS`，:391）**[已确认]**。
- 握手之后：`ssl.postHandshakeReset()`，HU 发明文帧 **Auth Complete**：`00 03 00 04 00 04 08 00` = chan 0、flags 0x03、len 4、type 0x0004、protobuf `AuthResponse{status=0 (OK)}`（`Messages.kt:11-13`、`AapTransport.kt:1062-1069`）**[已确认]**。
- 此后所有帧都带 `ENCRYPTED(0x08)`：消息体 = `sslEngine.wrap` 的结果（每条消息一个 record）：`encrypt` 在 `AapSslContext.kt:349-369`，头部组装在 `AapTransport.kt:669-689`（`data[0]`、`data[1]` 取自原始消息，长度 = 密文大小）。接收：对每个帧执行 `sslEngine.unwrap`（`AapSslContext.kt:271-347`）**[已确认]**。TLS 会话在连接之间复用（JSSE session cache，`AapSslContext.kt:411-412`）**[已确认]**。

### 2.4 HU 的证书/密钥

HU 出示客户端证书：“Google Automotive Link”公共证书（JVC Kenwood 的），与密钥分离，以明文形式存放在仓库中：
- headunit-revived：`app/src/main/res/raw/cert`（PEM，subject：O="Google Automotive Link"，有效期 2014-07-08 .. 2044-07-07）和 `.../raw/privkey`（PKCS#8 RSA 2048）。加载：`HUR/ssl/SingleKeyKeyManager.kt:85-103`（`R.raw.cert`、`R.raw.privkey`；RSA `PKCS8EncodedKeySpec`），客户端别名的 `KeyManager`：`chooseClientAlias`/`chooseEngineClientAlias`（:69-76）**[已确认]**。
- aasdk（opencardev/aasdk）：`src/Messenger/Cryptor.cpp:253-299`（内置 PEM，“RSA PRIVATE KEY”）和 `cert/headunit.crt`、`cert/headunit.key`。这是另一张证书（JVC Kenwood，serial 0x1b）vs headunit-revived（serial 0x7653）；两者 AA 都接受。aasdk 的 `cert/README.md`：“publicly available certificate used in Android Auto head units” **[已确认]**。
- 取两者任一即可；建议取 headunit-revived 的 `res/raw`，因为它已在 Android 客户端（JSSE/Conscrypt）上验证过 **[推测]**。
- 许可证：headunit-revived 是 AGPL；把代码拷进项目会连带 AGPL。证书和密钥作为数据是公开的。许可证决定请自行权衡。

---

## 3. Service Discovery

Auth Complete 之后，手机发送 `ServiceDiscoveryRequest`（control，type 0x0005；字段 `phone_name`=4、`phone_brand`=5），HU 回复 `ServiceDiscoveryResponse`（type 0x0006）（`HUR/aap/AapControl.kt:340-346`、`HUR-PROTO/control.proto:230-258`）**[已确认]**。

通道 id 由 HU 自己在 `Service.id` 中指定（aasdk 甚至称之为 “dynamic”，`AASDK/include/aasdk/Messenger/ChannelId.hpp:5-10`）；headunit-revived 的具体编号（`HUR/aap/protocol/Channel.kt:5-18`）对 AA 有效 **[已确认]**：CONTROL=0、SENSOR=1、VIDEO=2、INPUT=3、AUDIO_SPEECH(AU1)=4、AUDIO_SYSTEM(AU2)=5、AUDIO_MEDIA(AUD)=6、MIC=7、BLUETOOTH=8、MEDIA_PLAYBACK=9、NAVIGATION=10（aasdk 的编号不同，这不是标准）**[已确认]**。

### 3.1 根字段（headunit-revived 的默认值）

（`HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt:299-337`；默认值 `HUR/utils/Settings.kt:921-960`）**[已确认]**

| 字段 | 值 |
|---|---|
| make / model | `"Google"` / `"Desktop Head Unit"` |
| year | `"2025"` |
| vehicle_id | `"headlessunit-001"`（摩托车附加后缀 `-moto`，`HUR/aap/VehicleIdentityPolicy.kt:16-20`） |
| driver_position | `DRIVER_POSITION_LEFT` (0) |
| head_unit_make / model | `"Google"` / `"Desktop Head Unit"` |
| head_unit_software_build / version | `"1"` / `"0.1.0"` |
| can_play_native_media_during_vr | `false` |
| hide_projected_clock | false；session_configuration（bit 0x01 时钟、0x02 信号、0x04 电量） |
| display_name | `"Open Headunit"` |
| headunit_info（字段 17） | 重复 make/model/year/id/hu_make/hu_model/sw + `vehicle_type`（`ServiceDiscoveryResponse.kt:314-328`） |
| connection_configuration | 可选：ping timeout 15000 毫秒、socket read timeout 15000 毫秒、缓冲区 65536（`HUR/aap/ConnectionConfigPolicy.kt:14-40`） |

重要（**[已确认]**，`ServiceDiscoveryResponse.kt:212-223`、`VehicleTypePolicy.kt:1-34`）：如果 HU 不声明麦克风服务（`ID_MIC`），手机会以 “No audio/mic” 中断 setup，**除非** HU 把自己声明为摩托车（`vehicle_type = 3`、`vehicle_id` 加 `-moto`）。摩托车时手机会用自己的麦克风。对 carlife-aa 第一版：要么声明 `MOTORCYCLE`，要么声明 mic 服务（16 kHz/16 位/单声道）并转发车机麦克风的真实声音。

### 3.2 服务（最小可用集合）

由 headunit-revived 代码确认（`ServiceDiscoveryResponse.kt`）**[已确认]**：

1. SENSOR（id 1），`sensor_source_service`：`DRIVING_STATUS` (13) 必填；`NIGHT` (10)（headunit-revived 总是声明，:44）；`LOCATION` (1) 仅在 GPS 开启时（:39）。`makeSensorType` :457。
2. VIDEO（id 2），`media_sink_service`（:52-137）：
   - `available_type = MEDIA_CODEC_VIDEO_H264_BP` (3)、`audio_type = NONE (0)`、`available_while_in_call = true`；
   - `video_configs[0]`：`codec_resolution = _1280x720 (2)`、`frame_rate = _60 (1)` 或 `_30 (2)`、`density`（DPI）、`margin_width`、`margin_height`、`pixel_aspect_ratio_e4`（10000 = 方形像素）、`video_codec_type = H264_BP (3)`。
   - 分辨率枚举（`control.proto:82-93`）：1=800x480、2=1280x720、3=1920x1080、4=2560x1440、5=3840x2160、6=720x1280、7=1080x1920、8=1440x2560、9=2160x3840。“720p”的帧 = **1280x720**。DHU 文档确认 480p/720p/1080p 受支持（https://developer.android.com/training/cars/testing/dhu）**[已确认]**。
   - margins：只要帧的宽高比与屏幕一致，就不影响车机屏幕尺寸。margin 为零 = AA 全帧绘制。headunit-revived 在 `HUR/utils/HeadUnitScreenConfig.kt:620-631` 和 `ProjectionGeometryPolicy.kt:49-57` 中计算（`margin = max(0, (adjusted_video - panel)/scale)`；PAR 模式下 margin=0）**[已确认]**。对 16:9 车机（或 1280x720 无 letterbox 的帧），用 margin=0/0、density 160..240 **[推测]**（让界面看起来正常的 density 值需现场调试；headunit-revived 取屏幕真实的 `densityDpi`，`HeadUnitScreenConfig.kt:644-650`；README：“Google Maps portrait: DPI < 200”）。
3. INPUT（id 3），`input_source_service`（:141-161）：`touchscreen = TouchConfig{width=1280, height=720}`（= 帧尺寸，不是屏幕尺寸）、`keycodes_supported`（`HUR/input/KeyCode` 的列表）、`touchpad` 仅在需要旋钮时。触摸尺寸 = 协商后的帧宽高（`ServiceDiscoveryResponse.kt:144-147`）**[已确认]**。
4. AUDIO（`media_sink_service`、`available_type = MEDIA_CODEC_AUDIO_PCM (1)`、`audio_type`、`audio_configs`）（:163-211；配置 `HUR/aap/protocol/AudioConfigs.kt:49-72`）**[已确认]**：

   | 通道 | id | `audio_type` | 格式 |
   |---|---|---|---|
   | SYSTEM（系统声音） | 5 | `SYSTEM (2)` | 16000 Hz、16 位、1 声道 |
   | SPEECH（导航提示/助手） | 4 | `SPEECH (1)` | 16000 Hz、16 位、1 声道 |
   | MEDIA | 6 | `MEDIA (3)` | 48000 Hz、16 位、2 声道 |

   - SYSTEM 通道总是声明（“keep connection alive”，`ServiceDiscoveryResponse.kt:164`）；16 kHz 单声道配置 “required”：否则手机端 endpoint 列表为空并报 “No audio/mic”（`AudioConfigs.kt:64-66`）**[已确认]**。
   - **self mode 的细节：** loopback 会话（`commManager.isLoopbackSession`）下 headunit-revived **不声明** SPEECH 和 MEDIA，只声明 SYSTEM（`ServiceDiscoveryResponse.kt:179-211`、`AudioSinkAnnouncementPolicy.kt:90-91`），仓库里没写原因（“Why Self Mode drops them at all is not recorded ... and is not settled”）**[已确认]**；在 #533 讨论中用户确认：audio sink 关闭时声音从手机自己出。**这对 carlife-aa 很关键**：AA 的声音恐怕得另想办法接管，或者验证声明了这些服务后 AA 在 loopback 上到底会不会发 media/speech。**[推测]**
   - 手机端校验器只接受 {16000, 48000} Hz、16 位、1 或 2 声道的音频配置（`ServiceDiscoveryResponse.kt:218-222` 的注释）**[已确认]**。
5. MIC（id 7），`media_source_service{type=PCM, audio_config{16000,16,1}}`（:224-236）仅在 HU 真的录音时声明；否则声明为摩托车（见上文）**[已确认]**。
6. Bluetooth（id 8）：仅当设置了 MAC 时才声明（`ServiceDiscoveryResponse.kt:246-260`）；不声明则 AA 把通话留在手机上。最小集**不需要** **[已确认]**（:261-267 的注释）。
7. MEDIA_PLAYBACK（id 9）和 NAVIGATION（id 10）（`navigation_status_service{min_interval 1000, ImageCodesOnly}`）headunit-revived 总是声明（:270-284）。对最小“视频+触控”会话 **[推测]**：非必需；未验证 AA 缺了它们能否启动。
8. headunit-revived 没有也不需要：radio、phone_status、vendor_extension、generic_notification、wifi_projection。

---

## 4. 视频

数据流（全部走 VIDEO=2 通道）**[已确认]**：

1. Phone -> HU：在目标通道上发 `ChannelOpenRequest`（control type 0x0007、`priority`、`service_id`）。HU 在**同一**通道回复 `ChannelOpenResponse{status=STATUS_SUCCESS}`（type 0x0008）；传感器通道上还会立刻发 `DrivingStatus UNRESTRICTED`（`HUR/aap/AapControl.kt:477-502`）**[已确认]**。
2. Phone -> HU：`MediaSetupRequest`（type 0x8000，字段 `type`=3=H264）（`media.proto` 的 `MediaSetupRequest`、`AapControl.kt:39-41`）。HU 回复 `Config{status=STATUS_READY(2), max_unacked, configuration_indices=[0]}` type 0x8003（`AapControl.kt:103-116`）**[已确认]**。headunit-revived 视频的 `max_unacked`：Wi-Fi/socket 为 12，USB 为 16（软 HEVC 为 6/8）（`AapControl.kt:136-156`），音频为 30（socket）**[已确认]**。
3. HU -> Phone：`VideoFocusNotification{mode=VIDEO_FOCUS_PROJECTED(1), unsolicited}` type 0x8008（`HUR/aap/protocol/messages/VideoFocusEvent.kt:10-20`）。headunit-revived 在 Surface/活动就绪时发送（Config 之后 `gainVideoFocus`：`AapControl.kt:118-120`；`AapProjectionActivity.kt:175,289,345,437,2043-2046`），重复/启动时为 `unsolicited=true`，响应请求时为 `false` **[已确认]**。据逻辑判断，没有这条消息 AA 不会启动视频 **[推测]**（未做直接测试）。
4. Phone -> HU：`Start{session_id, configuration_index=0}`（type 0x8001）（`AapControl.kt:94-101`、`media.proto` 的 `Start`）。HU 在通道上记住 `session_id`（`AapTransport.setSessionId` :1161），并在所有 Ack 中使用 **[已确认]**。
5. Phone -> HU：`CODEC_CONFIG`（type 0x0001）：`[type 2B][SPS/PPS Annex-B]`，随后 `DATA`（type 0x0000）：`[type 2B][timestamp u64 BE µs][Annex-B access unit]`。起始码 `00 00 01`/`00 00 00 01`（`HUR/aap/AapVideo.kt:219-232`）。payload 偏移 10（带 timestamp 的 DATA）或 2（CODEC_CONFIG/不带 timestamp）（`HUR/decoder/video/VideoFragmentAssembler.kt:221-225`）。这就是现成的 Annex-B，可以直接喂给 MediaCodec/CarLife 编码通道而无需转换 **[已确认]**（如果 CarLife 侧期望带 SPS/PPS 的 IDR Annex-B；否则需要在关键帧前补 SPS/PPS **[推测]**）。
6. 分片：DATA > ~16 KB 时按 FIRST(0x09，+4 字节 total size) + MIDDLE(0x08)* + LAST(0x0a) 到达；按 `flags` 组包（`AapVideo.kt:282-358`、`VideoFragmentAssembler.kt:210-219`：FLAG_SINGLE=11、FIRST=9、MIDDLE=8、LAST=10）。headunit-revived 组包缓冲区上限：131080*64 字节（`AapVideo.kt:20`）**[已确认]**。
7. Ack：对每个收到的视频消息（DATA 和 CODEC_CONFIG；按实现，ack 是针对开头 `type` 为 0 或 1 的消息，即实际按第一个分片或整条消息，`AapTransport.kt:798-852`）HU 在 VIDEO 通道发 `Media.Ack{session_id, ack=1}` type 0x8004（`HUR/aap/protocol/messages/MediaAck.kt:7-18`、`AapTransport.kt:1157-1159`）。Ack 在**处理之后**（让窗口控制速度）而非收到时发出（`AapTransport.kt:770-795` 注释）**[已确认]**。不发 ack 的话，`max_unacked` 窗口会堵死、画面停住 **[已确认]**。
8. 按需关键帧：代码里没有专门的 “request IDR” 消息；headunit-revived 重发 `VideoFocusNotification`（focus cycle：先 NATIVE gain=false，再 PROJECTED）（`AapTransport.kt:379-441`、`AapVideo.kt:97-109`）**[已确认]**。对我们：如果 CarLife 解码器丢帧，就做同样的事 **[推测]**（AA 15.2 的行为未验证）。
9. Phone -> HU `VideoFocusRequestNotification{mode, reason}`（type 0x8007）：`VIDEO_FOCUS_NATIVE` 表示“用户在 AA 里按了 Exit/Home”，需要显示原生 UI（`AapControl.kt:48-73`）**[已确认]**。要正确退出必须响应 native 请求：`NATIVE` 时 HU 应返回原生画面/关闭会话 **[推测]**。
10. 视频通道上的 `Stop` (0x8002)：后台/切换；headunit-revived 在自己的 focus-cycle 中忽略它（`AapControl.kt:158-178`）**[已确认]**。

---

## 5. 触控

INPUT 通道（id 3）**[已确认]**：

1. ChannelOpen 之后手机发送 `KeyBindingRequest`（type 0x8002、`keycodes` 列表），HU 回复 `BindingResponse{status=STATUS_SUCCESS}` type 0x8003（`AapControl.kt:216-237`、`input.proto`）**[已确认]**。没有它输入不工作 **[推测]**。
2. 事件：type `0x8001`（`Input.MsgType.EVENT`），proto `InputReport`（`HUR-PROTO/input.proto:28-78`）：
   - `timestamp`（uint64，**纳秒**）：headunit-revived 取 `SystemClock.elapsedRealtime()`（毫秒）* 1_000_000（`HUR/aap/protocol/messages/TouchEvent.kt:48`）；
   - `touch_event`（字段 3）= `TouchEvent{ repeated Pointer pointer_data (1), uint32 action_index (2), PointerAction action (3) }`；
   - `Pointer{ x=1, y=2, pointer_id=3 }`（uint32）；
   - `PointerAction`：DOWN=0、UP=1、MOVE=2、CANCEL=3、OUTSIDE=4、POINTER_DOWN=5、POINTER_UP=6（`input.proto:30-38`）。与 Android 对应：直接对应 `MotionEvent.ACTION_*`（`TouchEvent.kt:19-30`）。
3. 坐标：TouchConfig 声明的**帧**像素（即 720p 为 1280x720），范围 0 到 width/height 含端点；`FILL` 且 margin 为零时：`x = rawX / surfaceW * videoW`、`y = rawY / surfaceH * videoH`，clamp 到 `[0, videoW]`、`[0, videoH]`（`HUR/input/TouchCoordinateMapper.kt:24-72`；调用处 `AapProjectionActivity.kt:2141-2199`）。margin 模式下从宽度中减去 `margin`（uiW = videoW - marginW）**[已确认]**。
4. 多点触控：一条消息包含所有活动指针；`action_index` = 触发 POINTER_DOWN/UP 的指针索引（`AapProjectionActivity.kt:2199`）**[已确认]**。`pointer_id` 取自 `MotionEvent.getPointerId`。
5. 对 CarLife：CarLife 有自己的触控协议（见 [CarProjection](https://github.com/aa112901/CarProjection) 和 [apollo-DuerOS](https://github.com/milesking/apollo-DuerOS)）；网关的任务：取车机屏幕坐标系中的坐标，归一化到 1280x720，按同样顺序发 DOWN/MOVE/UP。单指以 pointer id=0 发出；CarLife 的第二根手指（`action>>8`、pointerx/pointery，见 `carlife-protocol.md` §6.1.1）由桥接层转换为带全部活动指针的 POINTER_DOWN/POINTER_UP，剩余手指的 id 不变（`AaBridge.onCarlifeTouch`）**[推测]**。

---

## 6. 音频

（`HUR/aap/AapAudio.kt`、`HUR/aap/AudioMediaPayload.kt`、`AapControl.kt`）**[已确认]**

- 每个声明的音频服务（通道 4、5、6）走与视频相同的链路：ChannelOpen -> `MediaSetupRequest(type=PCM/AAC)` -> HU `Config{READY, max_unacked(30 by socket), [0]}` -> 手机 `Start{session_id, config_index}` -> `DATA` 流（`[type 0][ts u64 µs][PCM]`）+ 对每条 `DATA` 回 `Ack`（对 `CODEC_CONFIG` 不发 ack，仅 AAC；`AudioMediaPayload.kt:14-27`）。
- Ack：`Media.Ack{session_id（取自该通道的 Start）, ack=1}` 发到同一通道（`AapTransport.kt:1157-1159`；音频的调用 `AapMessageHandlerType.kt:67-78`）。
- PCM 格式：见第 3.2 节：系统/语音 16 kHz/16/单声道，媒体 48 kHz/16/立体声（little-endian signed，AA 就是这么给的：`AudioTrackWrapper` 中的 `AudioTrack` ENCODING_PCM_16BIT；**[推测]** 字节序，但代码里 PCM 解码未做重排）。
- AAC 可以不声明（`Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC`，2.4 GHz 下 headunit-revived 会切换，`NarrowBandProfilePolicy`）；loopback 用 PCM。
- 音频焦点：手机发送 `AudioFocusRequestNotification{request: GAIN=1, GAIN_TRANSIENT=2, GAIN_TRANSIENT_MAY_DUCK=3, RELEASE=4}`（control type 0x0012），HU 立即回复 `AudioFocusNotification{focus_state}`（type 0x0013）：GAIN->`STATE_GAIN(1)`、GAIN_TRANSIENT->`STATE_GAIN_TRANSIENT(2)`、MAY_DUCK->`STATE_GAIN_TRANSIENT_GUIDANCE_ONLY(7)`、RELEASE->`STATE_LOSS(3)`（“总是授予”）（`AapControl.kt:395-436, 450-455`）。音频通道上 `MediaSetupRequest` 之后 HU 自己发 `AudioFocusNotification{STATE_GAIN, unsolicited=true}`（`AapControl.kt:123-131`）**[已确认]**。
- 导航焦点：`NavFocusRequestNotification` -> 回复 `NavFocusNotification{NAV_FOCUS_PROJECTED (2)}`（`AapControl.kt:358-370`）**[已确认]**。
- 麦克风（若声明）：手机在 MIC 通道发 `MicrophoneRequest{open, max_unacked}` (0x8005)；HU 回复 `MicrophoneResponse{status, session_id}` (0x8006) 并发 `DATA`（PCM 16 kHz/16/单声道），手机回 `Ack`（`AapControl.kt:188-206`、`MicrophoneResponse.kt`）**[已确认]**。
- 最小“视频+触控”：可以只声明 SYSTEM sink（headunit-revived 在 loopback 下就是这么做的），接受其 Config/Start 后直接把数据丢弃/倒掉，**别忘了对每条 DATA 回 ack**，否则手机端窗口会堵死 **[推测]**。

---

## 7. Ping、keepalive、shutdown

**[已确认]**
- Ping 由**手机**发起：control type 0x000b `PingRequest{timestamp, bug_report?}`，会话期间约每秒一次（`AapTransport.kt:214-216` 注释）。HU 在相同通道回复 `PingResponse{timestamp}`（type 0x000c）（`AapControl.kt:348-356`：`timestamp = System.nanoTime()`）**[已确认]**。
- HU 不发自己的 Ping（代码里没有）**[已确认]**。超时：手机可在 `VersionResponseOptions` 中给出期望的 ping timeout（`AapTransport.kt:1016-1020`）；HU 可在 `ServiceDiscoveryResponse.connection_configuration` 中给出自己的建议（headunit-revived 默认 15 秒）**[已确认]**。
- TCP：`keepAlive=true`、`tcpNoDelay=true`，读 soTimeout 15 秒等待下一个头部；超时视为会话丢失（`AapReadSingleMessage.kt:28-30,41-44`；`SocketProjectionConnection.kt:273-293`）**[已确认]**。这意味着手机的 ping 必须比 15 秒更频繁地到达（实际约 1 秒一次）。
- HU -> 手机 shutdown：`ByeByeRequest{reason=USER_SELECTION(1)}`（control 0x000f），暂停 150 毫秒，关闭 socket（`AapTransport.kt:698-709`）**[已确认]**。必须关闭 socket：否则 5277 服务器一直显示“占用”（`TeardownGuard.kt:6`）**[已确认]**。
- 手机 -> HU shutdown：手机的 `ByeByeRequest`（reason）：HU 回复 `ByeByeResponse` (0x0010)，等 500 毫秒后关闭（`AapControl.kt:372-382`）**[已确认]**。如果收到 `ByeByeResponse`（HU 先发的），会话结束（`AapControl.kt:318-320`）。
- “Magic garbage”：4+ 字节 0xFF 代替头部 = Wireless Helper 的干净退出（headunit-revived 特有，我们不需要）（`AapReadSingleMessage.kt:56-61`）。
- 分帧错误：control type 0xFFFF `FRAMING_ERROR`、`0x00FF UNEXPECTED_MESSAGE`（`control.proto:37-38`）**[已确认]**。

---

## 8. “720p 视频 + 触控”的最小序列

每个箭头 = 一个 AAP 帧。“pl” = 明文（flags 0x03），“enc” = flags 0x0b（非 control 通道上的 control 类型为 0x0f）。通道编号：headunit-revived 的。

前置条件：手机上开启 developer mode 和 “Start head unit server”（AA 17.x）。应用带 foreground service 和第 1.5 节的权限。

0. TCP 连接 `127.0.0.1:5277`（超时 5 秒）。不要开测试连接，也不要在未握手的情况下关闭。
1. HU -> AA（pl, ch 0）：`VERSION_REQUEST` (1)：`00 03 00 06 00 01 00 01 00 02`。
2. AA -> HU（pl, ch 0）：`VERSION_RESPONSE` (2)：major、minor、status（+options）。检查 `status == 0`。
3. TLS 握手：HU `wrap()` -> `ENCAPSULATED_SSL` (3)（pl），AA -> `ENCAPSULATED_SSL`，如此直到 `FINISHED`（客户端证书用第 2.4 节的，信任任意证书）。
4. HU -> AA（pl, ch 0）：`AUTH_COMPLETE` (4)：`00 03 00 04 00 04 08 00`。
5. AA -> HU（enc, ch 0）：`SERVICE_DISCOVERY_REQUEST` (5)。
6. HU -> AA（enc, ch 0）：`SERVICE_DISCOVERY_RESPONSE` (6)，带服务：SENSOR(1: DRIVING_STATUS、NIGHT)、VIDEO(2: H264_BP、1280x720、fps 30 或 60、margins 0、density N)、INPUT(3: touchscreen 1280x720、keycodes)、AUDIO SYSTEM(5, PCM 16k/16/1)、（可选 SPEECH(4)/MEDIA(6)）；vehicle_type 用 MOTORCYCLE 或声明 MIC(7)。
7. AA 对每个服务在相应通道发 `CHANNEL_OPEN_REQUEST` (7)；HU 回复 `CHANNEL_OPEN_RESPONSE` (8, STATUS_SUCCESS)。通道 1 上，响应之后 HU 立刻发 `SENSOR_EVENT` (0x8003)，带 `DrivingStatus=UNRESTRICTED(0)`。
8. AA -> HU, ch 1：对每个声明的传感器发 `SENSOR_START_REQUEST` (0x8001, 传感器类型)；HU：`SENSOR_START_RESPONSE` (0x8002, STATUS_SUCCESS)；NIGHT 额外发 `SENSOR_EVENT{night=false}`。
9. AA -> HU, ch 3：`KEY_BINDING_REQUEST` (0x8002)；HU：`BINDING_RESPONSE` (0x8003, STATUS_SUCCESS)。
10. AA -> HU, ch 2：`MEDIA_SETUP` (0x8000, type=H264)。HU -> AA：`MEDIA_CONFIG` (0x8003: READY、max_unacked=12、indices=[0])。
11. HU -> AA, ch 2：`VIDEO_FOCUS_NOTIFICATION` (0x8008: PROJECTED、unsolicited=true)。
12. AA -> HU, ch 2：`MEDIA_START` (0x8001: session_id、config_index 0)。记住 session_id。
13. AA -> HU, ch 2：`CODEC_CONFIG`（SPS/PPS）和 `DATA` 流（Annex-B），带分片；HU 按 flags 0x0b / 0x09,0x08*,0x0a 组包。每条消息处理完后 HU 在 ch 2 发 `MEDIA_ACK` (0x8004: session_id、ack=1)。
14. 音频（如果声明了）：通道 4/5/6 上重复步骤 10 和 12，+ Setup 之后发 `AUDIO_FOCUS_NOTIFICATION`（STATE_GAIN、unsolicited）；对每条 DATA 回 Ack。
15. AA -> HU, ch 0：`PING_REQUEST`（约 1 次/秒）-> HU `PING_RESPONSE`。`NAV_FOCUS_REQUEST` -> `NAV_FOCUS_NOTIFICATION(PROJECTED)`。`AUDIO_FOCUS_REQUEST` -> `AUDIO_FOCUS_NOTIFICATION`。`VOICE_SESSION_NOTIFICATION` 可忽略。
16. 触控：HU -> AA, ch 3：`INPUT_REPORT` (0x8001)，带 `TouchEvent`（DOWN/MOVE/UP，坐标为 1280x720 像素）。
17. 退出：HU -> AA `BYEBYE_REQUEST` (15)，150 毫秒，关闭 socket。AA -> HU `BYEBYE_REQUEST` -> 回复 `BYEBYE_RESPONSE` 并关闭。

**未**确认为必需的（需实测）：MEDIA_PLAYBACK(9) 和 NAVIGATION(10) 服务；NIGHT 传感器；loopback 下 SPEECH/MEDIA 音频的存在 **[推测]**。

---

## 9. 风险、版本、替代方案

### 9.1 2025-2026 年 self mode / 车机服务器的状态

- headunit-revived README：AA >= 17.4 上几乎所有第三方触发器（Wireless Helper、intent self mode）都会失效；“Headunit Server”被称为“Self-Mode 仅存的解决方案”（headunit-revived README、NOTE；wiki https://github.com/andreknieriem/open-headunit/wiki/Wireless）**[已确认]**。
- 对 AA 16.4+ 且 Android <= 10：Google 禁用了 intent 启动；绕过方法：127.0.0.1 上的 Headunit Server，已在 “Android 10 with Android Auto 17.xx” 上确认可用（https://github.com/andreknieriem/open-headunit/discussions/533）**[已确认]**。
- 公开/近期的投诉（2026 年 9 月）：#1022 “Self mode not responding on latest android auto”（要求“在 dev settings 里打开再关闭 headunit server”，反复出现；未分析即关闭）https://github.com/andreknieriem/open-headunit/issues/1022；#977 “refuses to work in Self mode”（能到 Welcome 界面然后被弹回；以 “Done/Testing” 关闭）https://github.com/andreknieriem/open-headunit/issues/977；#698 “App is not working after android auto 17.4 release”（wontfix，head unit server 也没救）https://github.com/andreknieriem/open-headunit/issues/698；#994 AA 在 CN HyperOS 上不是系统应用 https://github.com/andreknieriem/open-headunit/issues/994；#929 self mode 下 AA 把 HU 当作活动媒体应用 https://github.com/andreknieriem/open-headunit/issues/929 **[已确认]**（页面转述，评论细节经 WebFetch 不全）。
- 已找到的资料里没有专门针对 Android 16 + 5277 服务器的报告 **[已确认]**（缺数据，不等于确认可用）。我们的应用用 5277 分支，在 AA 17.x 上验证过。
- 服务器已知的脆弱性：只接受一个连接，被不礼貌地断开会挂死（`UnresponsivePeerPolicy.kt`、`TeardownGuard.kt`）；恢复：Stop/Start 服务器，必要时 Force stop AA 并重启手机（`AapTransport.kt:1043-1050`）**[已确认]**。这需要做进 UX（给用户的提示，10/60/300 秒的 backoff）。
- 服务器只能手动启动（见 1.2）**[已确认]**；自动化（ADB wireless debugging、accessibility）未研究 **[推测]**。
- Google 随时可能干掉服务器或改分帧：`VersionRequestOptions` 中的 “snapshot_version” 字段（`control.proto:291-294`）和 `FramingError` 暗示协议在演化；我们的 HU 应当忽略未知字段和消息 **[推测]**。
- self mode 下的视频：同一手机上的 AA 会与手机主 UI 并行渲染 720p 帧；SoC 负载（AA 编码器 + 我们的解码器/ CarLife 转发）**[推测]**：对弱手机会很吃紧；`VideoFocus` 关键帧循环可能造成闪烁。
- 声音：loopback 下 headunit-revived 不声明 MEDIA/SPEECH（原因未定），#533 中 sink 关闭时声音从手机出 **[已确认]**。因此要在车机 CarLife 上出声，需要做实验：声明 media-sink，看看流是否真的过来 **[推测]**；替代方案：单独抓取手机系统声音（`MediaProjection`/`AudioPlaybackCapture`）**[推测]**。

### 9.2 代码中没有或未经确认的

- AA < 16.4 作为 “server” 的行为：无数据；只确认了 16.4/17.x。**[推测]**
- 启动是否必须 NIGHT / MPB / NAV 传感器：未验证。**[推测]**
- input timestamp 字段的精确要求：用的是纳秒（ms*1e6），未经 AA 校验。**[已确认]** 公式，**[推测]** 重要性。
- 许可证：headunit-revived 是 AGPL（不把项目按 AGPL 授权就不能拷代码），aasdk 是 GPLv3（查仓库里的 `LICENSE`）。
- AA 实际选定的协议版本（1.6/1.7？）：文档中未记录。**[推测]**

### 9.3 替代方案

1. **DHU 风格、无 Android-HU**：手机上的 AA -> adb forward 5277 -> PC（DHU）（https://developer.android.com/training/cars/testing/dhu）。与我们的场景无关（需要一台手机同时充当 HU 和车机上的投影源）**[已确认]**。
2. **Wireless AA（Wi-Fi Direct/hotspot，Native mode）**：HU 应用扮演真正的 wireless-HU（BT 握手 + Wi-Fi）（`HUR/connection/wifi/modes/nativeaa/`、README “2. Native Mode”）。不适合单手机方案（手机没法通过 BT/Wi-Fi 给自己当 HU）**[推测]**。
3. **Wireless Helper + intent（LEGACY）**：在 AA <= 17.3 上可用（README），但要求 AA 是系统应用，且 Android 16 上受 BAL 限制 **[已确认]**（README），**[推测]**（Android 16）。
4. **MediaProjection 屏幕镜像**替代 AA 协议：完全绕过 AA，但这不是 Android Auto UI **[推测]**。
5. **USB AOA 模式**（PC/第二台手机当 HU）：不适合“单手机”方案 **[推测]**。

---

## 附录：文件快速链接

- 网络/握手：`HUR/aap/AapTransport.kt:933-1084`（handshake）、`HUR/aap/AapSslContext.kt:24-173`、`HUR/aap/protocol/messages/Messages.kt`。
- 接收：`HUR/aap/AapReadSingleMessage.kt`、`HUR/aap/AapMessageIncoming.kt`、`HUR/aap/AapMessageHandlerType.kt`。
- Control/Media/Input/Sensor 处理器：`HUR/aap/AapControl.kt`。
- Service discovery：`HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt`、`HUR/aap/protocol/AudioConfigs.kt`。
- 视频：`HUR/aap/AapVideo.kt`、`HUR/decoder/video/VideoFragmentAssembler.kt`、`HUR/aap/protocol/messages/VideoFocusEvent.kt`、`MediaAck.kt`。
- 触控：`HUR/aap/protocol/messages/TouchEvent.kt`、`HUR/input/TouchCoordinateMapper.kt`、`HUR/aap/AapProjectionActivity.kt:2141-2199`。
- Proto 模式（可用于生成代码）：`HUR-PROTO/{control,media,input,sensors,common}.proto`；另一种布局在 `AASDK/protobuf/aap_protobuf/`。
- Self mode：`HUR/connection/self/*`、`HUR/connection/wifi/server/WirelessServer.kt`、`HUR/connection/UnresponsivePeerPolicy.kt`。
- 证书：headunit-revived `app/src/main/res/raw/{cert,privkey}`、aasdk `cert/headunit.{crt,key}`。

---

## 10. 车机服务器自动启动（2026-10-07 添加，2026-10-24 移除）

无障碍服务（`autosrv/`，AccessibilityService）曾自行点击 AA 菜单项。已移除：现在车机服务器由用户手动开启（⋮ -> “Вкл. сервер головного устр-ва”），应用只负责打开 AA 设置。同时，应用不再自行关闭活跃的 AA 会话（视频模式与 DPI 的切换在服务器重启后生效）：服务器是一次性的，没有别人能重启它。下面记录该服务当时的工作方式及菜单项名称。AA 17.7 的菜单项名称（资源 `menu_action_developer_head_unit_network_server[_stop]`）**[已确认]**：

| | en | ru |
|---|---|---|
| “启动”菜单项 | `Start head unit server` | `Вкл. сервер головного устр-ва`（原文如此，无“Запустить”字样） |
| “停止”菜单项 | `Stop head unit server` | `Остановить сервер головного устройства` |
| ⋮ 菜单按钮（contentDescription） | | `Ещё`（在 `uiautomator dump` 中可见） |

AA 17.7 的设置以 `Material3SettingsActivity` 打开（从 `DefaultSettingsActivity` 重定向），工具栏上有 `ImageView desc='Ещё' clickable=true` **[已确认]**。

服务脚本（`ServerAutomation`，已删除；用假屏幕的 JVM 测试验证过）：屏幕亮且无 keyguard -> `startActivity(DefaultSettingsActivity, NEW_TASK|CLEAR_TASK)` -> “Ещё” -> 如果有“Остановить”：点击、等待、再点“Ещё”、等到出现“Запустить”（最多 5 次尝试）、点击 -> 校验（再开菜单，应出现“Остановить”）-> 返回应用（`MainActivity` 带 `cmd=front`）。触发器（`Trigger`）：按钮 / `--es cmd aa_server_restart [--ei delay_s N]`、USB_ATTACH（在第一次 `AaConnector` 尝试之前，最多等 8 秒，然后正常走连接器）、AFTER_SESSION（AA 会话结束、USB 仍在线，最多等 12 秒）、RETRY（上一次尝试撞上锁屏，而服务器还没起来）。手机锁定时：车机上显示占位提示“请解锁手机”。

启用服务：设置 -> 无障碍。在这台手机上（HyperOS、Android 16），`adb shell settings put secure enabled_accessibility_services ...` 和 `pm grant ... WRITE_SECURE_SETTINGS` 会报 `SecurityException: must have WRITE_SECURE_SETTINGS`（需要“USB 调试（安全设置）”开关）**[已确认]**；需手动开启。对 sideload 安装的应用，该项可能是灰的：设置 -> 应用 -> Headswap -> ⋮ -> “允许受限设置” **[推测]**。

## 11. 车载麦克风给 AA（2026-10-07）

AA 侧（`AapSession`、`AaMicUplink`；格式与 headunit-revived 的 `MicSessionController`、`MicFlowControl`、`MicUplinkFrame`、`MicCaptureFormat` 核对过）**[已确认]**：
- `MicrophoneRequest{open=1, anc=2, ec=3, max_unacked=4}` (0x8005) 在 MIC=7 通道上。Open：回复 `MicrophoneResponse{status=0, session_id}` (0x8006)，session_id 从 1 开始；拒绝：status -7、session_id 0（与之前一样）。Close：用原 session_id 回复成功。
- DATA：通道 7，type `0x0000`，消息体 `[timestamp u64 BE µs][PCM16 LE mono 16 kHz]`，帧为加密 bulk（flags 0x0b）。每帧 2048 个采样 = 4096 字节 = 128 毫秒；关闭时的尾部被丢弃，不补零。timestamp = 帧首字节的时刻。
- 窗口：未确认帧数不超过 `max_unacked`；`Ack{session_id=1, ack=2, receive_timestamp_ns=3}` 在通道 7 上（计数 = 有 `receive_timestamp_ns` 则数它，否则数 `ack`）。超出窗口时队列里最多存 4 帧，再超就中断会话（`MicrophoneResponse` 带错误）。在 Open 得到回复之前数据会累积。

CarLife 侧（CarLife 2.0 协议，车机上的观察）**[已确认]**：
- 车机开始录音：CMD `MIC_RECORD_RECOG_START` 0x00010024（空消息体），随后 `MODULE_STATUS{cnt=1, VR(4)=1}`；停止：`MIC_RECORD_END` 0x00010023 和 `MODULE_STATUS VR=0`。`MIC_RECORD_WAKEUP_START` 0x00010022 是原厂应用在持续监听唤醒词时发的，我们不使用。
- HU->MD 数据：VR=5 通道，type `0x00058001`（`CommonParams.MSG_VR_DATA = 360449`），12 字节头 `[len][time][type]`，消息体 PCM16 单声道 16 kHz，不加密。原厂 CarLife 应用在开始录音后丢弃前 20 个包；我们默认为 0（`AaMicRouter.HU_SKIP_PACKETS`），直到知道 Honda 车机的包大小为止。
- Honda 车机在 FEATURE_CONFIG 中不发 `VOICE_MIC` 键（4 个键：BLUETOOTH_AUTO_PAIR、BLUETOOTH_INTERNAL_UI、FOCUS_UI、VOICE_WAKEUP，据 2026-10-07 行车日志）-> MIC 状态 = 0（车机麦克风）。

路由（`AaMicRouter`，UI 模式“麦克风：车机 / 手机 / 自动”）：车机 = 只用车机；手机 = 只用 `AudioRecord` VOICE_RECOGNITION 16 kHz；自动 = 车机，若 2 秒无数据则发 `MIC_RECORD_END` 并切到手机。日志前缀 `MIC`（AA 的 open/close、对车机的请求、每秒一次的车机字节数、fallback）。手机麦克风需要 RECORD_AUDIO；为在后台工作，服务在权限已授予时使用 `connectedDevice|microphone` 的 foreground 类型。