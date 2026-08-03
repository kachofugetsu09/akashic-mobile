# 0005 共享 WebUI 源码由核心仓库统一维护

- 状态：accepted
- 日期：2026-08-01
- 关联：`MOB-OWN-001`、`MOB-OWN-002`、`MOB-REPO-001`、`MOB-UI-002`
- amends：[0002](0002-core-mobile-ownership-boundary.md)
- 部分勘误：[0007](0007-server-selected-webui-keeps-embedded-baseline.md) 将固定 ZIP 收窄为 embedded baseline，并增加已认证服务端 generation

## 问题

桌面 Web Chat 与 Android WebView 使用相同的 React 消息组件，却在两个仓库保存两份源码。主题、流式正文和工具轨迹会独立漂移；直接依赖父仓库路径又会破坏移动仓库独立构建。

## 决定

1. `akashic-agent/frontend/chat` 统一维护桌面与 Android 的共享 WebUI 源码和双入口。
2. 本仓库保存固定 ZIP、SHA-256 和 source manifest 作为 embedded baseline；Gradle 校验后解包进 APK。已配对运行时还可按 [0007](0007-server-selected-webui-keeps-embedded-baseline.md) 消费服务端选择的不可变 generation。
3. WebView 容器、Native bridge、Room、outbox、通知、Keystore、相机扫码和 Android 生命周期继续由本仓库拥有。
4. embedded baseline 与 Stable 必须固定 `akashic-agent` source commit/tree、完整构建上下文和产物摘要；Preview 可以固化未提交输入，但必须记录 base commit、patch/tree digest 与 dirty provenance。所有路径都不得使用本机绝对路径、submodule、浮动分支或网络 fallback。

## 理由

一个源码真源消除视觉与组件漂移，固定二进制产物同时保留离线、独立、可复现的 Android 构建。状态 owner 没有随源码位置迁移。

## 后果

- 本仓库删除 `frontend/chat` 与 Node 构建链；Web 状态测试转移到共享 UI 真源。
- Android CI 校验固定产物后运行原生测试、lint 和构建。
- WebUI 改动由主仓库构建和验证；baseline 变化更新本仓库 ZIP、摘要与 source manifest，兼容的运行时 UI 变化也可按 0007 显式发布服务端 generation。dirty Preview 不能直接提升 Stable，提交真实输入后必须从隔离 snapshot 重建。

## 验证

- 无父仓库 checkout、无 `node_modules` 时 Android 仍可构建。
- ZIP、SHA-256 或 source manifest 任一被篡改时 Gradle 失败。
- APK assets 中包含 `mobile.html`、内嵌 manifest 和散列资源。
