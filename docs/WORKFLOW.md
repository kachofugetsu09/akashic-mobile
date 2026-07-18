# 仓库工作流

所有任务遵循同一条路径：

```text
Read → Ownership → Isolate → Contract → Implement → Verify → Review → Reconcile → Deliver
```

## 1. Read

按 `AGENTS.md → docs/INDEX.md → docs/WORKFLOW.md` 进入，再读取任务路由要求的真源、真实代码、schema、测试和 Git。不能用记忆或旧 PR 代替当前证据。

出口：能区分用户确认语义、当前实现、推断和未知。

## 2. Ownership

声明能力 owner、权威状态 owner、消费者范围和跨仓库依赖。移动端请求核心 runtime 改动前，必须说明通用能力理由、其他消费者和客户端侧替代方案。

出口：所有读写与副作用都有唯一 owner；不确定时先询问。

## 3. Isolate

从目标分支最新远端 commit 创建专用 worktree。检查脏状态；修改持久化文件前在仓库外创建清晰备份。测试 workspace、插件目录、包名和设备数据必须与正式环境隔离。

出口：基线、branch、worktree、允许路径和恢复点已记录。

## 4. Contract

用 `templates/agent-task-contract.md` 明确目标、语义增减、保护状态、允许仓库、外部依赖锁、验证、停止条件、回滚和交付目标。需求歧义不能由 Agent 自行补全。

出口：成功条件可观察，删除与迁移授权明确。

## 5. Implement

只做合同允许的最小改动。持久化变化必须描述增加、更新、逻辑失效、物理删除和恢复；协议变化同步维护固定快照与来源证据。

出口：diff 内没有无关重构、隐式 fallback 或未授权副作用。

## 6. Verify

先运行与改动直接相关的最小验证。按影响范围选择仓库已有验证：

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

Runtime Gate 与真机 Gate 的详细隔离约束以 `README.md` 和 `runtime-gate/README.md` 为准。未运行的验证必须明确写成未验证，不得用旧结果冒充当前 head。

出口：每项语义变化都有对应证据，失败已归因。

## 7. Review

Review 默认只读。按 `templates/review-contract.md` 检查相邻 stack、累计 head、能力 owner、持久化增减、协议来源、远端 checks 和未验证项。修复需要明确授权。

出口：确定性缺陷已修复；业务取舍和语义冲突已停止询问。

## 8. Reconcile

代码与工作手册对账：长期语义变更更新 `projectneed.md` 或决策；新问题模型更新 `design/`；完成事项从 `NOW.md` 删除。文档和代码在同一交付中保持一致。

出口：没有第二份竞争真源，`NOW.md` 只剩未完成事项。

## 9. Deliver

提交信息简洁说明目的。PR 使用仓库模板，报告实际验证、未验证项、外部依赖版本和回滚方式。受保护分支只能通过 Review 后的 PR 合入。

完成定义：目标行为实现、保护状态未退化、相关 checks 通过、文档完成对账、交付 head 可精确复现。
