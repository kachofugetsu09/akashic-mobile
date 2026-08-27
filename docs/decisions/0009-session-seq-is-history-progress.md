# 0009 · Session seq 是移动历史进度

- 状态：accepted
- 日期：2026-08-27
- 关联条款：MOB-DATA-001、MOB-XREPO-001～MOB-XREPO-003、MOB-RELEASE-001

## 背景

旧协议把主动正文作为 `message.proactive` durable event 发送，Android 先保存
`proactive:*` 临时消息，再等历史页按 delivery ID、内容或时间合并。它与 Session 历史形成
两套正文和两套进度。

## 决定

Core 先提交 Akashic 主动正文并分配 canonical `message_id + seq`。Android 收到
`session.updated` 后请求 Session list，比较 `snapshot_max_seq` 与 Room 中最大连续
`serverSeq`，再通过已有 `history.get(after_seq)` 拉取缺尾。Room 消息行本身就是历史进度，
不新增 history cursor。

删除 `message.proactive`、`proactive:*`、`ephemeral:*` assistant fallback 和按内容/时间兼容
合并。Realtime ACK cursor 继续只负责 durable transport resume。

## 理由

一个事实只剩一个 owner：SessionDB 拥有正文与顺序，Room 是可重建缓存，Realtime inbox 只
拥有传输。Web 与 Mobile 可以使用同一套 Session head 对账，不需要服务端保存设备 cursor。

## 影响

这是 breaking removal。旧安装不迁移本地消息，发布后清除历史并重新扫码同步。Core 与
Mobile 必须以固定 commit/schema 组合验收。

## 验收

- Android 协议与运行代码不接受 `message.proactive`。
- `session.updated` 会触发 Session list/head 对账，缺尾从本地最大连续 `serverSeq` 开始拉取。
- 全新安装或清除历史后可从 `after_seq = -1` 重建；实时 ACK cursor 不参与历史完成判断。
