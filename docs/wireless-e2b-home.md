# E2B：分支 B（手机热点 + 信标）— 家中验证

## 代码

- `link/wifi/HotspotBeacon.kt`：UDP 7999 上的 “CarlifeHost” 信标，每 2 秒向每个 IPv4 接口的 broadcast 发送（不含 rmnet/ccmni/lo/p2p/隧道），每 12 秒向热点子网（非 wlan0）每个地址 unicast 一次；UDP 8999 应答器（对带 `carlifehost` 字符串的数据报回 JSON 就绪状态）。在 6/6 时停止。
- `Tcp6Listener(peerOk)`：0.0.0.0 上的 6 个端口，只接受来自信标广播子网的地址（否则 WL 里 `why=subnet`）。
- 入口：debug `--ez debug_wifi_b true`（BridgeService），等车机 10 分钟。热点由人开启。
- `pc-headunit`：`--transport tcp6 --discover --probe-8999` — 抓 7999 上的信标，取发送方地址，用 6 个 socket 连接。

## A17 模拟器（2026-10-14）

模拟器 NAT：信标以 unicast 扫描方式到达 PC 的 10.0.2.2，8999 探测有响应，从 10.0.2.2 6/6 用时 75 毫秒，视频 30 fps。

## 真实热点，Redmi（2026-10-14，Android 16，HyperOS 3，构建 1.1.12-wl3）

Redmi 热点，PC（Windows，Wi-Fi）作客户端，`carlife_hu.py --transport tcp6 --discover --probe-8999 --touch-test`。

- Xiaomi 上热点接口：`wlan2`，/24 子网（10.83.148.0/24），手机 .164，PC .100。信标走了 broadcast 和扫描，`failed=0`。
- PC 在脚本启动后 1 秒抓到第一个信标，8999 探测收到响应。
- 从 PC 地址（信标子网）6/6 个 socket，2.0 握手带授权，AA 视频 R1080 走 Wi-Fi：前 10 秒约 22 fps，之后 30 fps；音乐（MEDIA）和曲目元数据到达；触摸到达。
- `ACCESS_LOCAL_NETWORK` 在 Android 16 上不生效（`n/a(api 36)`）。
- Windows 防火墙：python 的入站 UDP 7999 无需单独操作即通过（取决于网络配置文件）。

## 未验证

真实车机（固件行为、watchdog、重连）、长会话和发热、行驶中 Wi-Fi 断开、2.4 vs 5 GHz、HyperOS 热点自动关闭。