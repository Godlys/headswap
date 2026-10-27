# Headswap — 在 CarLife 车机上运行 Android Auto

**简体中文版。** Headswap 是一款 Android 应用，可让仅支持百度 CarLife 2.0（USB 连接，常见于中国市场的汽车）的车机运行 Android Auto。手机以「车机」的身份连接 Android Auto 的 head unit server（127.0.0.1:5277），将 1280x720 视频重新编码，并通过 USB（AOA）以 CarLife 手机的身份呈现给汽车。触摸映射为 1:1，音频和麦克风走 USB。无需任何外接硬件。已在 2024 款本田 Vezel 上验证。非官方项目，与 Google、百度或本田均无关联。GPL-3.0 协议。

![Android Auto 在本田 Vezel 原厂车机上](docs/screenshot.jpg)

## 这是什么，为什么

许多中国市场的汽车原厂车机只有百度 CarLife，没有 Android Auto。通常的解决办法是外接硬件或更换车机。Headswap 无需这些：手机上的应用让 Android Auto 自己充当「车机」，再把画面和控制转发给车机认识的 CarLife。

## 工作原理

```
手机:  Android Auto  <->  Headswap  <->  USB (AOA, CarLife 2.0)  <->  带 CarLife 的车机
          (车机服务器,          (桥接)
           127.0.0.1:5277)
```

- 应用以车机身份通过 TCP 连接 Android Auto 的「车机服务器」（AA 开发者模式）：版本协商、TLS、服务发现、H.264 1280x720 视频。
- 对车机而言，它是一台 CarLife 手机：切换为 USB 配件（AOA），完成 CarLife 2.0 握手，并把视频送入 VIDEO 通道。
- 视频被重新编码，每 100 毫秒重复一次画面。否则车机在静止画面上会断开 USB。
- 触摸 1:1 映射：车机坐标换算到 1280x720 画面后作为触摸事件发给 AA。
- AA 音频和车内麦克风走 USB（CarLife 通道），不走蓝牙。
- Android Auto 的车机服务器由用户手动开启一次：应用只负责打开 AA 设置（⋮ →「开启车机服务器」）。服务器是一次性的，所以 Headswap 不会关闭它，也非必要不结束 AA 会话。

## 兼容性

已确认的车型（带百度 CarLife 的原厂车机，均为中国市场版本）：

| 车型 | Baidu CarLife |
| --- | --- |
| 东风标致 5008 2024 | — |
| 本田皓影，第 2 代 | — |
| 本田 CR-V，第 6 代，改款前 | — |
| 本田 CR-V 2026 1.5T | 2.0 |
| 本田 CR-V 2026 混动（有保留地工作） | — |
| 本田 Vezel 2022–2024（广汽本田） | 2.0 |
| 现代伊兰特，第 7 代，改款前 | — |
| 现代 ix35 2021 | — |
| 捷达 VS5/VS7（仅百度 CarLife 版，J-Link 版不支持） | 2.0 |
| 起亚 K3 2023 | — |
| 起亚 Sportage Ace | — |
| 马自达 CX-5 2025–2026 | 2.0 |
| 马自达 CX-50 | — |
| 三菱欧蓝德 4 2022 | — |
| 斯柯达 Kamiq GT 2021 | — |
| 斯柯达明锐 Pro | 2.0 |
| 丰田卡罗拉 Cross 2024–2025 | 2.0 |
| 丰田 Levin | — |
| 大众高尔夫 8，Pro 配置，2021 | — |
| 大众 ID.Unyx | — |
| 大众凌渡 L | — |
| 大众速腾 2021–2022，改款前 | 2.0 |
| 大众 T-Roc 改款 2026 | — |
| 大众途岳 XR 2025–2026 | 2.1 |
| 大众途观 L Pro / 途昂 Pro | — |

最新列表见[项目页面](https://yegorkapomidorka.com/headswap/)。

欢迎提交其他车型的报告（Telegram 和 4PDA 见下文）。最低 Android 版本：10（API 29）。

## 安装（面向用户）

1. 下载 APK：https://yegorkapomidorka.com/headswap/
2. 在 Android Auto 中开启开发者模式（设置 -> 连点「版本」10 次），然后在「三点」菜单中开启「车机服务器」（Start head unit server）。需要手动开启（应用内的「打开 Android Auto」按钮）：无障碍服务已经没有了。
3. 用 USB 线把手机连接到车机。

更新：在 1.1.10 之后的版本中，Headswap 会自动在 [GitHub Releases](https://github.com/YegorkaPomidorka/headswap/releases) 中查找新版本并覆盖安装，设置会保留。可在 设置 → 更新 中关闭。首次更新时 Android 会请求允许从 Headswap 安装应用。

## 从源码构建

需要 JDK 17。

```
cd android
./gradlew assembleDebug
```

Release 签名从 `~/.android-keys/headswap-release.properties` 读取（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。没有这个文件只能构建 debug 包。

### 车机证书

应用不包含仓库中的证书。Android Auto 只接受由 Google Automotive Link 签发的车机证书。开放项目中使用的公开证书和密钥位于：

- [opencardev/aasdk](https://github.com/opencardev/aasdk)（[f1xpl/aasdk](https://github.com/f1xpl/aasdk) 的延续）：JVC Kenwood，`src/Messenger/Cryptor.cpp`（第 253-299 行），同样位于 `cert/headunit.crt` 和 `cert/headunit.key`；
- [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived)：`app/src/main/res/raw/cert` 和 `privkey`。

将 PEM 文件放到如下位置：

```
android/app/src/main/assets/aa/jvc/cert.pem
android/app/src/main/assets/aa/jvc/key.pem
```

和/或（headunit-revived 的证书组）

```
android/app/src/main/assets/aa/hur/cert.pem
android/app/src/main/assets/aa/hur/key.pem
```

PC 模拟器使用同样的文件，位于 `pc-headunit/certs/jvc/`（和 `hur/`）。`*.pem` 和 `*.key` 在 `.gitignore` 中。

## 仓库结构

- `android/`：应用（Kotlin，包名 `com.yegorkapomidorka.headswap`）：AA <-> CarLife 桥接（`BridgeService`、`CarLifeSession`、`CarLifeState`、`AppLog`）、AA 客户端（`aa/`）、界面（`ui/`）。
- `pc-headunit/`：用于 PC 调试的 Python Android Auto 车机模拟器，见 [pc-headunit/README.md](pc-headunit/README.md)。
- `docs/`：协议规范：[aa-headunit.md](docs/aa-headunit.md)（车机侧的 Android Auto）、[carlife-protocol.md](docs/carlife-protocol.md)（通过 AOA 的 CarLife）、[carlife-v2-handshake.md](docs/carlife-v2-handshake.md)（CarLife 2.0 握手）。

## 限制

- 通话只能通过蓝牙 HFP：不经过 USB。
- Android Auto 的车机服务器是一次性的：会话中断后需要手动重新开启。
- HyperOS 禁止 `adb input`，因此部分调试手段不可用。
- CarLife 2.0 协议仅按已验证车机所需的最小程度实现（无认证和加密）。要求认证和加密的车机将无法工作。
- Android Auto 随时可能改变车机服务器的行为：这是一个未文档化的开发者模式。

## 链接

- 网站和 APK：https://yegorkapomidorka.com/headswap/
- GitHub Releases（同样的 APK，应用从中自更新）：https://github.com/YegorkaPomidorka/headswap/releases
- Telegram：https://t.me/headswap
- 4PDA 帖子：https://4pda.to/forum/index.php?showtopic=1127415

## 免责声明

非官方项目。与 Google、百度、本田无关；Android Auto、CarLife 和本田均为其各自所有者的商标。使用风险自负。驾驶时请勿分心。

## 许可证

GNU GPL v3.0，见 [LICENSE](LICENSE)。© 2026 Yegor (yegorkapomidorka)。

例外：`pc-headunit/proto/*.proto` 基于 [headunit-revived](https://github.com/andreknieriem/headunit-revived)（AGPL-3.0）的协议定义。其余均为 GPL-3.0。
