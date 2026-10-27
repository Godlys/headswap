# CLAUDE.md

## 仓库与协作模式

仓库 `github.com/Godlys/headswap`（fork 自 `YegorkaPomidorka/headswap`，可私有化）。直接推 `main`，无 PR 流程。

## 改动如何进入 main

1. 所有改动直接在本地 checkout 完成，提交到 `main`。
2. `git add` 只添加明确的路径，不用 `-a`。
3. 提交信息末尾不要加 `Co-Authored-By`。

## 版本发布与 CI

- CI（`.github/workflows/build-arm64.yml`）：push 到 `main` 且 commit message 含「版本更新」时，自动构建 **arm64-v8a** release APK，产物上传 Actions artifact（`headswap-arm64-v8a.apk`）；也可 `workflow_dispatch` 手动触发。签名密钥通过 repo secret `HEADSWAP_SIGNING_B64` 注入，未配置则产出 unsigned APK。
- 版本号（`android/app/build.gradle.kts` 中的 `versionCode`/`versionName`）单独提交，commit 写「版本更新 vX.Y.Z」即可同时完成版本升级 + 触发 CI。
- 发布到 GitHub Releases（应用从那里自更新，`update/UpdateLogic.kt` 指向本仓库）：标签 `vX.Y.Z`，文件 `headswap-X.Y.Z.apk`：
  `gh release create vX.Y.Z headswap-X.Y.Z.apk --repo Godlys/headswap --title "Headswap X.Y.Z" --notes-file <更新内容>`
- 测试构建发布只能加 `--prerelease`（避免被算作最新版本推送给用户）。
- 若仓库变为私有：应用内自更新依赖 GitHub 公开 API（`api.github.com/repos/.../releases/latest`），**私有仓库下未认证请求会 404，自更新失效**，用户需手动下载新 APK。

## 车机证书

仓库已包含 Google Automotive Link 签发的车机证书（`android/app/src/main/assets/aa/{jvc,hur}/`，来自 aasdk 与 headunit-revived）。release 构建硬依赖该目录；如需替换，直接覆盖 `cert.pem`/`key.pem`（X.509 PEM + PKCS#8 或 PKCS#1 RSA）。注意 `android/.gitignore` 忽略该目录，提交变更需 `git add -f`。