# 0003 跨仓库兼容使用固定 Runtime Contract

- 状态：accepted
- 日期：2026-07-18
- 关联：`MOB-XREPO-001`、`MOB-XREPO-002`

## 问题

核心和移动端分仓后，任一仓库单独通过测试都不能证明组合语义兼容；浮动分支在 Gate 通过后还会继续变化。

## 决定

移动端用 `runtime-gate/runtime-contract.lock.json` 固定能力 commit/tree/schema、实际 provider runtime revision/tree/schema 和场景目录 digest。兼容关系只能由该固定组合的隔离场景 Gate 证明。

## 理由

commit 与 digest 把“测试了哪个外部状态”变成可复现事实；场景 Gate 检查同步、异步、确认、重试和恢复等跨模块语义。

## 后果

核心更新不会自动改变已通过的旧组合。需要采用新核心时，必须显式更新 lock、场景证据和消费者验证；不得用 `main`、`latest` 或本机缓存代替。

## 验证

以 `.github/workflows/android.yml` 和 `runtime-gate/README.md` 中当前命令为执行真源。历史通过结果不替代新 head 的验证。
