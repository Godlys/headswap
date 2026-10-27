# Honda Vezel 2024 上的无线 CarLife：已验证，不工作

结论：此车机没有无线 CarLife。只有 USB（AOA）。

车机原厂：Honda Vezel 2024，中国，CarLife 2.0，HU 应用 1.1.0。

## 官方 MD 的无线模式是怎么做的

来源：反编译的 Baidu CarLife MD 8.6.7，类 `com.baidu.carlife.core.connect.auto.*`。

- 手机持有 RFCOMM 服务器，SDP 中的记录名为 `carlife`。监听两个 UUID：
  - SPP `00001101-0000-1000-8000-00805F9B34FB`；
  - CarLife `a45bc7e5-bb50-4949-9de1-f78299cf6d78`。

  车机（`BluetoothClient`）连接它。
- BT 通道的帧结构与 USB 的 cmd 通道相同。8 字节头部：`len u16 BE`、`reserved u16`、`serviceType u32 BE`。随后是 `len` 字节 protobuf（`BluetoothConnect.readData`）。
- accept 之后手机先发：`WIRLESS_INFO_REQUEST` 0x00100001。然后是如下交换：

  | 消息 | 代码 |
  |---|---|
  | `INFO_RESPONSE` | 0x00108002 |
  | `TARGETINFO_REQUEST` | 0x00100004 |
  | `TARGETINFO_RESPONSE` | 0x00108005 |
  | `REQUEST_IP` | 0x00108006 |
  | `RESPONSE_IP` | 0x00100007 |
  | `MD_STATUS` | 0x00100008 |
  | `HU_STATUS` | 0x00108009 |

  protobuf 字段：
  - InfoResponse：`wirlessType=1`、`wifiFrequency=2`。类型 2、3、4 表示无线连接。
  - TargetInfo：`wifiDeviceName=1`、`targetInfo=2`、`scanRecord=3`、`p2pNetInfo=4`。

  交换之后 — Wi-Fi Direct 和 TCP 通道，见 [carlife-protocol.md](carlife-protocol.md)。
- UDP 7999 “CarllifeHost”：手机自己开热点时发送（`ConnectManager.sendHotspotUdp`）。车机监听它。

## 试过什么，2026-10-10

做了 Headswap 的诊断版，带被动探测模式。它对车机什么都不回，只监听：
- 4 个 RFCOMM 服务器：SPP 和 CarLife，各有 secure 和 insecure 变体；
- 已配对设备的 SDP；
- BT 事件；
- Wi-Fi Direct（peer 和服务）；
- Wi-Fi 扫描；
- UDP 7999。

实验结束后删除了探测分支。代码可凭本描述还原。

轮次：
1. 插着 USB 线，手机早已配对。车机只有 HFP、A2DP 和 PBAP。
2. 不插线。删掉配对、在探测运行中重新配对，听了 7 分钟。
   - 没有任何入站 RFCOMM。
   - 按 BT 协议栈日志，车机唯一的 RFCOMM 是 `UUID=0x111f`、HFP。
   - 车机没有 P2P peer、没有热点、也没有发往 UDP 7999 的数据报。
3. 手机上的官方 Baidu CarLife 只通过 USB 连接车机。logcat 里没有任何无线尝试。Baidu 自己的日志是关的。

车机的 SDP 记录（“Honda HFT”）：SPP、OPP、A2DP-sink、AVRCP、PANU、HFP、MAP-MNS。其中没有 CarLife-UUID 和 AA-wireless 的 `4de17a00-…`。

各轮日志：`logs/car-wprobe/`。

## 车机本身能看到什么

- 车机上的 CarLife：“帮助”（右上），然后 Android 或 iPhone。两个分区的第一项都是连接类型，只有 USB。没有 Wi-Fi 选项。
- 车机设置里的 Wi-Fi Hotspot 没有 Honda Connect 授权无法开启。在不少国家 Honda Connect 不可用。因此像原厂无线 Android Auto 那样的 BT + Wi-Fi 方案在这台车机上也不行。

## 代码中的分支 A（2026-10-16）

根据对 LifeAuto Lite 0.1.6 的分析做出（它通过无线连接 Mazda）。代码：`link/bt/CarlifeBtProbe.kt` 和 `link/bt/P2pJoin.kt`。

1. 等待“通过 Wi-Fi 等待汽车”时，监听 RFCOMM `carlife`（SPP 和 CarLife UUID）。车机自行连接。
2. `WIRELESS_INFO_REQUEST`，然后 `TARGET_INFO_REQUEST`。
3. 根据 `TARGET_INFO`，手机以客户端身份进入车机的 P2P 组：先用 `p2pNetInfo` 里的 ssid 和密码（`WifiP2pConfig.Builder`，若有频率则带上），否则找名字匹配字段 1 的 peer 并 PBC 连接，`groupOwnerIntent=0`。超时 30 秒。
4. 当 `p2p*` 出现 IPv4 且车机发来 `IP_REQUEST` 时，回复 `MD_WIFI_IP` 0x00100007。
5. 车机向 `Tcp6Listener` 连接 6 个 TCP 通道（外加 9440）。P2P 组子网与信标子网同等放行。
6. 蓝牙连接保持到等待结束或会话结束。
7. 车机撤掉 P2P 组（Mazda：退回自己菜单后约 40 秒，日志 2026-10-17）— 整个入组过程重置。车机重连蓝牙并发了新的 `TARGET_INFO` 时，重新入组。之前它会被当作“已在入组中”丢弃，连接一直回不来，除非点“停止”再重新开始。

未确认：`MD_WIFI_IP` 的消息体。目前是 protobuf 字段 1 里放 IP 字符串（LifeAuto 的消息体在原生部分组装，Baidu 的这个类我们没读过）。如果它之后车机不连 TCP，看 WL 日志中 `tx WIFI_IP` 之后的 `bt rx` 行：未知消息会写 `shape`（字段编号、数字和长度，不含内容）。

权限：`NEARBY_WIFI_DEVICES`（Android 13+）或 `ACCESS_FINE_LOCATION`（更低版本），与其余权限一起在点“通过 Wi-Fi 等待汽车”时请求。