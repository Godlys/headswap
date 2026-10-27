# tools

## logdiag.py — 可能的原因及应对

输入用户日志，输出 1–3 个可能的原因（附日志中的证据）、给用户的建议，以及应当追问的问题。基于 `logdigest.py` 实现。仅使用 stdlib Python 3，在 Windows 和 Linux 上运行方式相同。

```
python -X utf8 tools/logdiag.py <архив.zip | папка | файл.log> [--json] [--all]
```

- `--all` — 列出所有命中的规则，而不只是最佳的 3 条；`--json` — 以结构化形式输出事实和原因。
- 规则以数据形式存放在 `logdiag_rules.json` 中：`markers`（对原始日志的 regex，2 秒内的匹配视为同一事件）和 `rules`（条件为 `all`/`any`/`none`，形式为 `[事实, 操作符, 值]`）。操作符：`== != >= <= > < ~`（regex）以及 `<v`/`>=v`（版本）。事实：`m.<标记>` — 事件数量；来自摘要的汇总项 — `n_sessions`、`frames_max`、`aa_video_max`（来自 AA 的视频，不是占位帧）、`eio_sessions`、`eio_short`（USB 断开，≤15 秒）、`no_gms`、`pause_eio`（VIDEO_ENCODER_PAUSE 后 3–10 秒出现 EIO）、`version`、`sdk`、`hu`、`channel`、`requested`、`ep_<断开类型>`。无数据（`None`）不等于零。
- `score` — 规则的置信度；`boost` 在此基础上累加；`suppresses` 抑制更通用的规则；`fixed_in` — 若用户版本不低于该版本则规则不触发，否则在建议中追加「请更新」。`category: note` — 原因下方的备注（旧版本、「一切正常」）。
- 新增原因 — 在 JSON 中新增规则，并在 `markers` 中加入真实案例的日志行。验证：`python -X utf8 -m unittest tools/test_logdiag.py`。

## logdigest.py — 行程日志摘要

将 Headswap 日志存档压缩为 40–80 行的摘要：环境、会话表、断开、fps、错误、AUTOSRV、声音和麦克风、导航、触摸。仅使用 stdlib Python 3。

```
python -X utf8 tools/logdigest.py <архив.zip | папка | файл.log> [--json]
```

输入：
- 应用导出的 zip（「分享报告」/「保存存档」）：`device.txt` + `logs/headswap-*.log`（旧版本为 `probe-*.log`），日志截断至最后 4 MiB；
- 通过 adb 抓取完整日志的文件夹（递归查找，`logcat-*.txt` 也会被纳入）；
- 单独的 `.log` 文件。

`--json` 以结构化形式输出同样的数据，文本也是由该结构生成的。

各分节的内容及来源：
1. **环境** — 来自 `device.txt`；车载主机信息只取 `HU_INFO`（`аксессуар:` 行 — 始终是 Baidu CarLife，那不是车载主机）。
2. **会话** — 从 `== USB_ACCESSORY_ATTACHED` / `сессия: старт` 到 `== РЕЗЮМЕ СЕССИИ`；持续时间和原因取自摘要。开始时间前的 `~` — 会话在日志截断前就已开始（时间为估算值）。「лог кончился」— 没有摘要。AA 列 — 主 AA 会话（`AA итог`，data 最大者），括号中为其数量（通常第二个是短暂的「мост остановлен」占位会话）。
3. **断开** — 来自 logcat 的 EOF、EIO/ENODEV、BYEBYE_REQUEST、USB_ACCESSORY_DETACHED、peer closed、POWER_DISCONNECTED（「点火」）；15 秒内的事件合并为一个片段。「无 GMS」标志 — 在 config=0 且 0 个视频帧的情况下，MEDIA_START（而非 MEDIA_PLAYBACK）后 ≤5 秒内出现 EOF。
4. **视频** — `BRIDGE видео[...]` 和 `видео: отправлено ... (+N за 5с)`；3–15 秒之外的间隔被丢弃。
5. **错误** — `ERROR` 及包含 `Exception` 的行，数字已归一化。
6–9. AUTOSRV、声音/麦克风（NAVIGATION(10) 上的 `MIC_REQUEST` — 不是麦克风）、导航（0x18043/0x18045、NAVI_NEXT_TURN_INFO）、触摸。

此后，调查人员只在摘要给出的各个时间点附近的窗口内 grep 日志。

测试：`python -X utf8 -m unittest tools/test_logdigest.py`