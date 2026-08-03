# 运行资料查看入口

- 状态：current
- 证据基线：本文所在 Git tree
- 历史能力快照（运行资料查看实现）：Core commit `051cf38ea31f93dced3e46abd5c24984705b335f`、tree `1555c29e67b2f06104fcb406ce58b0aa85af82de`、schema SHA-256 `32a345eacd5971aa6b4b4bbbe7c01b5250c50b0629e6ae5b6688ca77acb35457`
- 当前 Runtime Contract：以 `protocol/source.json` 与 `runtime-gate/runtime-contract.lock.json` 为准；两者固定当前协议快照、能力/provider commit/tree/schema digest 与场景目录。

本文记录移动端如何只读展示核心拥有的运行资料，不把远端事实复制为移动端权威状态。

## 能力边界

```text
┌────────────────────────── akashic-agent ──────────────────────────┐
│ 文档目录/正文 │ scheduler 列表/详情 │ plugin/skill/MCP 运行快照     │
│                        只读命令响应                               │
└──────────────────────────────┬─────────────────────────────────────┘
                               │ mobile realtime protocol
                               ▼
┌────────────────────────── akashic-mobile ─────────────────────────┐
│ RuntimeInspectionCoordinator                                     │
│   ├─ ready 后读取三个目录，并按 command id 匹配响应               │
│   ├─ 仅在用户打开项目时读取正文/详情                              │
│   └─ 将当前投影交给 native snapshot，不做持久化                   │
│                              ▼                                    │
│ WebView：分类目录 → 单项详情 → Markdown 渲染                      │
└───────────────────────────────────────────────────────────────────┘
```

核心拥有文档内容、任务定义、运行状态和能力目录。移动端只拥有当前连接内的临时展示投影；断线时清空，不写入 Room，也不提供编辑、启停或删除操作。

## 界面结构

抽屉入口位于“会话”上方，使用低饱和湖绿色满色胶囊。入口颜色只表达这是一个独立的运行资料空间；页面内再用不同色相区分类别。

```text
┌────────────────── 抽屉 ──────────────────┐
│ ┌──────────────────────────────────────┐ │
│ │  ▣  知识与运行                      │ │
│ │     记忆 · MCP · 定时任务           │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ 会话                                     │
└──────────────────────────────────────────┘

┌────────────── 知识与运行 ────────────────┐
│ 文档 · 暖琥珀                            │
│   VEDA.md                         ›       │
│   MEMORY.md                       ›       │
│   …                                      │
│                                          │
│ MCP 与能力 · 苔绿色                      │
│   filesystem                     ›       │
│   插件 4 · 技能 12 · MCP 3               │
│                                          │
│ 定时任务 · 暮紫色                        │
│   每日整理 · 下次 08:00           ›       │
└──────────────────────────────────────────┘
                    │ 打开单项
                    ▼
┌────────────────── 详情 ──────────────────┐
│ ‹ 返回       项目名称                    │
│                                          │
│ Markdown 标题、列表、代码块和链接        │
└──────────────────────────────────────────┘
```

文档、MCP 和定时任务均展开为可逐项打开的列表，不使用只显示数量的聚合卡片。正文复用会话已有 Markdown 渲染组件，保持标题、列表、链接、代码块等语义。

## 协议与失败语义

- `RealtimeSession` 在连接 ready 后请求文档、能力和任务目录。
- `RuntimeInspectionCoordinator` 以 command id 和预期响应 type 同时校验请求归属；类型不匹配直接失败，不把错误响应当作空目录。
- 文档正文、单个 MCP 详情和单个任务详情按需读取。
- 核心返回 `command.failed` 时展示明确错误；断线会取消 pending 状态并清空远端投影。
- Android snapshot protocol 为 `7`，旧 WebView 不能把缺失的新字段误认为已加载成功。

## 固定跨仓库证据

`protocol/source.json` 与 `runtime-gate/runtime-contract.lock.json` 固定当前 PR 的协议快照、能力/provider commit、tree、schema digest 与场景目录；`runtime-gate/scenarios.json` 同时登记核心 provider 场景与移动端 consumer 测试。本文开头的核心 commit、tree 和 schema 仅是运行资料查看实现的历史能力快照，不是当前 Gate 锚点。核心提交必须先可从远端获取，移动端 PR 的 Gate 才能重放当前锁定证据。
