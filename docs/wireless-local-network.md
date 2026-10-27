# targetSdk 37 与 ACCESS_LOCAL_NETWORK：Android 17 模拟器上的验证

日期：2026-10-14。构建：debug，分支 `claude/wireless-e1-transport`，compileSdk/targetSdk 37。模拟器 AVD `A17`（API 37）。Yegor 的决定：在 E2 之前升到 37。

## 代码里有什么

- `build.gradle.kts`：compileSdk 37、targetSdk 37（取自 stash `d24587f3`，同处有调试命令 `aa_probe`、`aa/AaProbe.kt`）。
- Manifest：`android.permission.ACCESS_LOCAL_NETWORK`。
- `MainActivity` 调试命令（`--es cmd …`）：`net_probe`（`link/diag/NetProbe.kt`：UDP “CarlifeHost” 信标在 7999 上向每个 IPv4 接口 broadcast 发送 + 向 `--es to <ip>` unicast，UDP 8999 应答器，全部记入 `WL`）、`net_perm`（系统权限请求）。
- debug tcp6：`--es debug_tcp6_bind 0.0.0.0` 不仅监听 loopback；权限状态写入 `WL link begin`。
- 引导页和在用户处请求权限：E4（与 `NEARBY_WIFI_DEVICES` 和 BT 一起）。

## 平台对该权限怎么说

`pm list permissions`：`ACCESS_LOCAL_NETWORK`、`protectionLevel:dangerous`、组 `NEARBY_DEVICES`。请求时系统对话框是整个组的：“Allow Headswap to find, connect to, and determine the relative position of nearby devices?”。点 “Allow” 之后 `granted=true, USER_SET`。

## 结果

经模拟器 NAT 与 PC 通信（`adb emu redir`）：PC 对手机显示为 10.0.2.2，即局域网节点，而非 loopback。

| 检查 | 无权限 | 有权限 |
|---|---|---|
| UDP 7999 信标：broadcast 10.0.2.255（eth0、wlan0）和 unicast 10.0.2.2 | 每次发送 `sendto failed: EPERM` | 已发送，PC 收到 8/8 |
| UDP 8999 应答器：来自 PC 的数据报 | 一条都没到（0/8），无错误 | 8/8，JSON 就绪响应到达 PC |
| 6 个端口上的入站 TCP（bind 0.0.0.0，车机 = pc-headunit `--transport tcp6`） | bind 成功，PC “连接了”（模拟器 NAT），但应用一个 accept 都没收到：无错误地静默 | 从 10.0.2.2 6/6 约 3 秒，握手，视频约 30 fps |
| 经 `adb forward` 在 127.0.0.1 上的 tcp6 | 6/6，视频 30 fps | — |
| AA 服务器 127.0.0.1:5277 | 工作（2026-10-12 用 `aa_probe` 验证过） | — |

## 对 E2 和 E6 的结论

1. Loopback 不需要权限：USB 模式（AA 在 5277）和 `adb forward` 的 debug 台架在 37 上无需权限即可工作。
2. 无线分支没权限完全不能用：信标和 8999 响应报 EPERM，车机的入站 TCP 静默到不了。入站被拒应用侧完全不可见，所以诊断必须写出权限状态（已在 `WL link begin` 中）并在 `ACCESS_LOCAL_NETWORK=denied` 时 N 秒内没有任何 accept 的情况下显示提示。
3. 权限与 `NEARBY_WIFI_DEVICES` 和 BT 一起以“附近设备”组的一个对话框请求：在 E4 的引导页中占一步。
4. Android 16 及更低（多数测试者的手机，包括 Yegor 的 Redmi）上权限不生效：只验证了 Android 17 的行为。

## 没验证的

真实 Wi-Fi（热点、P2P 接口）、Android 17 真机、会话中途收回权限的行为。