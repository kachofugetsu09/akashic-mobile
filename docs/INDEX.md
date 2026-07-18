# 项目工作手册索引

本索引只负责把新会话路由到当前真源，不承载需求正文。

## 固定阅读顺序

1. `AGENTS.md`：协作纪律与仓库边界。
2. `docs/INDEX.md`：确定本次任务还要读什么。
3. `docs/WORKFLOW.md`：按固定阶段推进任务。
4. 按任务类型读取下表中的真源和当前代码证据。

## 任务路由

| 任务 | 必须继续阅读 |
|---|---|
| 任何产品行为或数据语义变更 | `projectneed.md`、相关 `decisions/` |
| 接手未完成工作 | `NOW.md` |
| Room、文件、密钥、缓存或删除逻辑 | `design/persistence-state-map.md` |
| 配对、Gateway、插件 UI 或跨仓库协议 | `design/system-context.md`、`decisions/0002-*`、`decisions/0003-*` |
| PR Review | `templates/review-contract.md`、目标 PR 的相邻 base/head 和累计 head |
| 实现任务 | `templates/agent-task-contract.md` 与改动路径的真实代码、schema、测试 |
| 新会话交接 | `templates/context-handoff.yaml` |

## 文件职责

| 文件 | 回答的问题 |
|---|---|
| `projectneed.md` | 用户确认、长期稳定、未来实现必须保持什么 |
| `NOW.md` | 已接受但尚未完成什么；完成后立即删除 |
| `decisions/` | 为什么做出某项决定及其勘误史 |
| `design/` | 当前代码事实、owner、调用关系和持久化地图 |
| `writing-rules.md` | 新信息应该写到哪里 |
| `templates/` | 任务、评审、决策和交接怎样表达 |
| 代码、配置、schema、测试和 Git | 当前实现到底是什么 |

## 冲突处理

优先级依次为：用户当前明确指令、`projectneed.md`、accepted 决策、`NOW.md`、当前实现证据、历史材料。前五项冲突时停止并列出冲突，不选择最方便实现的一项继续。

## 完整结构

```text
AGENTS.md
docs/
├── INDEX.md
├── WORKFLOW.md
├── projectneed.md
├── NOW.md
├── writing-rules.md
├── decisions/
├── design/
└── templates/
.github/pull_request_template.md
```

新增、移动或重命名权威文档时，必须在同一提交更新本索引和所有入站链接。
