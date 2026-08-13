# Akashic Mobile 长期需求与不变量

本文件只保存用户确认的长期语义。当前实现、方案、bug、验证结果和 Agent 推断不得写入这里。条款 ID 用于任务合同、Review 和决策引用。

## 1. 产品与能力所有权

### MOB-OWN-001 核心事实由核心仓库拥有

`akashic-agent` 拥有 SessionDB、运行 workspace、Gateway、服务端协议真源、插件和 MCP runtime。移动端是消费者，不能把本地投影、缓存或协议副本提升为核心事实。

### MOB-OWN-002 移动端拥有客户端能力

本仓库拥有 Android 客户端、WebView 容器与原生桥、客户端可靠投递状态、设备侧密钥、WebUI 下载/校验/缓存/激活、协议消费实现、测试和发布工具。共享对话 WebUI 源码、generation 和当前 Stable/Preview 选择由 `akashic-agent` 拥有；本仓库只消费固定来源、兼容范围和内容摘要，不维护第二份界面源码或服务端发布真相。

### MOB-OWN-003 核心补丁必须具有通用理由

移动端需求不能仅为适配单个客户端而修改核心 runtime。需要核心改动时，任务必须说明通用能力、受影响消费者、为什么客户端不能独立完成，以及跨仓库验收证据。

## 2. 服务端长期事实

### MOB-CORE-001 SessionDB 不因移动端生命周期减少

SessionDB 是核心长期状态。除非用户通过明确的删除或撤销操作授权，否则移动端断线、裁切、刷新、恢复、重新配对、授权失效和普通重构都不得减少服务端会话历史。

### MOB-CORE-002 移动端不能把缺失观察解释为删除授权

一次同步中没有看到某个会话、消息或附件，不足以证明服务端事实已删除。移动端只能按核心协议的明确终态或显式用户操作处理删除语义。

## 3. 移动端持久化

### MOB-DATA-001 服务端投影与本地工作必须分层

Room 中从核心重新拉取的会话、消息、turn block 和附件元数据属于服务端投影。投影可以由固定协议重新构建，但“可重建”不等于可以在任意错误恢复或普通刷新中随意删除。

### MOB-DATA-002 本地持久工作不得随投影重建删除

以下状态属于移动端自己的未完成工作或运行连续性，必须跨断线、进程重启和投影重建保留：

- outbox 命令；
- pending、failed、failed-retryable 和 outcome-unknown 消息；
- 编辑器草稿与引用目标；
- 待上传附件及其进度；
- 已接收但尚未消费或放弃的系统分享；
- 尚未明确消费的通知待办；
- 尚未得到确定结果的 turn stop 请求。

只有各自 owner 在协议确认、用户明确消费/放弃或已定义终态发生时，才能减少对应状态。

### MOB-DATA-003 缓存驱逐不得改变事实语义

接收附件和插件 UI 的可重建文件可以按明确容量策略驱逐；驱逐只能改变本地可用性状态，不得伪装成远端附件、插件能力或消息事实不存在。

### MOB-DATA-004 超长历史正文必须完整且可续传恢复

历史消息超过实时事件预算时，移动端仍必须恢复完整 UTF-8 正文，并保持该消息的 thinking、tool block、顺序和身份不变。正文通过当前已认证连接授权的同源 HTTPS Range 分段恢复；已确认的文件偏移跨断线和进程重启保留，只有字节长度与 SHA-256 同时匹配时才替换预览。失败必须保持可重试状态，不得把预览、部分文件或摘要不匹配的内容标记为完整正文。

### MOB-DATA-005 WebUI 缓存只保存可验证的派生界面

每个服务端的 WebUI manifest、blob、verified generation、desired/serving/fallback/attempt marker 和 rejected target 是移动端派生状态，不得进入消息、outbox、草稿、附件、配对或插件表。下载先写 app-private staging，逐项完成 schema、兼容范围、路径、MIME、大小和 SHA-256 校验后才提交 verified generation；同 digest 的重复 path 必须具有相同 size/MIME，partial、orphan 和进程中断都不得变成 ready。`WaitFor(space)` 是 coordinator 持有的进程内协调事实，不得混入业务表或伪装成已下载。

GC 只能删除未被 serving、fallback、当前 ready desired 或 attempting 引用的对象，且只有物理文件删除成功后才能删除 metadata/reference owner；已不属于任何 profile、没有 metadata 且没有同 hash reset journal 的合法 server-hash root，才可作为已删除 profile 的派生 orphan 删除，未知目录必须保留并计入全局预算。物理删除失败必须 fail-loud，不得报告已释放空间。用户执行名称明确的“重置此服务端 UI 缓存”时，owner 必须先取消并等待该服务端的 Resolve/Ensure/Present，持久化绑定 server/Target/fingerprint 的 `<hash>.reset.json`，完成物理缓存删除后才事务减少对应 metadata/reference，并立即回到 embedded baseline。marker、live root 与 trash 的 rename/delete 后必须同步缓存根目录项；同步失败保留 journal/Room owner。重置不自动重下同一 Target；用户必须随后对当前服务端 Target 执行“重新检查”。该动作不得删除其他服务端缓存、配对、密钥、消息、草稿、outbox、阅读位置、附件或插件状态。

manifest/blob 本地验证、删除或 WebUI metadata 写入失败时，WebUI coordinator 必须保留 serving/attempt owner 并暴露可观察错误或有界重试。这类派生 UI 缓存故障不得被当成 realtime 协议错误而断开消息连接。

### MOB-DATA-006 协议错误不得封死服务端投影重建入口

已配对且设备授权仍有效的用户必须能在连接校验失败、同步中断或重连期间主动执行“清理缓存并同步”。该入口只清除可从核心重建的服务端投影并保留配对、本地未发送工作、草稿与上传附件；设备已撤销、活动 Turn、附件下载或已经开始的重建仍可阻止执行。错误连接上的重建先提交本地清理，再通过既有认证重连和 durable cursor 恢复，不要求连接先进入 `READY`。

## 4. 配对与删除

### MOB-PAIR-001 配对生命周期不是删除生命周期

断线、重新配对、切换服务器和服务端撤销设备授权只改变连接或授权状态，不删除服务端内容，也不默认删除手机上的投影、本地工作或设备资料。

### MOB-PAIR-002 重新配对必须保留恢复能力

重新配对同一服务端时应继续识别并恢复已有本地状态。配对另一服务端时，旧服务端资料保持独立，不得作为建立新连接的副作用被清除。

### MOB-PAIR-003 配对能力声明只在明确边界更新

服务端授权记录中的设备 capability 来自配对声明，不得由普通重连、客户端版本号或服务端推断静默改写。当前授权缺少客户端必需 capability 时必须 fail-loud，并提供原生“重新配对”入口；重新配对只替换授权身份与连接 profile，继续保留消息投影、outbox、草稿、附件、阅读位置、设备侧可重建缓存和服务端历史。

重新配对开始时，移动端必须立即停止旧服务端插件目录的查询与发布并清空其进程内 catalog owner；按服务端隔离的插件 catalog 与 asset 磁盘缓存继续保留，只有新授权完成同步后才能重新激活。未来若引入连接级 capability renegotiation，必须作为独立协议语义审批，不能把一次性兼容迁移扩张成隐式授权更新。

### MOB-DEL-001 破坏性清除必须另行定义和确认

普通连接、配对、同步和修复路径不得拥有整服务器级联删除能力。若未来增加“忘记此服务器的本地资料”，必须作为名称明确的独立破坏性功能，先定义影响清单、未完成工作的处理、设备密钥策略、恢复与审计；它永远不代表删除服务端内容。

## 5. 插件、Skill 与 MCP

### MOB-PLUGIN-001 安装和运行由核心插件系统拥有

插件、Skill 和 MCP 通过核心插件系统安装和运行。移动端不复制插件安装状态，不直接加载开发者本机缓存，只消费核心公开的协议、catalog、资产和交互结果。

### MOB-PLUGIN-002 插件 UI 缓存不是插件真源

移动端插件 UI 资产、catalog 和 result cache 只是带版本或内容身份的消费者缓存。缓存缺失或驱逐必须通过核心重新获取，不能改变插件是否安装或能力是否存在的判断。

### MOB-PLUGIN-003 大型插件查询必须显式选择数据面

插件目录、资源版本、查询授权、取消和实时事件继续使用已认证 WebSocket。只有插件 module 显式声明 HTTPS 时，移动端才把只读查询正文发送到当前活动 endpoint 派生的同源 HTTPS 地址；未声明的插件保持既有 WebSocket 内联查询，不能被客户端静默迁移或 fallback。

HTTPS 请求必须复用当前 endpoint 已建立的 LAN pin 或 tunnel system trust，拒绝 absolute path、redirect 和超过固定上限的响应。短期 ticket、HTTP response 和 result cache 都是可重建消费者状态，不得提升为服务端插件事实；Native 到 WebView 的 catalog 与 result 使用异步消息桥，不能通过同步 JavaScript 注入大 JSON。

### MOB-PLUGIN-004 插件目录通知只跟随已确认重载

核心插件 watcher 只有在当前 revision 完成 reconcile 后才能发布移动端 catalog changed 通知。扫描或 reconcile 失败时保留上一个已确认 revision 并继续有界轮询；不得推进 revision、发布失败状态对应的 hint，或要求移动端通过清缓存恢复。移动端重连仍主动拉取当前 catalog，非持久 hint 只负责缩短已连接设备看到成功变更的延迟。

## 6. 跨仓库兼容性

### MOB-XREPO-001 兼容证据必须固定且可复现

协议与核心 runtime 的兼容性必须固定来源 repository、commit、tree、schema digest 和场景目录 digest。浮动分支、`latest`、开发者本机 checkout 和安装缓存状态不能作为发布证据。

### MOB-XREPO-002 同步与异步属于协议语义

跨仓库接口从同步改为异步、确认改为接受、返回值改为事件或终态发生变化时，必须视为语义变化，而不是普通实现细节；生产者与移动端消费者需要在同一固定组合上通过场景验收。

### MOB-XREPO-003 主动消息的实时与历史投影共享身份

核心为同一条主动消息的实时事件和发送成功后的历史消息提供同一个稳定投递身份。移动端必须优先按该身份合并为一条本地消息；内容与时间匹配只兼容没有稳定身份的旧协议数据，候选不唯一时不得猜测或删除。历史投影尚未到达时，移动端使用该投递身份引用主动消息；历史完成 canonical 化后继续使用服务端 message ID，不把本地临时 ID 发送给核心。

### MOB-XREPO-004 WebUI 发布只消费固定 Core schema

WebUI `ReleaseView`、Target、manifest、短期 ticket 和 HTTPS 资源协议以 Core schema 为唯一真源。本仓库必须固定 source repository、完整 commit/tree、schema path/hash 和实际 provider runtime；不得用 Kotlin 类型、设计文档、浮动分支或本机 checkout 反向定义协议。`release.changed` 只触发新的已认证 Resolve，客户端不得把 hint、sequence、时间或 semver 当作可下载 target 或新旧排序依据。

### MOB-XREPO-005 Turn 与 Attempt 身份分别校验

`Turn` 是用户可理解的逻辑工作单元，`Attempt` 是其内部一次可中断、可重放的执行。Mobile 实时事件的 `turn_id` 标识 Attempt，`control_turn_id` 标识逻辑 Turn；客户端必须用 Attempt 维持流式生命周期，用逻辑 Turn 与 canonical history 合并，不得要求跨 Attempt 的两个身份相等，也不得在任一身份内部接受漂移。缺少 `control_turn_id` 的旧协议事件继续把 `turn_id` 作为逻辑身份兼容。

## 7. 仓库与安全边界

### MOB-REPO-001 移动端仓库可独立构建

移动端必须能脱离核心源码 checkout 独立获取依赖、测试、构建和发布。embedded baseline 以仓库内固定 ZIP、source manifest 和 SHA-256 作为 APK 构建输入；服务端 generation 只在应用运行且完成配对认证后写入 app-private cache。Gradle、CI 和发布不得依赖开发者本机绝对路径、未固定父仓库状态或网络下载才能构建 baseline。

### MOB-SEC-001 公共仓库不保存秘密

APK 签名材料和密码、OAuth、配对秘密、设备私钥、服务端密钥及生产配置不得提交到本仓库。真实设备验证必须使用隔离 application ID、隔离 Gateway workspace 和临时配对材料，不能覆盖正式应用或线上数据。

## 8. 诊断证据

### MOB-DIAG-001 崩溃诊断默认本地且有界

正式版和测试版只在未捕获异常，或既有错误处理边界确认协议、状态、数据库、文件失败时，原子覆盖同一份固定容量的最近事故记录。事故记录保留完整 Throwable frame、cause 和 suppressed 链；只有超过硬容量上限时才显式标记裁切。用户导出时只附带最近一次事故和操作系统提供的最近一次退出原因，不保存或导出历史列表。诊断不得常驻采样、自动上传、包含会话正文、吞掉业务错误或改变原始崩溃处理；只有用户主动导出后，报告才可以离开应用私有目录。

## 9. 通知、更新与界面层级

### MOB-NOTIFY-001 应用前台消费消息通知

用户从桌面或通知入口进入应用并到达前台时，移动端清除已经发布的消息通知；前台连接服务的常驻通知继续保留。该动作只消费系统通知展示，不删除消息、会话或服务端事实，也不替代会话内部基于可见内容推进的已读水位。

### MOB-RELEASE-001 Android 更新来自公开移动端仓库

Android 客户端只从 `kachofugetsu09/akashic-mobile` 的公开 GitHub 稳定 Release 检查更新。Release 必须使用 `vMAJOR.MINOR.PATCH` tag，提供名称与 tag 精确对应的已签名 APK 和 GitHub SHA-256 摘要；客户端验证版本、大小和摘要后才交给 Android 系统安装器，并始终保留用户的安装确认与“安装未知应用”授权。

### MOB-UI-001 模态抽屉拥有最高导航层级

会话抽屉打开时位于对话内容、顶栏连接状态和运行状态之上；运行状态使用 Material 3 全圆角胶囊容器，不得穿透抽屉或遮挡抽屉内容。

### MOB-UI-002 共享 WebUI 产物必须固定来源并校验

APK embedded baseline 只能打包由干净 `akashic-agent` commit 构建的共享 WebUI ZIP。产物必须记录 source repository、commit、tree 与资产摘要；Gradle 在解包前校验外部 SHA-256 和内嵌 manifest，不匹配时 fail-loud，不从网络、本机父目录或旧 build cache 回退。首个 OTA APK 的 embedded loader 继续只消费现有 ZIP manifest schema 1，远端 OTA parser 只消费 Core 固定的 generation manifest schema 2；baseline 不导入 OTA Room/CAS，两个 schema 不互相 fallback。OTA 更新失败、未配对或离线时继续使用 baseline，不把旧 build cache 或远程页面伪装成 baseline。未来远端 schema 变化必须进入 compatibility gate；保留数据的 APK 强制降级不是受支持恢复路径，且不得以 destructive Room migration 伪装兼容。

### MOB-UI-003 服务端 WebUI 通过 Resolve、Ensure、Present 收敛

已配对客户端只从当前服务端身份读取已认证 `ReleaseView`，在同一认证边界下载 manifest 和按文件摘要寻址的资源，再从本地可信 origin 创建 WebView。客户端只维护 `desired`、`ready(desired)`、`serving` 和 `fallback` 四个事实，并以 `Resolve`、`Ensure`、`Present` 三个幂等动作协调；不得建立把检查、下载、等待页面状态和激活串成一条全局状态机。

Preview 优先于 Stable；一次成功 Resolve 中两者不兼容或不存在时，desired 明确为 embedded baseline。旧 serving 只可维持尚未结束的 UI session，或在 Resolve 因临时网络/认证失败时继续显示，不能反向成为成功 Resolve 的 desired。进程冷启动恢复 serving/fallback 时必须重新按当前 native build、bridge、snapshot 与 platform 指纹核对本地已验证 manifest；不兼容 generation 在创建 WebView 前拒绝并回到兼容 fallback 或 embedded baseline，不能因它曾健康提交就跨原生协议升级继续展示。新 target 取代旧 Ensure，迟到结果用本地 owner token 丢弃；`Present` 不等待网络，只在新 UI session 或用户明确立即应用且原生 `canReplaceUi` 成立时切换。candidate 完成 exact-origin/nonce/generation/协议握手、完整 snapshot、root render 和 visual acknowledgment 前不得取得发送、文件、系统或插件副作用能力。

`Resolve/Ensure` 只能产生 `Ready`、`RetryAfter`、`WaitFor(trigger)` 或 `RejectTarget`。同 Target 进入 `WaitFor(space)` 后，前台、重连和普通 hint 可重新 Resolve 当前选择，但不得重复 prepare/manifest/blob；只有 Target 变化、显式清理、用户明确重试、reset 或 revoke 才解除等待。同 Target 的永久 reject 只能由 Target/兼容指纹变化，或用户对当前 `ReleaseView` 按 Preview→Stable 选出的确切 rejected Target 显式重试解除，不轮询。旧 ReleaseView 遗留的 reject 不得让设置页显示幽灵重试入口，也不得因用户重试新 Target 而被顺带清除。

candidate 由 application/process-scope attempt lease 持有，与已提交 serving 和 asset handler 当前 presentation 分权。Activity 旋转/配置重建时必须继续同一 candidate lease 和 `admission=false`；切换 server 必须在新 server 首帧前同步建立新 UI session。candidate 健康前不得打开系统外链 Activity。若健康窗口中 Resolve 得到不同 Target，立即废止旧 attempt 并恢复已提交 serving/baseline，不得等旧 candidate 超时或自报健康后再处理。

WebUI 发起的发送与插件查询，在发起 WebView 生命周期仍有效时，必须由当前 WebView owner 收敛为一个明确成功或失败结果。candidate、过期 lease、关闭的 admission 或无法入主线程队列都必须显式拒绝，不能静默丢弃并让仍存活的页面永久等待；旧 WebView 的迟到成功不得改变新 owner。owner release 销毁发起 WebView 时，该页面的 pending 随页面取消，结果不得转投新的 owner。embedded baseline 使用 `generationRef=null` 作为合法代际身份，必须保留重新配对、设置和发送等恢复 bridge 的可达性。

### MOB-UI-004 UI-only 更新与 Android 二进制发行分开

颜色、排版、抽屉、island、组件组合和只调用既有 bridge capability 的交互可以由服务端发布新 WebUI，不要求发布 APK。新增原生系统能力、改变 bridge/snapshot 兼容范围、WebView 生命周期、Room、网络或安全逻辑时仍必须发布 Android binary，并用 manifest `minimum_native_build` 阻止旧版本加载。

GitHub APK 检查、下载、摘要验证、系统安装确认和 `REQUEST_INSTALL_PACKAGES` 继续由现有独立 owner 管理。WebUI OTA 不得自动安装 APK、改变该入口或提前替换为 Google Play 发行策略。

### MOB-UI-005 会话模型选择只消费 Core 权威目录

移动端从当前已认证 Core 连接按会话读取模型 generation、默认 runtime、可选 runtime、能力和已提交选择，不维护模型名称、Provider 或思考强度的第二份静态目录。模型胶囊只展示该投影；选择在本地校验后随下一条用户消息进入同一可靠 outbox 命令，由 Core 的会话 owner 提交。

旧客户端未携带模型字段时必须保留现有会话选择；新客户端显式发送空 runtime 表示恢复跟随全局默认。目录刷新、切换会话、断线和迟到 reply 不得把一个会话的选择写给另一个会话，也不得伪造发送成功。
