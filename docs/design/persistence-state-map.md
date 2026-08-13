# 持久化状态地图

- 状态：current
- 证据基线：本文所在 Git tree

本文描述当前实现怎样增加和减少数据。长期授权边界见 `../projectneed.md`。

## 状态总览

| 状态 | 位置 | 角色 | owner | 可重建 |
|---|---|---|---|---|
| 服务端会话历史 | 核心 workspace 的 SessionDB | 权威长期事实 | `akashic-agent` | 否 |
| 服务端 profile 与连接元数据 | `akashic-mobile.db` | 本地连接身份 | 移动端 | 部分；密钥不可由远端盲目重建 |
| 会话、消息、turn、远端附件元数据 | `akashic-mobile.db` | 服务端投影 | 核心事实、移动端投影 owner | 是，来自固定协议 |
| outbox、失败消息、草稿、通知、stop | `akashic-mobile.db` | 本地长期工作与连续性 | 移动端 | 否，不能从服务端完整重建 |
| 上传附件 | `filesDir/pending-attachments` + Room | 本地未完成工作 | 移动端 | 否 |
| 待处理系统分享 | `filesDir/incoming-shares` + SharedPreferences | 本地未完成工作 | 移动端 | 否 |
| 接收附件 | `filesDir/received-attachments` + Room | 可驱逐消费者缓存 | 移动端 | 是，远端仍存在时 |
| 历史正文续传 | `filesDir/message-content` + Room | 可重建传输状态 | 移动端 | 是，来自核心 SessionDB |
| 插件 UI 资源 | `filesDir/plugin-ui-cache/*` | 派生消费者缓存 | 核心能力、移动端缓存 owner | 是 |
| 服务端 WebUI OTA | Room v15 专属 metadata + `filesDir/mobile-web-ui/<sha256(server_id)>/` | 身份隔离的派生 UI 缓存与激活证据 | Core 发布选择、移动端 Store/Present owner | 是；embedded baseline 独立保留 |
| 当前选择和 UI 设置 | Preferences DataStore | 可变本地设置 | 移动端 | 否；可重置但不代表事实删除 |
| 设备私钥 | Android Keystore | 本地身份秘密 | 操作系统/移动端 | 否 |
| 文本分享临时文件 | `cacheDir/shared-text` | 临时控制文件 | 移动端 | 无需重建 |
| 崩溃诊断 | `filesDir/diagnostics` | 有界诊断证据 | 移动端 | 否；仅用于定位设备侧失败 |
| 待安装 APK | `filesDir/updates` + SharedPreferences | 用户发起的短期更新缓存 | 移动端 | 是；来自固定公开 Release |

## 增减规则

### 核心 SessionDB

- 增加：核心在用户或 Agent 产生新会话事件时追加事实。
- 更新：只由核心拥有的 metadata/状态协议执行。
- 物理删除：只能由核心明确的用户删除或管理动作授权；移动端生命周期无权触发。
- 恢复：以核心备份和 SessionDB 为准，移动端 Room 不能反向恢复核心真源。

### Room 服务端投影

- 增加：配对后的目录、历史和 realtime 事件 upsert 会话、消息、turn 和附件元数据。
- 更新：delivery、stream、read position、cursor 和下载状态按各 DAO 状态机更新。用户/outbox 的 `clientMessageId` 与 assistant turn 的 `turnClientMessageId`、`controlTurnId` 分列保存，前者继续拥有唯一索引；`LocalDeliveryStore` 在同一事务中拥有“每个会话至多一个 streaming assistant turn”不变量；重叠 `turn.started` 在写消息和推进 cursor 前失败。v13→v14 只把 assistant 的旧 `clientMessageId` 移到 `turnClientMessageId` 并清空原列，用户/outbox 身份不变；v14→v15 只为 `assistant:<turnId>` streaming 临时行回填 `controlTurnId`，不改正文或终态。
- 逻辑失效：附件缓存以 `evicted` 表达文件不可用；消息投递使用明确状态，不用缺行伪装终态。v10→v11 迁移只把同一会话中较旧的重复 streaming 临时消息更新为 `interrupted`，同步结束其 running blocks，并保留最新活动投影、消息正文和全部 turn blocks。
- 物理删除：`reloadFromServer` 只允许清理可重载投影，并通过查询保护带本地工作的消息和会话。
- 恢复：从固定核心协议重新同步；正常重连可从本地连续 `serverSeq` 投影的首个不完整页续传，投影完整时不重放；投影不连续、数量异常、核心要求 reset 或用户在协议错误状态主动执行“清理缓存并同步”时从第一页重建。错误连接上的用户重建先提交可重载投影清理，再重连并从既有 durable cursor 恢复；失败必须暴露，不能用空列表冒充成功。

### Room 本地工作

- 增加：发送、编辑草稿、接收最终通知、请求停止和准备附件时创建记录。
- 更新：owner 通过条件 UPDATE 推进 pending、in-flight、retry、failed、unknown、sent 或消费状态。
- 逻辑失效：明确 ack、可证明终态、用户消费或用户放弃后才进入可清理状态。
- 物理删除：outbox 仅在确认后删除；通知在系统发布成功或策略明确抑制后消费；stop 在确定完成后删除；草稿和待办由对应用户动作删除。
- 恢复：应用启动重置不确定的 in-flight 状态并重放；不能从服务端重建未发送意图。

### 上传附件与系统分享

- 增加：在外部 URI 授权仍有效时复制到应用私有目录，再提交记录；文件与记录必须共同拥有。
- 更新：附件上传进度与状态原位推进；系统分享保存目标会话和冻结后的草稿合并结果。
- 物理删除：附件确认发送、用户移除草稿或分享内容明确消费/放弃后删除；文件删除失败会 fail-loud。
- 恢复：以私有文件和持久记录共同存在为准；任何一侧缺失都是数据损坏，不静默跳过。

### 接收附件与插件 UI 缓存

- 增加：按远端身份和内容身份写入 staging，校验完成后发布。
- 更新：访问时间、下载进度和缓存状态允许原位改变。
- 物理删除：只有容量驱逐、未引用清理或废弃临时文件清理可以删除；删除后保留足以重新获取的远端身份或 catalog 事实。
- 恢复：从核心重新下载；核心不可达时表现为缓存不可用，不改写远端事实。

### 服务端发布的 WebUI 派生缓存

- 对象：Room v15 的 `mobile_webui_state`、`mobile_webui_generations`、`mobile_webui_blobs`、`mobile_webui_rejects`，`filesDir/mobile-web-ui/<sha256(server_id)>/` 下的 manifest、content-addressed blobs、verified generation 与 partial，以及缓存根下的 `<hash>.reset.json`、`<hash>.reset.json.tmp`、`<hash>.reset-trash` 三类 reset journal 路径。embedded baseline 仍位于 APK assets，不进入这些表或目录；`WaitFor(space)` 是 coordinator 持有的进程内协调事实，不是 Room 行。
- 增加：成功的已认证 Resolve 只写 desired metadata；Ensure 先在同 server staging/partial 写入，完成 schema、路径、MIME、大小、canonical bytes 与逐项 SHA-256 校验后，才增加 verified generation/blob metadata；Present 在加载 candidate 前持久化 generation+nonce attempt。
- 更新：每个 server 的 desired、serving、fallback、attempt、reject、访问时间和下载确认偏移由对应 Resolve/Ensure/Present/Store owner 原位推进；同 Target 的进程内 wait 由 coordinator 更新。schema v12→v13 只创建 WebUI 表/索引；v13→v15 的 turn identity 分列迁移不改变 WebUI 表及文件；baseline schema 1 与远端 manifest schema 2 由不同 parser 拥有。
- 逻辑失效：新 ReleaseView 令旧 desired 失效；健康提交替换 attempt；TargetKey 或 native/WebView compatibility fingerprint 改变，或用户对当前 Target 明确手动重试，才可解除对应 reject。同 Target 的 `WaitFor(space)` 不因前台、重连或普通 hint 失效；只有 Target 变化、显式清理、用户明确重试、reset 或 revoke 清除。两个远端指针均为空时 desired=baseline，旧 serving 不因此成为新 desired。
- 物理删除：安全 GC 只删除未被 serving、fallback、当前 ready desired、attempting 或当前下载 owner pin 的派生对象；已不属于任何 `server_profiles.serverId`、没有 WebUI metadata 且没有同 hash reset journal 的 64 位十六进制 server root，才可作为已删除 profile 的派生 orphan 先物理删除。未知目录或无法证明 owner 的文件保留并计入全局预算。所有引用减少都必须发生在物理删除成功之后，失败时保留 owner 并 fail-loud。“重置此服务端 UI 缓存”先取消并等待该 server owner，持久化 `<hash>.reset.json`，再把 live root 原子移到 `<hash>.reset-trash` 并完成物理删除，最后事务写 baseline 与 `manual_reset` reject。marker rename、live→trash、trash 删除和 marker 删除都必须同步缓存根目录项；同步失败保留 journal/Room owner 并 fail-loud。重置立即回到 embedded baseline，不自动重下同一 Target；只有用户随后对当前服务端 Target 执行“重新检查”才解除 `manual_reset`。不得触及 embedded baseline、其他 server、消息、outbox、草稿、阅读位置、附件、配对、Keystore、插件 cache 或待安装 APK。
- 恢复：启动先关闭对应 server 的进程 presentation，再严格校验 `.reset.json` 中的 server/Target/fingerprint 并重放未完成 reset；只剩 journal 删除失败时状态为“重置已提交、清理待完成”，不能伪装成完整失败或再次删除业务状态。其余路径只删除可证明未提交的 `.tmp`/orphan，验证 Room identity、manifest 与全部 member hash；遗留 attempt 回到 verified fallback/baseline，并拒绝相同 TargetKey+compatibility fingerprint。无法证明完整的 generation 绝不成为 ready/serving，只能从当前已认证 Core 重新 Ensure。

### 历史正文续传

- 增加：`history.page` 携带 `content_ref` 时创建传输记录；HTTPS Range 响应先追加到消息身份对应的私有临时文件。
- 更新：每段正文落盘并 `fsync` 后才推进 Room 的确认偏移；启动时截断超过确认偏移的尾部。正文未完成时，消息投影保留服务端预览，thinking 与 tool block 按正常历史事务落库。
- 逻辑失效：完整文件的字节长度、SHA-256 和严格 UTF-8 解码全部通过后，在同一 Room 事务中替换消息正文并删除传输记录。
- 物理删除：校验成功、服务端投影被明确重载、应用数据被用户清除或无对应传输记录的孤立临时文件才可删除；普通断线和进程退出不得删除已确认片段。
- 恢复：重新连接后从 Room 确认偏移重新申请短期 ticket 并续传；摘要或响应边界不一致时保留失败状态并暴露错误，不发布部分正文。

### 配对 profile 与设备密钥

- 增加：pair accepted 后写入 profile，并在 Android Keystore 创建或复用设备 key alias。
- 更新：当前选择可清空或切换；重新配对不会删除 Room profile 或密钥。
- 物理删除：当前 `ServerProfileDao` 不提供整 profile 删除能力。未来若实现本地资料清除，必须先按 `MOB-DEL-001` 定义独立破坏性入口和完整影响协议。
- 恢复：profile 信息可重新配对获得；设备私钥不可重建，丢失后需要建立新设备身份。

### 崩溃诊断

- 增加：未捕获异常或 Realtime 既有错误处理边界确认的功能阻断错误原子覆盖 `last-incident.txt`，其中保存构建、设备、失败上下文和完整 Throwable frame、cause、suppressed 链；超过 512 KiB 字符上限时显式标记裁切。
- 更新：所有应用事故共享一份最近记录，不追加无界历史；用户主动导出时临时读取 Android 提供的最近一次系统退出原因及其有界 trace，不把退出历史另存一份。
- 逻辑失效：新的事故原子覆盖旧记录后，旧证据不再有效。
- 物理删除：从旧版升级时把 `last-crash.txt` 与 `last-runtime-error.txt` 中较新的一份迁入统一记录，并删除旧分散文件及 `exit-history.txt`；之后只有卸载、清除应用数据或新事故覆盖会减少旧证据。正常会话、同步和配对流程不操作诊断目录。
- 恢复：`AtomicFile` 在覆盖中断后恢复上一份完整记录；诊断不能从服务端重建，用户可通过应用内系统分享页主动导出，应用不自动上传。

### 待安装 APK

- 增加：用户在设置页确认下载后，从公开移动端仓库的稳定 GitHub Release 写入 `.part`，按 Release 大小与 SHA-256 校验后原子发布到 `filesDir/updates`，再同步记录版本、路径、大小、摘要与下载时间。
- 更新：下载进度只存在于当前设置页；授权页面导致 Activity 重建时，以持久记录和已验证文件恢复安装流程。
- 物理删除：摘要不一致、文件损坏、目标版本已安装或缓存超过 24 小时时删除对应 APK 并清除记录；交给系统安装器后清除待办，Release 仍可重新下载。
- 恢复：只能从固定公开 Release 重新下载；更新缓存不影响会话、消息、配对或核心事实。

## 备份与恢复边界

仓库只保存 schema 和实现，不保存用户数据库、附件、密钥或生产配置。业务代码或迁移变更前，应在隔离 application ID/workspace 中验证；正式设备数据的备份恢复策略目前不是本仓库已实现能力，且应用声明 `allowBackup=false`，不能声称 WebUI cache、Room 或 Keystore 已被 Android 自动备份。OTA cache 是可重新获取的派生数据，但这不授权删除或重建同库中的业务表；保留数据强制降级到旧 Room schema 不是支持的恢复路径。
