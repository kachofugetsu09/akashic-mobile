# 0008 Core main 对账 WebUI Stable

- 状态：accepted
- 日期：2026-08-08
- 关联：`MOB-OWN-001`、`MOB-XREPO-004`、`MOB-UI-002`～`MOB-UI-004`
- amends：[0007](0007-server-selected-webui-keeps-embedded-baseline.md) 第 12 项
- Core 决策：`akasic-agent/docs/decisions/0029-main-gateway-reconciles-mobile-webui-stable.md`

## 问题

0007 第 12 项要求 Core 重启不改变 `ReleaseView`。这使拉取已合并服务端 `main` 的普通用户仍需另行执行发布命令，服务端 commit 与默认移动界面可能长期分离。

## 决定

Core 可以在 Gateway 从与 `origin/main` 完全一致的 `main` 启动时，把尚未成功发布过的当前 source commit 对账为 Stable。该行为由 Core 发布者拥有，复用不可变、可复现、原子提交的 Stable 路径；失败必须阻止 Gateway ready，不能破坏旧 ReleaseView。feature、detached、dirty 或未同步 main 不自动发布，自动对账不清除 Preview。

Android 不增加“比较版本并替服务端发布”的逻辑。客户端仍只消费一次已认证 Resolve 返回的完整 `ReleaseView`，继续使用 Preview 优先、兼容性判断、Ensure 与 Present 合同。

```text
Core main 启动 ──► Core Stable 对账 ──► ReleaseView
                                           │
                                           ▼
Android Resolve ──► Preview / Stable / embedded baseline
```

## 后果

- 服务端自动对账不改变 Android 的缓存、激活、回退和数据保留 owner。
- 同一 source commit 是否已经发布由 Core journal 判断，不由 Android semver、sequence 或时间推断。
- 开发者继续显式发布 Preview；移动端不自动清除或绕过 Preview。

## 验证

- 合并后的 Core main 首次启动产生 Stable，后续同提交启动 no-op。
- Stable 对账失败时旧 ReleaseView 完整，Gateway 不进入 ready。
- Android 对自动与显式产生的同一 ReleaseView 执行完全相同的 Resolve/Ensure/Present 路径。
