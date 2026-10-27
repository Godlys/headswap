# pc-headunit

面向 PC 的最小 Android Auto 车载主机（head unit）模拟器，用于调试：它连接手机上的 AA「车载主机服务器」（TCP 5277），依次完成 Version -> TLS -> Auth -> Service Discovery，接收 H.264 视频到 `out/video.h264`，并把全部通信写入控制台和 `out/session.log`。它不需要手机充当车载主机，也不需要 CarLife，因此可以方便地在 Headswap 应用之外单独验证 AA 一侧。

协议见 [../docs/aa-headunit.md](../docs/aa-headunit.md)。

## 依赖

- Python 3.10+
- `pip install -r requirements.txt`（`protobuf` 5.x 包；生成的 `proto/*_pb2.py` 需要 runtime >= 5.27.2）
- 测试需要：`pytest`；若 PATH 中没有 `ffmpeg`/`ffprobe`，通过 `ffprobe` 校验流的测试会被跳过
- 重新生成 `proto/*_pb2.py` 时（运行不需要）：`pip install grpcio-tools==1.67.1`，然后 `python proto/gen.py`

## 证书

与真实车载主机一样，模拟器必须向手机出示由 Google Automotive Link 签名的客户端证书。证书不包含在仓库中（`certs/` 已列入 `.gitignore`）。请把 PEM 文件放入以下任一目录：

```
pc-headunit/certs/jvc/cert.pem   # JVC Kenwood，来自 aasdk：src/Messenger/Cryptor.cpp，cert/headunit.crt 和 headunit.key
pc-headunit/certs/jvc/key.pem
pc-headunit/certs/hur/cert.pem   # 来自 headunit-revived：app/src/main/res/raw/cert 和 privkey
pc-headunit/certs/hur/key.pem
```

来源：[opencardev/aasdk](https://github.com/opencardev/aasdk)（[f1xpl/aasdk](https://github.com/f1xpl/aasdk) 的延续）、[andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived)。通过 `--cert jvc|hur` 选择证书集（默认 `jvc`）。测试需要两套证书。

## 运行

```
adb forward tcp:5277 tcp:5277
python hu.py --duration 30 --cert jvc
```

在此之前，在手机上：Android Auto -> 设置 -> 点「版本」10 次 -> 「三点」菜单 -> 「Start head unit server」。服务器每次启动只接受一个连接，并且在连接被粗暴切断后会挂住，因此请用 Ctrl+C 或 `--duration` 停止脚本：这样它才能正确关闭会话。

实用参数：`--check`（不建立连接，在本地校验证书和 TLS）、`--touch-test`（首帧后 10 秒点击屏幕中央）、`--resolution`、`--fps`、`--moto`、`--full-audio`、`--minimal`。完整列表：`python hu.py --help`。

## CarLife 车载主机模拟器（`carlife_hu.py`）

仅使用标准库。默认（`--transport stream`）为 TCP 服务器：手机上的 Headswap 桥接通过 `adb reverse tcp:7240 tcp:7240` 以单个连接接入，所有通信都带外部通道头。日志和视频分别写入 `out/carlife_hu.log` 和 `out/carlife_video.h264`。

```
adb reverse tcp:7240 tcp:7240
python carlife_hu.py --duration 120
```

## 6 套接字模式（无线格式）

在 CarLife 无线格式中，每个通道都有自己的 TCP 套接字，且没有外部头。手机监听六个端口，模拟器以客户端身份连接（CMD 7240、VIDEO 8240、MEDIA 9240、TTS 9241、VR 9242、TOUCH 9340）。端口通过 `adb forward` 从手机获取，因此默认主机为 127.0.0.1：

```
adb forward tcp:7240 tcp:7240
adb forward tcp:8240 tcp:8240
adb forward tcp:9240 tcp:9240
adb forward tcp:9241 tcp:9241
adb forward tcp:9242 tcp:9242
adb forward tcp:9340 tcp:9340
adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_tcp6 true
python carlife_hu.py --transport tcp6 --touch-test
```

先启动手机上的桥接（它负责打开端口），再启动模拟器。会话逻辑相同：HU_PROTOCOL_VERSION、MD_INFO 之后的 init burst、授权、每秒一次 heartbeat、首帧后 15 秒点击、结尾输出汇总。对于 Data 通道（端口 9440，手机接收但忽略）有 `--extra-data-socket` 选项。

测试桥接对通道丢失的反应：模拟器关闭一个套接字，然后等待桥接是否关闭其余套接字。

```
python carlife_hu.py --transport tcp6 --kill-chan VIDEO --kill-after 10
```

`--kill-chan` 接受 CMD、VIDEO、MEDIA、TTS、VR、TOUCH；`--kill-after` 从第一个视频帧开始计时。最终会输出 `kill: VIDEO at 12.3 s, bridge closed others after 0.4 s` 或 `bridge did NOT close others within 10 s`（后者表示桥接仍挂着部分通道）。

## 暂停循环

场景「车载主机暂停视频后又重新启动」（收到 START 时桥接重置解码器并等待新的 IDR）。两种模式都适用：

```
python carlife_hu.py --pause-cycles 20 --pause-every 10 --pause-for 3 --duration 300
```

`--pause-cycles N`（默认 0 = 关闭）、`--pause-every S`（默认 10：从第一个视频帧到第一个 PAUSE 的秒数，之后从每个 START 到下一个 PAUSE）、`--pause-for S`（默认 3：PAUSE 与 START 之间的秒数）。`--duration` 必须覆盖 N * (every + for)，否则会话会提前结束。模拟器发送 `VIDEO_ENCODER_PAUSE`（0x0001800A，空正文，与 START 相同），等待后发送 `VIDEO_ENCODER_START`，并从 START 起测量：到第一个视频帧和第一个关键帧的时间（Annex-B 缓冲区中有类型为 5 的 NAL）。暂停期间的帧单独统计：收到 PAUSE 后桥接不应再发送帧，若出现此类帧会记入日志并标注相对 PAUSE 的延迟（几毫秒 = 帧「在途中」，数十到数百毫秒 = 桥接未停止）。最终输出：

```
pause #1: paused_frames=0, start->first_frame=120 ms, start->idr=480 ms
pause cycles: 20, worst start->idr 910 ms, frames during pause total 0
```

时间位置显示 `none` 表示 START 之后直到下一个 PAUSE 或会话结束都没有收到帧或 IDR；汇总行中以 `(no IDR in K cycles)` 标注。如果会话在暂停期间结束，该循环不计入统计，日志中会出现 `session ended during pause` 一行。

## 分支 B：手机热点

手机开启热点，PC 作为车载主机客户端连接。无需手动指定手机地址：模拟器监听 UDP 信标（`CarlifeHost` 每 2 秒一次），取第一个数据报的发送方地址作为 host，之后的工作方式与 6 套接字模式相同。

1. 在手机上开启热点，让 PC 连接到该热点。
2. 在手机上启动分支 B：
   ```
   adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_wifi_b true
   ```
3. 在 PC 上：
   ```
   python carlife_hu.py --transport tcp6 --discover --probe-8999 --touch-test
   ```

在 Windows 上，请在防火墙中允许 python 接收入站 UDP 7999（专用网络），否则信标无法到达。如果 PC 有多张网卡，请指定热点所在的网卡：`--bind-ip <PC 在热点网络中的 IP>`。

参数：`--discover`（仅与 `--transport tcp6` 搭配）、`--beacon-port`（默认 7999）、`--bind-ip`（默认 0.0.0.0）、`--discover-timeout`（默认 60 秒；收不到信标时会话以 `no beacon within 60 s` 结束）、`--probe-8999`（收到信标后向手机 8999 端口发送一个 UDP 数据报，等待响应最多 2 秒，响应非必需）。最终输出 `discovered_host`、信标数量和探测结果。

## 测试

```
python -m pytest tests -q
```

测试完全独立：「手机」直接写在测试里，通过 `socket.socketpair()` 与会话通信。不需要端口 5277，也不需要手机。