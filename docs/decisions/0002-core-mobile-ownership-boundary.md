# 0002 核心与移动端按状态 owner 分仓

- 状态：accepted
- 日期：2026-07-18
- 关联：`MOB-OWN-001`、`MOB-OWN-002`、`MOB-DATA-001`
- 部分勘误：[0005](0005-shared-webui-source-lives-in-core-repository.md) 调整共享 WebUI 源码位置，不改变原生状态 owner

## 问题

核心 SessionDB、移动端 Room、本地可靠投递和插件 UI 缓存描述相似，但删除与恢复语义完全不同。

## 决定

核心仓库拥有服务端长期事实、Gateway、协议真源和插件 runtime；移动端仓库拥有客户端交互、本地未完成工作和消费者缓存。Room 服务端投影不得替代 SessionDB，本地工作不得因投影重建被删除。

## 理由

状态 owner 决定谁能写、谁能删、以谁恢复。按 owner 分层能避免把上下文裁切、列表缺失或缓存清理误译成事实删除。

## 后果

跨边界变化必须同时声明生产者、消费者、权威状态和客户端替代方案。移动端仓库保持独立构建，不通过 submodule 复制核心运行时。

Input 的身份在边界上只有一个值：客户端先生成 `message.send` frame ID，Core 接纳后把同值保存为 `Message.id`。Room v18 把旧 `user:<首次 clientMessageId>` 本地行迁到当前 `clientMessageId`；旧版明确失败重试会更新后者和 outbox，但保留前者作为视觉身份。旧协议已经投影为 `<sessionId>:<seq>` canonical ID 的 Input 保留该 ID，只退役 `clientMessageId`，避免现行历史按原 ID 补全时产生重复。完整远端 Input 是补结算 outbox 的成功证据，未完成的 restoring 行及下载进度继续保留。迁移只移动没有服务端序号、正文和来源的 `user:` 本地行，拒绝猜测其他旧 ID 形状。

## 验证

任务合同和 PR 必须列出能力 owner、持久化增减与受保护状态；Runtime Contract 验证固定核心与移动端组合。
