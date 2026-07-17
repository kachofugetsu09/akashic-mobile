# Akashic Mobile

Akashic 的 Android 客户端与移动 WebView runtime。

仓库正在从 `akashic-agent` 的 `feature/im-phone` 分支按可审查的 stacked PR 迁入。迁移完成后，本仓库将能够独立测试、构建和发布 APK；服务端协议真源仍由 `akashic-agent/schema/` 维护。

## 本地验证

Android 工程只依赖本仓库和 Android SDK：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

`protocol/mobile-realtime-v1.json` 是服务端协议的固定快照，来源 commit 和 SHA-256 记录在 `protocol/source.json`。协议变更必须先更新 `akashic-agent/schema/`，再同步快照并验证客户端。

## 真实设备 Gate

连接个人设备时，禁止直接运行 `connectedDebugAndroidTest`。它不能在安装前证明候选包不会覆盖设备上的现有应用或测试数据。

使用 `android/device-gate.sh`。脚本要求源码 worktree 干净，只接受受限字符组成的设备序列号、测试方法和 runner 参数，并给本次运行生成独立的 `com.akashic.mobile.review.<run-id>` application ID。它会先构建 APK、读取 APK 中的真实 package/target package、枚举设备已有 package，确认无碰撞后才以禁止替换的方式安装。每个 `--test` 是独立 instrumentation 阶段，阶段之间会强制停止应用进程；只有 runner 明确报告一个指定测试开始并成功结束时，该阶段才通过。

```bash
cd android
ANDROID_HOME=/path/to/android-sdk ./device-gate.sh \
  --serial DEVICE_SERIAL \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#processRestartResumesWithoutHistoryDuplicates' \
  --runner-arg pairingOfferBase64=EPHEMERAL_OFFER \
  --runner-arg historySessionId=isolated-session-id
```

报告写入忽略版本控制的 `android/build/reports/device-gate/<run-id>/`，包含精确 source commit/tree 和唯一终态 `gate_result`。脚本只清理由本进程成功安装的运行级 package，清理失败会列出残留 package 并让 Gate 失败；发现候选 ID 已存在时会在任何安装、清理或数据操作之前失败。

这个脚本证明的是客户端源码、设备 package、测试执行和清理边界。`pairingOfferBase64` 指向的服务端是否来自一次全新 Mobile Lab workspace，仍必须由 Gate 操作者在生成临时配对材料时保证；不要把正式 workspace 的配对材料传给真实设备测试。

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。
