# Runtime compatibility Gate

这个 Gate 同时固定两条不同的事实，不能把它们合并：

- `protocol/source.json` 固定当前 Draft PR 实际实现的历史 schema 快照。
- `runtime-contract.lock.json` 分开固定能力提交、实际运行 revision/tree、当前 schema 与语义场景目录。

```text
历史 schema commit ──校验真实 blob──► 当前 PR 协议快照

能力 commit/tree/schema ──► 静态协议能力锚

provider runtime/tree/schema ──只读挂载──► 无网络 Docker ──运行──► 核心真实语义测试
             │                         │
             │                         └─ tmpfs workspace / plugin-home / HOME
             └─ `tested_backward_compatible` 只由 Gate 结果证明

移动端生产 codec / outbox ──校验真实测试标记──► Android JVM tests
```

本地验证：

```bash
python3 runtime-gate/verify_contract.py \
  --mobile-root . \
  --core-root /path/to/pinned/akashic-agent

docker build \
  --file /path/to/pinned/akashic-agent/docker/debug/Dockerfile \
  --tag akashic-mobile-runtime-gate:local \
  /path/to/pinned/akashic-agent

python3 runtime-gate/run_core_contract.py \
  --mobile-root . \
  --core-root /path/to/pinned/akashic-agent \
  --image akashic-mobile-runtime-gate:local
```

每一层 stacked PR 只登记该层已经实现的场景；后续 PR 在自己的目录版本中追加场景，禁止把未来能力提前塞进基础 PR。能力提交用于锁定静态协议语义，provider runtime 固定真正运行全部场景的核心版本；两者可以来自不同历史链，不伪称 ancestry，兼容性只由固定场景的 Gate 结果证明。更新核心兼容基线时，必须同时更新两边的完整 commit/tree/schema digest 和场景目录 hash，并重新运行 Gate。禁止写浮动分支或 `latest` 作为兼容证据。

`mobile-pr5-v1` 完整继承 PR4 的会话与附件场景，只追加持久被动消息队列、不可投递通知保留、跨进程停止和恢复中的 turn identity。会话目录缺失仍使用 PR2 确立的中立投影清理语义，不在客户端新增远端删除 tombstone。
