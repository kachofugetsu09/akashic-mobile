# Akashic Mobile

Akashic 的 Android 客户端与移动 WebView runtime。

仓库正在从 `akashic-agent` 的 `feature/im-phone` 分支按可审查的 stacked PR 迁入。迁移完成后，本仓库将能够独立测试、构建和发布 APK；服务端协议真源仍由 `akashic-agent/schema/` 维护。

## 安全边界

- 不提交 APK 签名文件或密码。
- 不提交 OAuth、设备配对材料、服务端密钥或本机配置。
- Release 签名只在受保护的 CI secret 或开发者本机凭据中完成。

