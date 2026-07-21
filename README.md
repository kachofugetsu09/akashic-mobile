# Akashic Mobile

Akashic 的 Android 客户端与移动 WebView runtime。

本仓库可以独立测试、构建和发布 APK；服务端协议真源仍由 `akashic-agent/schema/` 维护。

## 本地验证

```bash
npm ci
npm run typecheck
npm run lint
npm run test:mobile-web-state
python3 -m unittest discover -s runtime-gate -p 'test_*.py'
python3 runtime-gate/verify_contract.py --mobile-root .
cd clients/android
./gradlew testDebugUnitTest lintDebug assembleDebug
./test-device-gate.sh
```

`protocol/source.json` 固定客户端实现对应的历史协议快照；`runtime-gate/` 另外固定实际验收的核心 revision、tree 和 24 个跨仓库语义场景。两者变化时必须重新运行 runtime contract Gate，不能用浮动分支或本机核心 checkout 代替。

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

## Debug 崩溃诊断

Debug APK 使用独立包名 `com.akashic.mobile.debug`。它只在发生未捕获异常时覆盖写入一份最多 128 KiB 的堆栈，并在启动时保存最近八次系统退出原因；不常驻采样，也不上传数据。

手机通过 USB 连接并允许调试后执行：

```bash
clients/android/scripts/collect-debug-diagnostics.sh
```

报告写入 `clients/android/build/reports/debug-diagnostics/<时间戳>/`。Debug 包与正式包数据隔离，需要单独配对。

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。
