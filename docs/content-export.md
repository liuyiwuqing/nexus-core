# 管理员正文导出 · CONSOLE-13-B

原元数据 CSV 继续提供给已授权读取角色，最多 5000 行、不含正文。本项单独提供管理员正文导出，不扩展 viewer/operator 的批量正文权限。

`GET /api/v1/messages/content-export?confirm=EXPORT_MESSAGE_CONTENT` 必须携带有效管理员 Bearer。确认缺失/不匹配 400，未鉴权 401，其他角色 403；角色路径校验同时处理 matrix 参数和尾部斜线，不能用 URL 变体绕过导出权限。接口可组合与消息列表相同的 `readStatus/source/receivedFrom/receivedBefore`，实际范围是已应用条件与最近七天未清理正文的交集；不会导出历史已清理内容。

固定列为 messageId/source/eventType/title/content/summary/sender/originalUrl/priority/receivedAt/createdAt，最多 1000 条。查询第 1001 行即 409 `content_export_too_large`，不静默截断。沿用现有 CSV 的 UTF-8 BOM、CRLF、引号/换行和公式前缀保护，`no-store`；不包含凭据、原始 metadata 或任意额外字段。若应用了更早时间窗口可能没有可导出的正文，此范围始终在确认文案说明。

页面仅管理员看到“导出正文…”，先展示范围和内容字段，勾选明确确认后下载。加载/读取失败/零结果/未应用筛选禁止提交；导出期间防重复，导航、退出和会话变化丢弃迟到文件。结果只生成本次下载，服务端不留 CSV 文件。统一审计记录导出请求及 HTTP 结果，不记录 CSV 正文或 query。

`ContentExportFlowTest` 与既有 `ConsoleMetadataExportFlowTest` 验证实际 HTTP/隔离 PostgreSQL：确认、管理员与其他角色、路径变体、七天/已清正文排除、CSV 字符/公式、1001 拒绝与 1000 成功及旧元数据兼容。实际 Vue `test_content_export_ui.sh` 的 15 次检查通过，产生真实 CSV 并读取 UTF-8 正文，验证显式确认、未应用筛选、超限反馈和退出取消迟到下载。生成数据仅来自 RAM/隔离库，未读取或导出生产消息。完整构建与旧页面回归按台账实际结果记录。

项目导出权限和范围在本文/服务端/检查维护，不更新共享 Skill；未自动启用清理、迁移生产库或向外部服务发送正文。
