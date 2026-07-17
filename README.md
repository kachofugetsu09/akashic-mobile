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

使用 `android/device-gate.sh`。脚本只接受明确的设备序列号和测试方法，给本次运行生成独立的 `com.akashic.mobile.review.<run-id>` application ID；它会先构建 APK、读取 APK 中的真实 package/target package、枚举设备已有 package，确认无碰撞后才安装。每个 `--test` 是独立 instrumentation 阶段，阶段之间会强制停止应用进程。

```bash
cd android
ANDROID_HOME=/path/to/android-sdk ./device-gate.sh \
  --serial DEVICE_SERIAL \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
  --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#processRestartResumesWithoutHistoryDuplicates' \
  --runner-arg pairingOfferBase64=EPHEMERAL_OFFER \
  --runner-arg historySessionId=isolated-session-id
```

报告写入忽略版本控制的 `android/build/reports/device-gate/<run-id>/`。无论测试成功或失败，脚本只清理本次生成的运行级 package；发现候选 ID 已存在时会在任何安装、清理或数据操作之前失败。

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。
