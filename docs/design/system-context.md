# 系统上下文

- 状态：current
- 证据基线：本文所在 Git tree

本文记录当前代码可以证明的结构，不定义产品意图；长期约束见 `../projectneed.md`。

## 边界图

```text
┌──────────────────────── akashic-agent ────────────────────────┐
│ SessionDB / Gateway / protocol / plugins / WebUI ReleaseView  │
│ WebUI immutable generations + content-addressed blobs         │
└──────────────────────────────┬─────────────────────────────────┘
                               │ WebSocket control + HTTPS bounded data
                               ▼
┌──────────────────────── akashic-mobile ───────────────────────┐
│ RealtimeSession                                                │
│   ├─ Room projection + local delivery continuity              │
│   ├─ Resolve / Ensure + per-server verified WebUI cache        │
│   ├─ filesDir attachments / shares / plugin UI cache          │
│   ├─ DataStore selection and UI settings                      │
│   ├─ Android Keystore device identity                         │
│   └─ Present + native shell + trusted-origin WebView/bridge    │
└────────────────────────────────────────────────────────────────┘
                               ▲
                               │ embedded baseline ZIP + SHA-256 + source manifest
┌──────────────────────── akashic-agent ────────────────────────┐
│ frontend/chat shared WebUI source + desktop/mobile entries    │
└────────────────────────────────────────────────────────────────┘
```

## 主要入口与 owner

| 入口或组件 | 当前职责 | 状态 owner |
|---|---|---|
| `App.kt` / `AppContainer` | 组装数据库、文件 store、密钥和 realtime | 移动端 |
| `RealtimeSession` | 配对、连接、同步、投递、附件、插件 UI 与 WebUI Resolve/Ensure 的 WebSocket/HTTPS 协调 | 移动端消费协议；核心拥有远端事实与 WebUI 选择 |
| `AppDatabase` | Room v13 schema 与迁移；v13 只增加 WebUI 派生 metadata 表 | 移动端 |
| `MobileConnectionService` | 后台连接和持久通知消费 | 移动端 |
| `protocol/mobile-realtime-v1.json` | 客户端历史协议快照 | 核心 schema 是真源 |
| `runtime-gate/` | 固定核心组合并验证跨仓库语义 | 移动端维护消费者契约；核心提供 provider 测试 |
| plugin UI stores | 缓存核心发布的 asset/catalog/result | 核心插件 runtime 是能力真源 |
| `MobileWebUiStore` / `MobileWebUiCoordinator` | 每服务端校验、缓存、恢复、GC、reset 与三动作收敛 | 移动端；Core 只拥有发布选择和不可变资源 |
| `MobileWebChat` WebView host | 从本地可信 HTTPS origin 展示 committed serving/candidate；健康前关闭副作用 bridge | 移动端 |
| `clients/android/mobile-web` | 固定 embedded baseline ZIP、摘要与来源；Gradle 校验后解包 | `akashic-agent/frontend/chat` 是源码真源；移动端拥有 baseline 消费锁 |

## 当前配对行为

`RealtimeSession.restartPairing()` 会断开 socket、清空当前运行时 profile 和当前服务器选择，但保留 Room、设备密钥、会话、本地消息和按服务端隔离的可重建缓存。进入重新配对时插件进程内 catalog owner 立即清空并停止查询，磁盘 catalog/asset 不删除；新授权只有在历史同步完成后才重新激活当前服务端目录。`ServerProfileDao` 只提供查询和 upsert，不暴露整服务器物理删除能力。

`MobileWebUiCoordinator.pairingRecoveryRequired` 只由服务端 `capability_required` 错误置位。MainActivity 以原生 Material 3 Snackbar 提供“重新配对”，因此 embedded WebUI 或 bridge 自身不可用时仍有恢复路径；普通断线不伪装问题已解决，新认证开始或有效 ReleaseView 成功处理后才清除此状态。

## 当前跨仓库证据

`runtime-contract.lock.json` 分开记录 capability commit 与实际 provider runtime。CI 拉取精确 revision，在无网络、只读核心源码和临时 workspace/plugin home 中运行 provider 场景，再运行移动端消费者检查。

## 服务端 WebUI 发布

Core 显式提交 Stable/Preview 后，Android 用当前已认证连接执行 `Resolve`，需要时用同 endpoint 的短期 ticket 执行 `Ensure`，并只在新的 UI session 或用户明确立即应用时执行 `Present`。Core 从与 `origin/main` 一致的新 `main` 启动时还会把从未成功发布过的该 commit 对账为 Stable；同 commit 重启 no-op，失败阻止 Gateway ready，Preview 保持原值。保存源码、普通构建、watcher、feature 和 detached 启动不改变 `ReleaseView`。

## 会话模型目录与选择

`RealtimeSession` 在连接 ready 和会话切换时通过 `model.catalog.get` 读取 Core 当前模型 generation 与该会话选择。`ModelCatalogCoordinator` 拒绝目录外 runtime/思考强度，并用目标 session 丢弃迟到 reply；WebUI snapshot 只投影这份目录。用户选择随下一条 `message.send` 进入既有 outbox，空 runtime 明确恢复默认，旧客户端缺少字段时 Core 保留原选择。

```text
┌──────────────┐  WSS ReleaseView  ┌─────────┐
│ Core pointer │ ────────────────▶ │ Resolve │
└──────┬───────┘                   └────┬────┘
       │ HTTPS manifest/CAS             │ desired
       └──────────────────────────▶ ┌────▼────┐
                                    │ Ensure  │── verified app-private cache
                                    └────┬────┘
                                         │ UI session boundary
                                    ┌────▼────┐
                                    │ Present │── local trusted HTTPS origin
                                    └─────────┘
```

embedded baseline 永远保留在 APK；远端目标未就绪、离线、不兼容、被拒、激活失败或 renderer 连续失败时，当前会话继续 committed serving，下一边界使用 verified fallback 或 baseline。`4403` 与已认证 `device.revoked` 是唯一绕过普通 session 边界的撤销路径：立即关闭当前 socket generation 和 bridge admission、回 baseline、停止重连，同时保留业务数据与已验证 WebUI cache。

## WebView bridge 完成语义

发送和插件查询是带副作用或占用页面 pending 的交互；在发起 WebView 生命周期仍有效时，原生 bridge 为每个 request 保证一个 terminal result。健康候选只允许握手；如果页面在 candidate、旧 lease、关闭 admission 或主线程队列不可用时请求动作，bridge 立即向发起 WebView 返回失败，不排队、不静默丢弃，也不把旧页面的迟到成功投给新页面。owner release 销毁发起 WebView 时，该页面的 pending 随页面取消，结果不得转投新的 owner。

```text
WebUI request
     │
     ├─ current lease + admission ─▶ native owner ─▶ accepted / rejected
     │
     └─ candidate / stale / closed ─────────────────▶ rejected
                                                        │
                                                        ▼
                                           同一发起 WebView 清除 pending
```

embedded baseline 没有远端 generation，`generationRef=null` 本身就是它的合法代际身份；不能拿字符串 `embedded` 与空 ref 比较后把整条恢复 bridge 判成 stale。普通导航动作仍遵守 exact origin、main frame、nonce、generation 和当前 WebView identity。

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
- 这个缺口不能被实现者自动补成物理删除或静默清理；设备撤销只终止当前授权与远端 UI presentation，不等同于忘记本地资料。
