# Fork 当前构建约定（优先于下方历史说明）

- 用户最终指定的集成与构建分支为 `main`。
- 功能分支仅用于编辑和审查；获准改动合入 `main` 后再构建。
- 推送后执行：`gh workflow run android-release.yml --repo ByteJuno/Theta --ref main`。
- 核对运行提交号，验证 Lint、完整单元测试、Debug 和签名 Release；不自动安装或公开发布。
- 非标签构建的 versionCode 由 CI 按提交时间（北京时间）生成：`yyyyMMdd` + 当日 15 分钟时段 `00–95`，只会高于 `app/build.gradle.kts` 中的手写值；后续提交的包可直接覆盖安装，同一提交重复构建结果一致。
- fork 已关闭应用内更新检查：上游 Release 使用不同签名，无法覆盖安装本 fork。
- `main` 构建成功后，`保存构建草稿` job 会把签名 Release APK 存为 Release 草稿 `build-<短提交号>`（不创建标签，仅维护者可见），保留最近 5 个；Artifact 过期后从草稿下载。草稿未经真机验收不要发布。
- 若 PR Check 已在完全相同的代码树上通过（功能分支从 `main` 最新提交开出并直接合并时成立），Eta Build 的 `Lint 与单元测试` job 会跳过重复检查；树不同或发版标签时照常完整检查。
- 工作流已强制此约定：Eta Build 的检查与打包 job 只在 `main` 或 `v*` 标签上执行，其他分支派发会直接跳过；push `main` 不再触发构建；PR Check 只针对 `main`。注意派发会使用目标分支自身的工作流文件，限制只对已包含此改动的分支生效。
- 版本名使用 `上游版本-fork.N`；上游升版时重置为 `上游新版本-fork.1`，同一上游版本内递增 N。
- 下方与本约定冲突的旧流程仅供历史参考。

# Eta 发布流程

## Fork 开发测试约定

`ByteJuno/Theta` 的编译、单元测试、Lint 和 APK 打包统一在 GitHub Actions 执行；默认不在本机运行 Gradle。开发时将改动推送到功能分支并审查，合入 `main` 后再手动触发云端构建：

```bash
git push origin main
gh workflow run android-release.yml --repo ByteJuno/Theta --ref main
gh run list --repo ByteJuno/Theta --workflow android-release.yml --branch main
```

确认运行的提交号与推送提交一致，并检查 Lint、完整单元测试、Debug 与签名 Release 构建结果。测试安装包从该次运行的 Artifacts 下载。功能分支不需要推送标签或公开发布 Release。用户完成真机验收并确认后，再整理上游 PR。

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## PR 检查

指向 `main` 的 Pull Request 会触发 `PR Check`，依次执行 Lint、单元测试和 Debug 构建，
失败时上传检查报告。该工作流不读取任何 Secrets，来自 fork 的 PR 需要维护者在 PR 页面
批准后才会运行，批准前请重点检查 `.github/` 下的改动。

`main` 分支通过仓库规则集要求 `Lint / Test / Debug Build` 检查通过后才能合并。
修改该 job 名称时需同步更新规则集。

## 构建与发布

以下情况会在同一次工作流中生成 Debug APK 和经过签名验证的 Release APK，
并作为两个可直接下载的 Actions Artifact 保存 1 天：

- 推送 `v*` 标签
- 在 GitHub 的 `Actions > Eta Build` 中手动运行

同一工作流会并行执行 Lint 与单元测试。检查失败不影响 APK 上传，但推送标签时不会创建
Release 草稿。

非标签构建会在版本名后追加 SemVer 构建元数据 `+<短提交号>`，例如 `Eta-v3.2.0+a1b2c3d.apk`，
用于区分同一版本号下的不同构建；只有标签构建产出不带后缀的正式版本名。Actions Artifact
需要登录才能下载且到期失效，仅供开发测试，面向用户的分发只使用 GitHub Releases。

推送 `v*` 标签时，工作流还会创建一个只附带 Release APK 的 GitHub Release 草稿，
说明预填自动生成的变更列表。草稿不会被应用内更新检查读到，发布始终由维护者手动完成。
带预发布后缀的标签（例如 `v3.3.1-beta.1`）会标记为 Pre-release，且不会替换稳定版 Latest。

Debug APK 会先独立构建并上传；签名配置缺失或 Release 构建失败时，
已生成的 Debug Artifact 仍可下载，工作流会继续明确报告 Release 失败。

`ByteJuno/Theta` fork 使用独立的持久发布证书，四项 Secrets 已配置。
Windows 本机备份位于 `~/.local/share/eta-artifical0/signing/`，其中
`eta-artifical0-release.jks` 为证书，`credentials.json` 为签名参数。
Windows ACL 仅允许当前用户和 SYSTEM 访问；备份不在 Git 内。
迁移或重建仓库时继续使用同一证书，并重新设置上述 Secrets。
不要把证书、密码或备份提交到 Git，也不要每次构建重新生成证书。
该证书与上游和 Debug 证书不同，不能直接覆盖由其他证书签名的安装。
`main` 分支可以通过手动运行工作流生成签名 Release APK，
无需推送标签或发布公开的 GitHub Release。

正式发布前先更新 `versionCode` 和 `versionName`，然后创建与
`versionName` 对应的标签。`versionCode` 使用 `yyyyMMdd` 加两位当日序号，
只能递增，否则已安装用户无法覆盖升级。推送标签时，工作流会校验标签与 `versionName`
一致，不一致时构建失败。例如发布 `2.2.2`：

```bash
git tag v2.2.2
git push origin v2.2.2
```

标签推送后，等待 `Eta Build` 工作流完成，然后：

1. 在仓库的 `Releases` 中打开 `vX.Y.Z` 草稿，确认附件为 `Eta-vX.Y.Z.apk`。
2. 改写发布说明。
3. 检查版本、说明和附件后，由维护者手动发布。
