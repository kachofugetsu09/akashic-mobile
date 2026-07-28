# 0004 插件查询控制面与数据面显式分离

- 状态：accepted
- 日期：2026-07-28
- 关联：`MOB-OWN-001`、`MOB-OWN-002`、`MOB-PLUGIN-002`、`MOB-PLUGIN-003`、`MOB-XREPO-001`

## 问题

Akasha recall 卡片首次打开时，28～104KiB 的查询结果与实时事件共用 WebSocket，并由
Native 同步注入 WebView。Pixel 7 实测服务端查询约 18ms，WebSocket 到 JavaScript
却需要约 2.3～5.9s；命中本地缓存后约 21ms。瓶颈是大型 JSON 的传输、原生解析和
同步桥接，不是 Akasha 检索。

## 决定

1. WebSocket 继续承载认证、resume、实时事件、插件 catalog/asset、短期查询授权和取消。
2. 只有 module 显式指定 `transport=https` 时，客户端才发送
   `plugin.ui.query.prepare`，再以返回的 request-bound ticket 对当前活动 endpoint 的
   同源 HTTPS route 发起 POST。其他插件继续使用 `plugin.ui.query` 内联 reply。
3. HTTPS 使用当前 endpoint 的既有 LAN certificate pin 或 tunnel system trust，不接受
   服务端指定 host，不跟随 redirect，并把解压后的 response 限制为 192KiB。
4. result 继续写入既有 immutable 可重建缓存，不新增 Room 或长期凭据。断线、owner
   unmount、revision 变化和服务端错误继续走同一插件协调器的取消与失败语义。
5. Native 通过异步 `WebMessage` 把 catalog/result 交给 WebView；TypeScript 仍拥有
   runtime schema 校验。Akasha 是当前唯一选择 HTTPS 的插件。

```text
┌─────────────────┐  prepare / ready  ┌────────────────────┐
│ Plugin WebView  │ ─────────────────▶ │ Authenticated WSS  │
└────────┬────────┘ ◀───────────────── │ control plane      │
         │                              └────────────────────┘
         │ same-origin HTTPS POST
         ▼
┌─────────────────┐  compact + gzip   ┌────────────────────┐
│ RealtimeSession │ ◀──────────────── │ Core query route   │
│ trust + bounds  │                   │ data plane         │
└────────┬────────┘                   └────────────────────┘
         │ async WebMessage
         ▼
┌─────────────────┐
│ Plugin renderer │
└─────────────────┘
```

## 理由

长连接适合顺序敏感的控制与实时事件，独立 HTTPS 请求适合有界正文、压缩、超时和大小
限制。由活动 endpoint 派生 origin 能复用已建立的设备与 TLS 信任，不增加 cookie、
长期 token 或第二套服务器配置。显式 opt-in 保留未迁移插件的兼容性。

## 后果

- 客户端协议新增 prepare/ready，但旧 query 路径保留。
- 插件 runtime 对 module 暴露可用 transport；旧客户端加载新版 Akasha module 时明确
  提示更新，而不是把大型结果静默退回 WebSocket。
- protocol snapshot 与 `source.json` 固定 Core commit
  `197e40a153af2e133b9d640f385b86bfabdb0b79`。
- 正式配对、OAuth、Room schema 和服务端权威状态都不改变。

## 验证

- Kotlin codec/coordinator 覆盖 prepare/ready、成功、HTTP 错误和连接取消。
- TypeScript 类型检查与 mobile web state 测试覆盖 capability 和异步 bridge。
- Runtime Contract 校验 snapshot 的 source commit 与 digest。
- 隔离 application ID、隔离 Gateway workspace 的 Pixel 7 首次打开与缓存复开计时作为
  设备验收；不得复用正式应用状态。
