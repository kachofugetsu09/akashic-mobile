# Akashic Mobile

Akashic 的 Android 客户端与移动 WebView runtime。

本仓库可以独立测试、构建和发布 APK；服务端协议真源仍由 `akashic-agent/schema/` 维护。

## 本地验证

```bash
npm ci
npm run typecheck
npm run lint
npm run test:mobile-web-state
cd clients/android
./gradlew testDebugUnitTest lintDebug assembleDebug
./test-device-gate.sh
```

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

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。
