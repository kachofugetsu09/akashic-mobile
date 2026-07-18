# 系统上下文

- 状态：current
- 证据基线：移动端 `68204abd8a0ad5359c222a305be295313477a7a4`

本文记录当前代码可以证明的结构，不定义产品意图；长期约束见 `../projectneed.md`。

## 边界图

```text
┌──────────────────────── akashic-agent ────────────────────────┐
│ SessionDB / workspace / Gateway / protocol / plugins / MCP    │
└──────────────────────────────┬─────────────────────────────────┘
                               │ WebSocket + fixed protocol
                               ▼
┌──────────────────────── akashic-mobile ───────────────────────┐
│ RealtimeSession                                                │
│   ├─ Room projection + local delivery continuity              │
│   ├─ filesDir attachments / shares / plugin UI cache          │
│   ├─ DataStore selection and UI settings                      │
│   ├─ Android Keystore device identity                         │
│   └─ native shell + mobile WebView                            │
└────────────────────────────────────────────────────────────────┘
```

## 主要入口与 owner

| 入口或组件 | 当前职责 | 状态 owner |
|---|---|---|
| `App.kt` / `AppContainer` | 组装数据库、文件 store、密钥和 realtime | 移动端 |
| `RealtimeSession` | 配对、连接、同步、投递、附件和插件 UI 协调 | 移动端消费协议；核心拥有远端事实 |
| `AppDatabase` | Room v10 schema 与迁移 | 移动端 |
| `MobileConnectionService` | 后台连接和持久通知消费 | 移动端 |
| `protocol/mobile-realtime-v1.json` | 客户端历史协议快照 | 核心 schema 是真源 |
| `runtime-gate/` | 固定核心组合并验证跨仓库语义 | 移动端维护消费者契约；核心提供 provider 测试 |
| plugin UI stores | 缓存核心发布的 asset/catalog/result | 核心插件 runtime 是能力真源 |

## 当前配对行为

`RealtimeSession.restartPairing()` 会断开 socket、清空当前运行时 profile 和当前服务器选择，但保留 Room、设备密钥、会话和本地消息。当前生产代码没有调用 `ServerProfileDao.delete(serverId)`；DAO 仍暴露该级联删除能力，已登记在 `../NOW.md`。

## 当前跨仓库证据

`runtime-contract.lock.json` 分开记录 capability commit 与实际 provider runtime。CI 拉取精确 revision，在无网络、只读核心源码和临时 workspace/plugin home 中运行 provider 场景，再运行移动端消费者检查。

## 未定义而不得猜测

- 产品尚未接受“忘记此服务器的本地资料”功能及其影响清单。
- 配对授权被服务端撤销后的完整 UI 状态模型尚未在 schema 中表达。
- 这些缺口不能被实现者自动补成物理删除或静默清理。
