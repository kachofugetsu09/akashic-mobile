# Akashic Mobile

Akashic 的 Android 客户端与移动 WebView 容器。共享对话 WebUI 源码位于 `akashic-agent/frontend/chat`；本仓库固定并校验 APK 内的 embedded baseline，并在运行时消费已配对 Core 选择的 WebUI generation。

本仓库可以独立测试、构建和发布 APK；服务端协议真源仍由 `akashic-agent/schema/` 维护。

## 本地验证

```bash
cd clients/android
./gradlew :app:verifyMobileWebArchive
cd ../..
python3 -m unittest discover -s runtime-gate -p 'test_*.py'
python3 runtime-gate/verify_contract.py --mobile-root .
cd clients/android
./gradlew testDebugUnitTest lintDebug assembleDebug
./test-device-gate.sh
```

`protocol/source.json` 固定客户端实现对应的历史协议快照；`runtime-gate/` 另外固定实际验收的核心 revision、tree 和 36 个跨仓库语义场景。两者变化时必须重新运行 runtime contract Gate，不能用浮动分支或本机核心 checkout 代替。

`clients/android/mobile-web/source.json` 固定 embedded baseline 的源码 commit/tree，旁边的 ZIP 与 SHA-256 是 Android 的独立构建输入。只有发布新的 Android binary 时才从已合并且干净的 `akasic-agent` commit 重建并同步这三个文件；服务端 WebUI generation 不反向修改 APK baseline。

## WebUI 发布边界

运行时更新只回答三个问题，不把检查、下载和页面替换串成一条全局状态机：

```text
┌─────────┐      ┌─────────┐      ┌─────────┐
│ Resolve │ ───▶ │ Ensure  │ ───▶ │ Present │
│ 服务端要什么 │      │ 本地是否完整 │      │ 本次会话展示什么 │
└─────────┘      └─────────┘      └─────────┘
     │                │                 │
     └─ 临时失败沿用当前 UI ─────────────┘
                      └─ 未就绪不替换；APK baseline 永久可用
```

颜色、排版、抽屉、island、组件组合和只调用既有 bridge capability 的交互，由 Core 显式发布 Stable/Preview generation，手机按内容摘要增量补齐，不需要重新发 APK。保存源码、构建成功、文件 watcher 或重启 Core 都不会自动发布；这条显式提交边界让 Preview、提升和回滚可审计。新增原生能力、改变 bridge/snapshot 兼容范围、Room、WebView 生命周期、网络或安全逻辑时，仍必须发布 Android binary。Preview 不理想时由 Core 清除 Preview 或回滚指针；客户端下一次成功 Resolve 收敛，不维护任意本地版本选择器。

| 变化 | 唯一 owner | 是否发布 APK |
|---|---|---|
| 颜色、排版、抽屉、island、组件组合、既有 bridge 交互 | Core WebUI generation + Stable/Preview | 否 |
| 当前服务端选择、Preview 清除/提升/回滚、资源数据面 | Core 发布者 | 否 |
| Resolve/Ensure、摘要校验、缓存、GC/reset、session-boundary Present | Android Kotlin | 是 |
| 新系统能力、bridge/snapshot、Room、WebView 生命周期、网络与安全策略 | Android Kotlin | 是 |
| GitHub APK 检查、下载、摘要验证和系统安装确认 | 既有 Android updater | 维持现状 |

客户端不直接打开远程页面。资源经当前配对身份授权下载、完整校验后进入 app-private cache，再由本地可信 HTTPS origin 加载；未配对、离线、无发布、不兼容、下载失败或激活失败时继续使用已提交 UI 或 embedded baseline。完整合同与 edge case 矩阵由 Core 的 `docs/design/server-published-mobile-webui.md`、本仓库的 `docs/decisions/0007-*` 和 `docs/projectneed.md` 共同约束。现有 GitHub APK 检查、下载和安装入口保持独立且原样保留。

### 首次 OTA 升级与重新配对

首次从没有 `mobile-webui-ota-v1` 声明的旧授权升级时，需要明确重新配对一次。App 收到 `capability_required` 后会显示原生“重新配对”操作；它不依赖 WebView bridge，点击后进入扫码页。重新配对不会清除服务端会话、手机消息投影、outbox、草稿、附件、阅读位置、插件资源或已验证 WebUI 缓存，不需要清应用数据或让服务端删除设备历史。

发送和插件查询在 WebUI candidate 激活窗口不会再无限等待：原生未准入时立即回明确失败，页面恢复按钮并保留草稿；消息一旦进入本地 outbox，断线或进程恢复继续使用同一 command id 幂等重放。重新配对前旧服务端的插件内存目录立即失效，新授权同步完成后再从保留的按服务端缓存校验并激活。

通知权限提示可以关闭；“开启”会先打开应用通知页，厂商 ROM 不支持时回退到应用详情页，两种系统入口都不可用才显示错误。

## WebUI Pilot APK

真机视觉验收使用独立包名和应用名，不覆盖正式 Akashic：

```bash
cd clients/android
./gradlew \
  -PakashicDebugApplicationIdSuffix=.webuipilot \
  -PakashicDebugAppName='Akashic WebUI Pilot' \
  :app:assembleDebug
```

产物仍位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `com.akashic.mobile.webuipilot`，使用 Android debug 签名且只用于本地验收。

## 真实设备 Gate

禁止直接在个人设备上运行 `connectedDebugAndroidTest`。它使用固定包名，不能在安装前证明候选不会覆盖已有应用或测试数据。

`clients/android/device-gate.sh` 会为每次运行生成独立的 `com.akashic.mobile.review.<run-id>` 包名。脚本先从实际 APK 读取 app、test 与 instrumentation target，确认设备无包名碰撞后再以禁止替换的方式安装。每个 `--test` 都作为独立 phase 执行；需要验证重启时，phase 之间会显式 force-stop。只有指定方法恰好执行一次、清理 run-specific 包完成后，Gate 才写出 `gate_result=passed`。

```bash
cd clients/android
ANDROID_HOME=/path/to/android-sdk ./device-gate.sh \
  --serial DEVICE_SERIAL \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#processRestartResumesWithoutHistoryDuplicates' \
  --runner-arg pairingOfferBase64=EPHEMERAL_OFFER \
  --runner-arg historySessionId=isolated-session-id
```

报告位于 `clients/android/build/reports/device-gate/<run-id>/`。脚本只清理由当前进程成功安装的临时包。配对材料仍必须来自全新的隔离 Gateway workspace，不能使用正式 workspace。

## 崩溃诊断

正式版和 Debug 版都会在发生未捕获异常时覆盖写入一份最多 128 KiB 的堆栈；Realtime 边界已处理但会阻断功能的协议、状态、数据库或文件错误另行覆盖保存最近一份上下文和堆栈。应用启动时还会保存最近八次系统退出原因。诊断不常驻采样、不记录会话正文，也不上传数据；侧栏的“导出诊断报告”只有在用户主动选择系统分享接收方后才会离开应用。

Debug APK 还可以在手机通过 USB 连接并允许调试后执行：

```bash
clients/android/scripts/collect-debug-diagnostics.sh
```

ADB 报告写入 `clients/android/build/reports/debug-diagnostics/<时间戳>/`。Debug 包与正式包数据隔离，需要单独配对。

## Android Release

正式 APK 只发布到本仓库的公开 [GitHub Releases](https://github.com/kachofugetsu09/akashic-mobile/releases)。应用内“设置 → 检查更新”只接受稳定的 `vMAJOR.MINOR.PATCH` Release，以及名称为 `Akashic-Mobile-vMAJOR.MINOR.PATCH.apk` 且带 GitHub SHA-256 摘要的资产。

```bash
cd clients/android
./scripts/publish-release.sh 0.8.18
```

发布脚本会构建并验证签名，上传 APK、R8 mapping 和 `SHA256SUMS`。签名材料仍只从本机凭据或受保护 CI secret 读取，不进入仓库或 Release。

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。
