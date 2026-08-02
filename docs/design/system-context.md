# 系统上下文

- 状态：current
- 证据基线：本文所在 Git tree

本文记录当前代码可以证明的结构，不定义产品意图；长期约束见 `../projectneed.md`。

## 边界图

```text
┌──────────────────────── akashic-agent ────────────────────────┐
│ SessionDB / workspace / Gateway / protocol / plugins / MCP    │
└──────────────────────────────┬─────────────────────────────────┘
                               │ WebSocket control + HTTPS bounded data
                               ▼
┌──────────────────────── akashic-mobile ───────────────────────┐
│ RealtimeSession                                                │
│   ├─ Room projection + local delivery continuity              │
│   ├─ filesDir attachments / shares / plugin UI cache          │
│   ├─ DataStore selection and UI settings                      │
│   ├─ Android Keystore device identity                         │
│   └─ native shell + WebView host/bridge                       │
└────────────────────────────────────────────────────────────────┘
                               ▲
                               │ pinned ZIP + SHA-256 + source manifest
┌──────────────────────── akashic-agent ────────────────────────┐
│ frontend/chat shared WebUI source + desktop/mobile entries    │
└────────────────────────────────────────────────────────────────┘
```

## 主要入口与 owner

| 入口或组件 | 当前职责 | 状态 owner |
|---|---|---|
| `App.kt` / `AppContainer` | 组装数据库、文件 store、密钥和 realtime | 移动端 |
| `RealtimeSession` | 配对、连接、同步、投递、附件和插件 UI WebSocket/HTTPS 协调 | 移动端消费协议；核心拥有远端事实 |
| `AppDatabase` | Room v12 schema 与迁移 | 移动端 |
| `MobileConnectionService` | 后台连接和持久通知消费 | 移动端 |
| `protocol/mobile-realtime-v1.json` | 客户端历史协议快照 | 核心 schema 是真源 |
| `runtime-gate/` | 固定核心组合并验证跨仓库语义 | 移动端维护消费者契约；核心提供 provider 测试 |
| plugin UI stores | 缓存核心发布的 asset/catalog/result | 核心插件 runtime 是能力真源 |
| `clients/android/mobile-web` | 固定共享 WebUI ZIP、摘要与来源；Gradle 校验后解包 | `akashic-agent/frontend/chat` 是源码真源；移动端拥有消费锁 |

## 当前配对行为

`RealtimeSession.restartPairing()` 会断开 socket、清空当前运行时 profile 和当前服务器选择，但保留 Room、设备密钥、会话和本地消息。`ServerProfileDao` 只提供查询和 upsert，不暴露整服务器物理删除能力。

## 当前跨仓库证据

`runtime-contract.lock.json` 分开记录 capability commit 与实际 provider runtime。CI 拉取精确 revision，在无网络、只读核心源码和临时 workspace/plugin home 中运行 provider 场景，再运行移动端消费者检查。

## 历史同步进度

正常重连时，`RealtimeSession` 使用服务端冻结的 `snapshot_max_seq` 和 `after_seq` 游标读取历史；同步期间追加的新消息留给下一轮，不改变当前快照。旧核心仍使用 page/page_size 兼容路径。投影不连续、数量异常或收到 `sync.reset_required` 时从头重建。核心 SessionDB 仍是权威事实，本地进度只决定可重建投影的读取起点。

历史消息正文超过 WebSocket 事件预算时，历史页只携带带长度、摘要和预览的 `content_ref`，thinking、tool block、顺序和消息身份仍随历史页落库。客户端通过 WebSocket 申请与当前设备、连接和消息绑定的短期 ticket，再从同源 HTTPS Range route 分段写入私有文件。每段先落盘后推进 Room 偏移，完整摘要与 UTF-8 校验通过后才原子替换预览。

```text
┌──────────────┐  history.page + content_ref  ┌─────────────────┐
│ Core SessionDB│ ───────────────────────────▶ │ Room projection │
└──────┬───────┘                              └────────┬────────┘
       │ WSS prepare / short ticket                     │ preview + blocks
       └──────────────────────┐                         │
                              ▼                         ▼
                       ┌──────────────┐  verified   ┌──────────────┐
                       │ HTTPS Range  │ ───────────▶ │ full content │
                       │ <= 256 KiB   │  fsync/hash │ same message │
                       └──────────────┘             └──────────────┘
```

## 主动消息投影身份

核心拥有主动消息的 `delivery_id`。`message.proactive` 实时事件与 `history.page` 中发送成功后的 assistant 消息携带同一个值；Android 先保存实时投影，历史到达后把它迁移到 SessionDB message ID。历史到达前，引用命令携带 `reply_to.delivery_id`；迁移后携带 `reply_to.message_id`。旧核心没有该字段时，客户端只对明确标记为 proactive 的唯一文本与时间候选执行兼容迁移。

## 未定义而不得猜测

- 产品尚未接受“忘记此服务器的本地资料”功能及其影响清单。
- 配对授权被服务端撤销后的完整 UI 状态模型尚未在 schema 中表达。
- 这些缺口不能被实现者自动补成物理删除或静默清理。
