# AGENTS.md

## 每个会话的固定入口

- 开始任何调查、设计、实现或评审前，依次阅读 `docs/INDEX.md` 与 `docs/WORKFLOW.md`。
- 再按 `docs/INDEX.md` 的任务路由读取 `docs/projectneed.md`、`docs/NOW.md`、相关决策和设计；不要把 README、旧 PR 或代码现状擅自提升为产品意图。
- 长期语义冲突、能力 owner 不清或持久化增减不明确时停止核对，不带着猜测修改代码。

## 沟通与实现

- 默认使用中文沟通；代码标识符保持英文。
- 先检查真实代码、构建配置和测试，再修改实现。
- 保持 fail-fast、fail-loud，不用空值、假数据或静默 fallback 掩盖错误。
- 非平凡函数使用简短中文 docstring，并按真实阶段添加编号注释。
- 修改持久化文件前创建清晰、可恢复的备份。

## 仓库边界

- 本仓库拥有 Android 客户端、移动 WebView、客户端协议快照、测试和 release 工具。
- `akashic-agent` 拥有服务端协议真源、gateway、插件服务和集成环境。
- 客户端必须能够脱离父仓库独立测试和构建。
- 禁止引入指向开发者本机或父仓库的绝对路径。

## 安全

- 禁止提交签名文件、密码、OAuth、设备配对材料、服务端密钥或生产配置。
- 协议快照必须记录来源核心 commit 和 SHA-256；不一致时构建直接失败。

## 交付

- PR 必须说明前置 PR、行为变化、验证命令和回滚方式。
- 修改 Android 后运行相关 Gradle 测试；修改移动 Web 后运行 typecheck、lint 和 build。
- 跨仓库协议与运行时兼容证据必须固定确定 commit、tree 和 digest，不使用浮动分支。
