# 当前未完成事项

本文件只保存已接受但尚未完成的工作。事项落地后在同一交付中删除，历史由 Git、PR 和决策记录保留。

- 完成 `akashic` 统一身份 breaking cutover：Room 15→16 删除旧 Session-bound 投影与本地工作，保留配对/cursor/非 Session 设置；新 Session 只通过 Core `session.create` 分配，收到 durable reset 后全量同步。
