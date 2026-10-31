# 2026-10-07 历史验证记录（非当前合并验收）

## 基线与环境

- 按用户要求丢弃原本未提交的本地修改，从 GitHub `a9ab54992f2dfee09c3893e852a29a8bdfacf230` 重新开发。本轮修复尚未提交。
- 最近功能审计范围为 `11ee51d^..a9ab549`，覆盖 27 个提交、314 个变更文件，新增 26609 行、删除 2209 行，包括代码、测试、资源和文档。
- 构建环境：Temurin JDK 25.0.3，Android SDK `android-37.0`、Build Tools 37.0.0。
- 真机：vivo V2419A / PD2419，Android 16 / API 36，ROM `PD2419B_A_16.1.12.38.W10`，KernelSU Root，已安装 LSPosed；微信 8.0.78。
- 真机测试直接调用生产工具执行器，未调用付费模型。构造页面不含用户数据；微信验证只切换标签和滑动，不发送消息，不输出聊天或联系人内容。

## 已修复的问题

| 优先级 | 问题与证据 | 修复 |
| --- | --- | --- |
| P1 | 网页搜索仅依赖 DuckDuckGo HTML；实测返回 HTTP 202 验证页 | 增加 Bing RSS 后备来源，每个来源限制 8 秒、保留安全的失败类型。RSS 使用 XML 解析，限制输出并验证 URL；两个来源都失败时明确返回失败，不伪装成零结果。RSS/XML MIME 支持仅用于搜索，不放宽普通网页读取。 |
| P2 | 基线 Android Lint 有 4 项错误 | 移动数据操作先检查实际 UID 必须为 Root，再调用受保护接口；仅在该方法抑制权限误报。日期型 versionCode 符合仓库规则且没有超过平台上限，针对构建脚本单独配置该提示，不整体关闭 Lint。 |

## 最近功能审计

检查变更清单、模块调用关系、权限与取消路径，并结合现有回归测试检查下列功能。测试数量为当前整个包的用例数，包含该包已有功能。

| 功能 | 重点检查 | 回归证据 |
| --- | --- | --- |
| 自动任务与触发器 | 队列认领、网络约束、运行冲突、停止时不重放不确定操作、触发去重和限制；`jobRunning` 在 worker 收尾前阻止并行启动 | `agent.automation` 6 项，数据库迁移与 DAO 测试包含在 `data.db` 7 项中 |
| 自动经验复盘与技能编写 | 复盘工具白名单双层限制、取消和预算、内置/禁用技能禁止修改、revision 校验、保留资源、符号链接及体积限制 | `agent.runtime` 152 项，`agent.skill` 57 项，`agent.memory` 4 项 |
| vivo 小 V、流式续聊与原子岛 | 单次接管所有权、原生回复隔离、历史配对、桥接身份校验、流式事件顺序、通知令牌和来源 | `hook.vivo` 40 项及 runtime/UI 历史回归；云端助手和 ROM 钩子长期运行见下方验证边界 |
| 文件与终端 | Android/Linux 身份与命名空间、路径边界、链接、UTF-8 分页、revision/cursor、原子写入声明、取消及输出上限 | `agent.terminal` 149 项，包含 1 项既有跳过 |
| 会话增量保存与流式备份 | 事务后才推进基线、仅写入变化、导入期间暂停旧状态保存、失败回滚、大小上限和暂存资源释放 | `data.repository` 72 项，`ui.app` 120 项，相关 DAO 与备份测试 |
| 语音输入与连续播报 | 按住说话不提前发送、停顿设置、纠错失败交付原文、取消旧回调、播放预取限制、音频块上限、段间淡入淡出与音频焦点 | `agent.voice` 90 项，包含 1 项既有跳过 |
| Markdown/公式、工具 ID、统计与侧栏 | 定界符不改写代码及源码偏移、历史完整批次修复、工具 ID 不与历史冲突、流式 token/耗时、资源和布局 | `ui.markdown` 65 项，`agent.model` 215 项，`ui.components` 59 项 |
| Android 13 兼容 | API 34/36 功能按版本限制，旧系统不使用弱身份校验替代新广播身份接口；默认能力投影 | Robolectric 的 API 33/34/36 等配置、全量编译和 Android Lint |

## 验证结果

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 1628 项、275 个测试类，0 失败、0 错误、2 项既有跳过 |
| `:app:lintDebug` | 0 错误、381 条警告、1 条提示；警告不代表全部已经消除 |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | 成功 |
| `:app:assembleRelease` | R8 优化构建成功；签名安装仍遵循仓库发布配置 |
| `git diff --check` | 通过 |
| 真机网页工具 | `web_search` 经 `bing_rss` 返回有效来源链接（请求上限 3 条），`fetch_url` 成功读取 `https://example.com/` |

JVM 跳过来自 `DetachedTaskSupervisorTest` 与 `EtaWaveShaderTest`。构建后可查看 `app/build/reports/tests/testDebugUnitTest/index.html` 和 `app/build/reports/lint-results-debug.html`。

旧显示引擎的测试、截图与复现步骤已删除；其历史结果不适用于上游新引擎。

## 复现命令

在仓库根目录使用 JDK 25，并配置 Android SDK：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```


## 验证边界

- JVM 模拟和协议回归不能替代不同 ROM、不同语音提供商及系统助手的完整实机行为。没有调用收费云端语音或模型，没有创建用户日历、闹钟或便签，也没有执行用户自动任务。
- 搜索后备降低单一来源封锁的影响，但两个公共来源同时受限时仍会明确返回失败。

## 实现参考

- 网页工具策略参考 [Hermes web tools](https://github.com/NousResearch/hermes-agent/blob/main/tools/web_tools.py) 和 [OpenClaw web tools](https://docs.openclaw.ai/tools/web)：多来源、有限超时、取消及明确失败；没有引入其整套依赖或新的 API 密钥要求。

当前显示引擎及验证要求见 [后台虚拟屏](VIRTUAL_SCREEN.md)。历史构建结果不代表 3.4.0 合并后的验证。
