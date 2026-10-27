# CarLife 2.0：MD <-> HU 序列

描述应用（MD 角色，手机）如何通过 CarLife 2.0 协议与车机（HU 角色）通信。实现在 `android/`（`CarLifeSession.kt`、`Handshake.kt`、`Protocol.kt`）。基于对真实 Honda Vezel 2024 车机的观察以及 [carlife-protocol.md](carlife-protocol.md) 中列出的开源资料。Framing 相对 1.0 版没有变化：外部 `[chan u32 BE][8+len u32 BE]`，CMD 8 字节 `[len u16][reserved u16=0][type u32]`，视频 12 字节 `[len u32][time u32][type u32]`。

在 Honda Vezel 车机上的观察：它发送 `HU_PROTOCOL_VERSION {2.0}` 两次，然后发 `CARLIFE_DATA_SUBSCRIBE`，之后在 MD 除了 MATCH 什么也不发的情况下保持沉默。该车机不发 `HU_AUTHEN_REQUEST`，通道无加密（`HU_FEATURE_CONFIG_RESPONSE` 中不声明 `CONTENT_ENCRYPTION` 键）。

VW Tharu XR 2026 会发 `HU_AUTHEN_REQUEST`，并且切消息的方式不同：inner 头部（8 字节）和消息体是分开的外部帧（`00 00 00 01 00 00 00 08` + `00 04 00 64 00 01 80 01`，然后是 `00 00 00 01 00 00 00 04` + `08 02 10 01`）。`InnerAssembler` 会暂存一个“只有一个头部、declared len > 0”的帧，并把同一通道的后续帧补到 declared len（cmd 通道：头部 8 字节，data 通道：12 字节；cmd 的 declared len > 1 MB 和 data 的 > `MAX_OUTER_LEN` 不暂存）。整条消息在一个帧内的照常通过。

## 序列

```
HU -> MD  0x18001 HU_PROTOCOL_VERSION {major=2 minor=0}      (重复的忽略，不再发第二个 MATCH)
MD -> HU  0x10002 PROTOCOL_VERSION_MATCH_STATUS {1}           \
MD -> HU  0x1001B FOREGROUND (空)                              |  同一批，立即发送，
MD -> HU  0x10018 SCREEN_ON (空，如果屏幕亮着)                  |  不等车机回任何东西
MD -> HU  0x10004 MD_INFO CarlifeDeviceInfo                    /
MD -> HU  0x10026 MODULE_STATUS，500 ms 后（44 字节，cnt=7）
HU -> MD  0x18043 CARLIFE_DATA_SUBSCRIBE  `08 01 12 02 08 00`
MD -> HU  0x10044 CARLIFE_DATA_SUBSCRIBE_DONE  `08 01 12 04 08 00 10 01`
HU -> MD  0x18003 HU_INFO                 (保存并记日志，无回复)
HU -> MD  0x18027 STATISTIC_INFO
MD -> HU  0x1004B MD_AUTHEN_RESULT {true}   `08 01`
MD -> HU  0x10051 MD_FEATURE_CONFIG_REQUEST (空)
MD -> HU  0x10031 CAR_DATA_SUBSCRIBE        (空)
HU -> MD  0x18048 HU_AUTHEN_REQUEST {randomValue}  (Honda 不发；VW Tharu 发)
MD -> HU  0x10049 MD_AUTHEN_RESPONSE {encryptValue}  (立即回复：用 Baidu 私钥做 RSA 签名，Base64；分支 "2.0;" — md5 码，见下)
HU -> MD  0x1804A HU_AUTHEN_RESULT                 (日志)
HU -> MD  0x18052 HU_FEATURE_CONFIG_RESPONSE       (记录所有键；CONTENT_ENCRYPTION=1 -> 仅警告)
HU -> MD  0x18045/0x18046 SUBSCRIBE_START/STOP     (日志，不发送数据)
HU -> MD  0x18007 VIDEO_ENCODER_INIT {w,h,fps}
MD -> HU  0x10008 VIDEO_ENCODER_INIT_DONE {w,h,fps}  (编码器尺寸按车机宽度取档)
MD -> HU  0x1001B FOREGROUND
HU -> MD  0x18009 VIDEO_ENCODER_START  -> 只有此时才启动编码器
MD -> HU  VIDEO [len][time=unix秒][0x20001] + H.264 Annex-B; 空闲时 keepalive: 0x20001 len=0
```

START 之前 MD 在 VIDEO 通道上一律沉默（不发任何 0x20002）。MATCH 之后启动批内的顺序和 STATISTIC_INFO 的回复都固化在 `Handshake.startBatch` / `Handshake.onStatisticInfo` 中，并由测试验证。

## 代码

| 代码 | 名称 | 方向 | 消息体 |
|---|---|---|---|
| 0x18001 | HU_PROTOCOL_VERSION | HU->MD | `08 major 10 minor`（重复的忽略） |
| 0x10002 | PROTOCOL_VERSION_MATCH_STATUS | MD->HU | `08 01` |
| 0x1001B | FOREGROUND | MD->HU | 空 |
| 0x10018 | SCREEN_ON | MD->HU | 空 |
| 0x10004 | MD_INFO | MD->HU | CarlifeDeviceInfo |
| 0x10026 | MODULE_STATUS | MD->HU | CarlifeModuleStatusList；MATCH 后 500 ms 发送 |
| 0x18043 | CARLIFE_DATA_SUBSCRIBE | HU->MD | CarlifeSubscribeMobileCarLifeInfoList |
| 0x10044 | CARLIFE_DATA_SUBSCRIBE_DONE | MD->HU | 同上，+ supportFlag |
| 0x18045 / 0x18046 | SUBSCRIBE_START / STOP | HU->MD | moduleID 列表（我们只记日志） |
| 0x18003 | HU_INFO | HU->MD | CarlifeDeviceInfo（HU 的） |
| 0x18027 | STATISTIC_INFO | HU->MD | CarlifeStatisticsInfo |
| 0x1004B | MD_AUTHEN_RESULT | MD->HU | `08 01` |
| 0x10051 | MD_FEATURE_CONFIG_REQUEST | MD->HU | 空 |
| 0x10031 | CAR_DATA_SUBSCRIBE | MD->HU | 空 |
| 0x18048 | HU_AUTHEN_REQUEST | HU->MD | randomValue (string)；Honda 不发，VW Tharu 发 |
| 0x10049 | MD_AUTHEN_RESPONSE | MD->HU | encryptValue (string)：`versionVerify(randomValue)` 签名的 Base64 |
| 0x1804A | HU_AUTHEN_RESULT | HU->MD | authenResult (bool) |
| 0x18052 | HU_FEATURE_CONFIG_RESPONSE | HU->MD | CarlifeFeatureConfigList |
| 0x18007 | VIDEO_ENCODER_INIT | HU->MD | `{w,h,fps}` |
| 0x10008 | VIDEO_ENCODER_INIT_DONE | MD->HU | `{w,h,fps}` |
| 0x18009 | VIDEO_ENCODER_START | HU->MD | 空 |
| 0x1800A / 0x1800B | PAUSE / RESET | HU->MD | 空 |
| 0x1800C / 0x1000D | FRAME_RATE_CHANGE / _DONE | HU<->MD | `{fps}`，允许 3..30 |
| 0x20001 | VIDEO_DATA / keepalive（len=0） | MD->HU | H.264 |
| 0x20002 | VIDEO_HEARTBEAT | MD->HU | 无；仅在 “mobile pausing” 时发送，我们不使用 |
| 0x18056 / 0x10057 | VIDEO_ENCODER_JPEG / ACK | HU<->MD | ACK 为空（我们回 ACK，但仍然发 H.264） |
| 0x18079 / 0x10080 | REQUEST / RESPONSE_MD_THEME_STATE | HU<->MD | `{themeModes}`：0 浅色、1 深色、2 自动 |

其余 CMD 通道代码：[carlife-protocol.md](carlife-protocol.md) 第 3 节。

## MD_INFO（CarlifeDeviceInfo）

字段：os="Android"、board、bootloader、brand、cpu_abi(2)、device、display、fingerprint、hardware、host、cid=Build.ID、manufacturer、model、product、serial="unknown"、codename、incremental、release=Build.VERSION.RELEASE、btaddress="unknown"、carlifeversion（应用内常量）、carlifeType=1。

SDK：`SDK_INT >= 33` 时发送 `sdk="29"`、`sdk_int=29`。Android 16 / SDK 36 正是这种情况。否则发真实值。

## MODULE_STATUS

对 major >= 2 的车机：`cnt=7`，键值对 (moduleID, statusID) = (1,0)(2,0)(3,0)(4,0)(6,0)(8,0)(9,0)。消息体 44 字节：`08 07 12 04 08 01 10 00 ...`。对 major < 2 只发五对。

## 视频尺寸

不是“按车机要求”，而是按车机宽度取档。对 major 2（这不是“新式车机”，不会有 wide）：宽度 < 800 -> 768x432；< 1024 -> 848x480；< 1280 -> 1024x576；否则 1280x720。“新式车机” = major 4 或 3.2：w/h >= 2.3 的 wide 给出 1024x384 / 1280x480 / 1920x720，而 >= 1920 无 wide 给出 1920x1080。INIT_DONE 中的 fps = INIT 中的 fps（若在 1..60），否则 30。开关 `force720` 给出 1280x720。不实现用户/服务器分辨率覆盖层。

## 未实现的功能

- `randomValue = "2.0;<rnd>"` 分支的 MD_AUTHEN_RESPONSE（Baidu 的原生 `EncryptionUtils.getVerifyCode`）：`encryptValue = md5hex("CARLIFE" + brand + model + sdk + <rnd>)`，小写十六进制，brand/model/sdk 取自已发送的 MD_INFO，`<rnd>` 是 `split(";")` 的第二段。发送该格式的是 Honda CR-V 2026 车机（中国，`honda_3ya`，CarLife HU v2.3.4），`<rnd>` 是 `2026-10-14-12-01-33` 这样的日期；不回复的话车机显示“设备未授权”，并大约每 40 秒断开一次 USB。普通 `randomValue` 按 Baidu 8.6.7 的方式签名，见上文。
- 通道加密：已验证的车机不声明它。如果 `HU_FEATURE_CONFIG_RESPONSE` 含 CONTENT_ENCRYPTION=1，日志写警告；0x1006A..0x1006E 链路（RSA public key -> AES key -> ENCRYPT_READY，见 carlife-protocol.md 第 8 节）未实现。
- 订阅数据（turn-by-turn、音乐）：DONE 对 moduleID 0 和 2 说 supportFlag=true，但 SUBSCRIBE_START 之后数据不发送。
- JPEG 视频模式不支持，只发 H.264。