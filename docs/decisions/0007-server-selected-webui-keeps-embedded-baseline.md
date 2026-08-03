# 0007 服务端选择的 WebUI 保留 embedded baseline

- 状态：accepted
- 日期：2026-08-03
- 关联：`MOB-OWN-002`、`MOB-DATA-005`、`MOB-PAIR-003`、`MOB-PLUGIN-004`、`MOB-XREPO-004`、`MOB-REPO-001`、`MOB-UI-002`～`MOB-UI-004`
- amends：[0005](0005-shared-webui-source-lives-in-core-repository.md)
- Core 决策：`akasic-agent/docs/decisions/0022-mobile-webui-uses-server-selected-generations.md`

## 问题

0005 把共享 WebUI 源码集中到 Core，并把固定 ZIP 作为 APK 的独立构建输入。这保证离线构建和来源固定，但每次颜色、布局、抽屉 island 或既有桥交互变化都要发布 APK。让 WebView 直接打开远程页面又会让启动依赖网络，并把未完整验证的页面、导航和 bridge 权限放进同一个边界。

## 决定

1. 固定 ZIP 继续进入 APK，职责收窄为 embedded baseline、离线构建输入和最终回退，不再是已配对设备获得 WebUI 的唯一来源。
2. Core 拥有不可变 generation、Stable/Preview 和当前 `ReleaseView`。Android 通过固定 Core schema 执行已认证 Resolve，再从同一 endpoint 的 HTTPS 数据面按内容摘要补齐资源；WebView 只加载 app-private 本地可信 origin，不打开远程页面。
3. Android 只维护 `desired`、`ready`、`serving`、`fallback`，并以 `Resolve`、`Ensure`、`Present` 协调。网络下载与 WebView 激活由不同 owner 管理；`Present` 只在 UI session 边界使用已完整验证的 generation。
4. 每个服务端的 manifest、blob、generation、attempt 和 reject 状态相互隔离，不跨服务端共享 CAS。Room 新表只保存 WebUI metadata 与引用；既有消息、outbox、草稿、附件、阅读位置、配对和密钥表不进入迁移写集合。
5. candidate bridge 在 exact-origin、main-frame、nonce、generation、snapshot 和首帧健康提交前只允许只读握手。旧 WebView callback、iframe、错误 origin、外部 navigation 和 renderer 重复退出不得提交 serving 或执行副作用。
6. 清理未使用 UI 资源与重置单个服务端 UI 缓存是两个名称明确的动作。它们只减少派生 WebUI 文件和 metadata；embedded baseline、其他服务端和业务状态不在删除范围。
7. 纯 UI 与既有 capability 变化走 WebUI 发布；新增原生 capability 或不兼容协议仍走 APK。GitHub APK updater、安装确认和权限保持原状。
8. 一次成功 Resolve 只按 compatible Preview、compatible Stable、embedded baseline 选择 desired；两根指针均空或均不兼容时 desired=baseline。旧 serving 只维持当前 UI session 或临时解析失败期间的可用性，不反向成为服务端选择。
9. 首个 OTA APK 保留既有 schema 1 ZIP 作为 baseline，远端只接受 Core schema 2 manifest；baseline 不导入 OTA cache。保留数据强制降级旧 APK 不属于支持恢复路径，Room 不得 destructive migrate。
10. `Resolve/Ensure` 只有 `Ready`、`RetryAfter`、`WaitFor(trigger)` 和 `RejectTarget` 四种结果。同 Target 的空间等待和永久 reject 不因前台、重连或重复 hint 自动下载；必须等 Target/兼容指纹变化或名称明确的用户动作。手动重试只清除当前 `ReleaseView` 按 Preview→Stable 选出的确切 rejected Target，不匹配旧发布遗留 reject。重置记录该 Target 的 `manual_reset`，立即回到 baseline，不自动重下同一 Target。
11. candidate 由 application/process-scope attempt lease 持有，与已提交 serving 和 asset handler 当前 presentation 分权。Activity 重建不能提升 candidate；server switch 必须同步建立新 UI session；candidate 健康前 bridge 副作用和系统外链均关闭。健康窗口中 desired Target 变化时立即废止旧 attempt 并恢复已提交 serving/baseline。
12. 保存源码、构建成功、文件 watcher 或重启 Core 都不改变 `ReleaseView`。Stable、Preview、清除、提升和回滚必须由名称明确的发布命令提交；客户端只消费提交后的选择。
13. capability 是配对声明的一部分。首个需要 `mobile-webui-ota-v1` 的 Android binary 对旧授权执行一次显式重新配对，不由服务端按版本号补写设备记录；重新配对保留业务数据与按服务端隔离的可重建缓存。未来连接级 renegotiation 另立协议合同。
14. WebUI 的发送和插件查询采用 terminal receipt：当前 owner 接受或拒绝后必须恰好回一个结果。candidate、过期 lease 和关闭 admission 只拒绝副作用，不得静默挂起页面；embedded baseline 的空 generation ref 是合法 owner，必须能执行恢复动作。

## 理由

embedded baseline 保留 APK 的离线、可复现和恢复能力；不可变 manifest 与 per-file CAS 让 UI-only 发布按内容增量下载，而不在 serving 目录原位打补丁。三动作模型只分别回答服务端选择、本地完整性和本次 UI session 展示，避免让页面 modal、抽屉、选区或网络错误扩张为原生全局状态。

```text
┌──────────────── Core ────────────────┐
│ ReleaseView ── manifest ── CAS blobs │
└───────────────┬──────────────────────┘
                │ 已认证 Resolve / Ensure
┌───────────────▼──────────────────────┐
│ Android Store：desired / ready       │
│ Android Present：serving / fallback  │
└───────────────┬──────────────────────┘
                │ 本地可信 origin
┌───────────────▼──────────────────────┐
│ WebView：产品 UI；健康前无副作用能力 │
└──────────────────────────────────────┘
```

这不是自造一套“每次启动等网络”的热更新：它采用 [Expo 的异步下载、后续启动应用](https://docs.expo.dev/eas-update/download-updates/)和 [runtime compatibility](https://docs.expo.dev/eas-update/runtime-versions/)，采用 [Ionic 的逐文件摘要差量](https://ionic.io/docs/appflow/deploy/differentials/)，并以 [WebViewAssetLoader 的本地 HTTPS origin](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)承载已验证资源。Compose host 按 Android 官方的 [WebView in Compose 生命周期实践](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/wrap-webview-in-compose)在 `AndroidView.onRelease` 中释放已经退出 composition 的确切 WebView，不用观察可变引用的 effect 猜测 view 生命周期。Web→Native 不使用会暴露给所有 frame 的 `addJavascriptInterface`；采用可校验 origin/main-frame 的消息 envelope，风险依据见 [Android WebView native bridge security](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。这些来源只提供不变量，具体 owner、删除权限、激活 fence 和回退语义仍由本决定与 Core 设计共同拥有。

能力变化只在清楚的协议边界生效也不是移动端特例：[Language Server Protocol](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.18/specification.md)在 initialization 交换能力，[SSH Transport](https://www.rfc-editor.org/rfc/rfc4253.html)在每条连接的 key exchange 协商算法。本轮为降低一次性迁移复杂度，使用已有的配对边界重新声明 capability；这不等价于把“每次能力变化都重配对”确立为长期最佳方案，未来 renegotiation 必须同时定义授权、降级和旧客户端语义。

Google Play 现行政策允许 WebView/解释器中的 JavaScript 例外，但同时要求运行时加载的解释代码不得促成政策违规，并明确禁止从 Play 外下载 dex/JAR/.so；因此本决定只允许静态 Web 资源，不把它等同于“已经保证通过商店审核”，也不在本轮改动 GitHub APK updater。政策边界见 [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)。

## 持久化影响

| 对象 | 增加与更新 | 物理减少 | 恢复证据 |
|---|---|---|---|
| Room WebUI metadata | Resolve 写 desired；Ensure 提交 verified generation/blob；Present 事务写 attempt/serving/fallback/reject；`WaitFor(space)` 由 coordinator 在进程内持有 | 仅安全 GC 或用户明确重置此服务端 UI 缓存；必须先等待同 server owner，且只在物理文件删除成功后删引用 | schema identity、四事实、TargetKey、manifest digest、当前 Target 精确 retry oracle、wait oracle 与 migration preserve oracle |
| `filesDir/mobile-web-ui/<sha256(server_id)>/` 与根级同 hash reset journal | staging 完整校验后以 hash 原子发布 blobs/manifest；`<hash>.reset.json` 先记录精确 server/Target/fingerprint 再执行物理删除 | 只删未 pinned 对象、可证明属于已删除 profile 的派生 orphan root，或重置目标 server；物理删除先于 Room 引用减少，失败时保留 owner 并 fail-loud | embedded baseline、verified manifest/逐文件 SHA-256、`<hash>.reset.json`/`<hash>.reset.json.tmp`/`<hash>.reset-trash` 重放证据、删除失败后仍在的引用、GC/reset report |
| 业务 Room、attachments、shares、plugin cache、Keystore | 继续由既有 owner 改变 | 本决定不授权任何减少 | 更新前后 DB/file write-set 与现有 owner 测试 |

## 后果

- AppDatabase 增加只创建 WebUI 表与索引的向前迁移；不得使用 destructive migration、`clearAllTables()` 或业务表重建。
- RealtimeSession 增加 capability、Resolve/prepare 与 hint 路由；协议 snapshot 和 Runtime Contract 必须固定已合并 Core commit/tree/hash。
- WebView host 增加 generation-specific asset handler、activation health fence 和 renderer recovery；页面不能接触票据、服务端 URL、任意文件或缓存写权限。
- 设置入口可以显示当前来源、立即应用、清理未使用资源、精确重试当前被拒 Target 和重置当前服务端 UI 缓存；重置后必须由用户点“重新检查”才可重下同一 Target。GitHub APK 更新入口继续独立存在。
- capability 缺失时由原生 Snackbar 提供不依赖 WebView bridge 的重新配对入口；点击后进入扫码页，旧提示不再覆盖 PairingScreen。重新配对只清授权运行态，插件旧内存 owner 立即失效，磁盘缓存和业务状态继续保留。
- 未来 Swift/iOS 客户端可以消费同一 Core schema、ReleaseView 与内容摘要，但 WKWebView、native bridge、cache、activation fence 和系统能力仍由 Swift 客户端单独拥有；Android Room 或进程状态不能成为跨平台真源。
- reset 使用缓存根下的 `<hash>.reset.json`、`<hash>.reset.json.tmp` 与 `<hash>.reset-trash` sibling 路径协调物理文件和 Room 两个提交面，其中 `hash=sha256(server_id)`。每次 marker rename/delete、live→trash rename 和 trash 删除后都同步缓存根目录；同步失败保留 journal 与 Room owner。崩溃恢复先关闭该 server 的进程 presentation，再校验 journal 的 server/Target/fingerprint，完成物理删除后才重放 Room baseline/reject；若只剩 journal 清理失败，用户可见结果必须区分“重置已提交、清理待完成”与“重置失败”。

## 验证

- embedded baseline 在未配对、离线、无发布、目标不兼容和资源损坏时都能冷启动。
- 隔离 Gateway 发布 Preview/Stable 后，run-specific 真机包无需 APK 更新即可在下一 UI session 使用；连续发布只展示最新 desired。
- partial、进程死亡、旧 callback、renderer termination、重复 hint、server switch、revoke、低空间、GC 与 reset 的负向用例不产生混合 generation或业务状态变化。
- candidate/admission 拒绝会给发送与插件查询返回明确失败；embedded baseline 的重新配对 bridge 可达，旧 WebView 迟到回执不能解除新页面的 pending。
- Core 插件 reconcile 连续失败时不推进 revision、不发 catalog hint；同 revision 恢复成功后只发布一次最终目录通知。
- Core/Android 共用的 schema 2 golden manifest 覆盖 `.js/.mjs/.cjs/.css` 与未知后缀；两端固定 MIME map、canonical bytes、generation/manifest/target digest 必须一致。
- v12 数据真实迁移到新 schema 后，消息、outbox、草稿、附件、阅读位置、配对与密钥引用逐项保留。
- 正式 package、正式 Gateway、正式 workspace 和 GitHub updater 在隔离验收前后保持不变。
