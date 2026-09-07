# 0006 超长历史正文使用已认证的同源 HTTPS Range 恢复

- 状态：accepted
- 日期：2026-08-02
- 关联：`MOB-DATA-001`、`MOB-DATA-004`、`MOB-XREPO-001`、`MOB-SEC-001`

## 问题

核心必须保持 WebSocket frame 有界，但一条历史 assistant 正文可能远大于事件预算。继续扩大单帧会同时放大 Gateway、代理、客户端解析和进程恢复的失败面；裁切正文又会让手机清空本地投影后无法恢复 SessionDB 的完整事实。

## 决定

1. `history.get` 使用 `after_seq + through_seq` 冻结 Message 日志前缀，不兼容旧分页。
2. 超过事件预算的整条 Message JSON 在历史页中改为 `message_ref`，其中只含版本、UTF-8 字节长度和 SHA-256；不保存可见预览或并行 block 投影。
3. 客户端通过 WebSocket 的 `message.content.prepare` 取得短期 ticket，再从当前活动 endpoint 派生的同源 HTTPS route 以单段、最多 256 KiB 的 Range 请求恢复正文。
4. ticket 绑定服务器、设备、连接 epoch、会话、消息、长度和摘要；HTTP 不接受 redirect，并复用配对建立的证书信任。
5. 客户端每段先写私有临时文件并 `fsync`，再保存确认偏移。只有完整长度、SHA-256、严格 UTF-8、Message 身份、Session 和 `seq` 全部通过时，才在 Room 事务中提交整条 Message 并结束传输。

## 理由

WebSocket 保留有序控制和小事件，HTTPS 提供成熟的 Range、条件请求和独立失败重试。正文仍由 SessionDB 按消息身份读取，短期授权不成为第二份事实；客户端临时文件和偏移只是可重建传输状态。

## 后果

- 自 Room v17 起，传输记录绑定 `messageId + sessionId + messageSeq`；v18 的 Input 身份迁移保留该记录与确认偏移，应用私有目录继续保留 `message-content` 临时文件。
- 普通断线和进程退出不清除已确认片段；重载服务端投影时级联删除记录并清理孤立文件。
- 旧协议在 Message v2 边界明确失败，不返回假完整内容。
- 协议快照和 Runtime Contract 使用仓库当前锁文件固定的核心提交。

## 验证

- 核心测试覆盖 ticket 绑定、单段 Range、响应摘要、Unicode 正文引用和冻结快照。
- Android 测试覆盖落盘后推进偏移、启动截断未确认尾部、完整校验后发布，以及 thinking/tool block 不变。
- 隔离 Gateway、隔离 application ID 的 Pixel 7 清空本地投影再同步，恢复前后按消息身份比较正文、thinking、tool block 和顺序。
