# Runtime compatibility Gate

这个 Gate 同时固定两条不同的事实，不能把它们合并：

- `protocol/source.json` 固定当前 Draft PR 实际实现的历史 schema 快照。
- `runtime-contract.lock.json` 固定已验证的核心运行时 commit、tree、当前 schema 与语义场景目录。

```text
历史 schema commit ──校验真实 blob──► 当前 PR 协议快照

当前 runtime commit ──只读挂载──► 无网络 Docker ──运行──► 核心真实语义测试
                                      │
                                      └─ tmpfs workspace / plugin-home / HOME

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

每一层 stacked PR 只登记该层已经实现的场景；后续 PR 在自己的目录版本中追加场景，禁止把未来能力提前塞进基础 PR。更新核心兼容基线时，必须同时更新完整 commit/tree、当前 schema hash 和场景目录 hash，并重新运行 Gate。禁止写浮动分支或 `latest` 作为兼容证据。

`mobile-pr3-v1` 在 PR2 会话恢复场景之上追加上传二进制帧、持久 offset 续传、消息媒体接入和纯文件名边界；下载与后续移动能力不属于本 profile。
