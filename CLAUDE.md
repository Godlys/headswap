# CLAUDE.md

## 改动如何进入 main 分支

仓库 github.com/YegorkaPomidorka/headswap，所有者 Yegor。流程始终一致：

1. **Worktree。** 所有改动只在独立的 worktree 及其分支 `claude/...` 中进行。主 checkout（`main`）上有其他会话并行工作，不要在那里提交。
2. **分支。** 提交到 worktree 分支。`git add` 只添加明确的路径，不用 `-a`。提交信息末尾加一行 `Co-Authored-By`。
3. **推送分支。** 通过个人 gh 账号推送，不用 GCM（它会导向工作组织）：
   `git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -u origin <分支>`
   如果 main 已经领先，先把 main 拉入分支（Claude Desktop 中用 `sync_with_base_branch`），再跑测试。
4. **PR** 到 `main`：`gh pr create --repo YegorkaPomidorka/headswap --base main --head <分支>`。
5. **合并。** 当 Yegor 说「залей」/「влей」（合并吧）时立即合并，不等评审——这就是他的授权：
   `gh pr merge <编号> --repo YegorkaPomidorka/headswap --merge`
6. **合并后** GitHub 上的 main 已更新。本地主 checkout 的 `main` 由在该 checkout 工作的会话自行拉取；不要在 worktree 里动它。

版本号（`android/app/build.gradle.kts` 中的 `versionCode`/`versionName`）只在发布版本时单独提交「Version X.Y.Z (versionCode N)」。测试构建（`-cx5test1` 等）用临时版本号编译，不进提交。

除网站外，版本还发布到 GitHub Releases：应用从那里自更新（`update/UpdateLogic.kt`）。标签 `vX.Y.Z`，文件 `headswap-X.Y.Z.apk`（文件名同网站），应用在安装前会显示发布说明：
`gh release create vX.Y.Z headswap-X.Y.Z.apk --repo YegorkaPomidorka/headswap --title "Headswap X.Y.Z" --notes-file <更新内容>`
测试构建发布到 GitHub 只能加 `--prerelease`：这样 GitHub 不把它算作最新版本，应用也不会推送它。
