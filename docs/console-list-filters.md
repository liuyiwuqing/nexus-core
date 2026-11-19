# 消息、投递筛选与元数据导出

2026-11-14。消息支持状态、来源和收件时间，投递支持状态、设备 ID 和消息收件时间；同一组条件也用于元数据 CSV。CONSOLE-13 只增加读取与导出，没有数据库迁移。CONSOLE-04 的 `cancelled`、状态历史和受限取消见[对应文档](console-delivery-history.md)。

## HTTP 契约

仍使用管理员 Bearer 与现有分页响应 `{items,total,page,size}`。`page` 默认 1、范围 1–100000；`size` 默认 30、范围 1–100。

| 接口 | 可选参数 | 有效值 |
| --- | --- | --- |
| `GET /api/v1/messages`、`/messages/export` | `readStatus` | `read`、`unread` |
| 同上 | `source` | 精确匹配，`[a-zA-Z0-9_.-]{1,32}`，如 `halo`、`manual` |
| `GET /api/v1/deliveries`、`/deliveries/export` | `status` | `pending`、`sent`、`received`、`displayed`、`read`、`failed`、`expired`、`cancelled` |
| 同上 | `deviceId` | 精确匹配，`[a-zA-Z0-9_-]{1,40}` |
| 同上 | `waiting` | `true`、`false`；`true` 等价 `status IN ('pending','sent')`（设备尚未接收），`false` 表示不限制；不能与 `status` 同时出现 |
| 同上 | `lastError` | 成功/失败原因码，`[a-z_]{1,32}`：`ack_timeout`、`broker_unavailable`、`busy`、`storage_error`、`time_unavailable`、`expired`、`invalid`、`cancelled_by_admin`，以及 `unknown`（等于"该行没有记录原因"，落回 `last_error IS NULL`） |
| 以上四个接口 | `receivedFrom`、`receivedBefore` | 带显式时区的 ISO 日期时间，年份 1–9999；开始包含、截止不包含 |

表中接口均以 `/api/v1` 为前缀。省略参数表示不限制该维度，保持既有调用。空字符串、非法身份字符、未知状态、无时区或非法时间、开始不早于截止均返回 400；合法但不存在的来源/设备/原因码返回空结果。未鉴权或其他作用域令牌返回 401。

`lastError` 不要求同时给 `status`：原因码也出现在 `sent`（`broker_unavailable`）和 `cancelled`（`cancelled_by_admin`）上，硬绑状态会漏掉这些行。管理台的「最近错误」筛选因此在任何状态下都可选；只有「未记录原因」（`unknown`）仍限于「投递失败」，因为在其它状态下它等于“没有 `last_error`”，会把成功行全部带进来。

`waiting=true` 与设备列表的 `waitingDeliveries` 同一口径：仍在 `pending` 或 `sent` 的投递。它与单值 `status` 互斥，同时给出返回 400，避免“等待中且已失败”这类自相矛盾的查询；`false` 等同于省略。空值、其他拼写或大小写一律 400。

所有时间条件均作用于 `inbox_messages.received_at`，即 CORE 收到消息的时间；不是来源创建时间、设备收到时间或 ACK 时间。只有一个边界也有效。URL 中 `+08:00` 的加号须编码为 `%2B`；页面按浏览器本地时间输入，再转换为带 `Z` 的 UTC 参数。

行、总数与导出复用固定 SQL 谓词并绑定参数。`total` 是筛选后的总数；超出末页返回空 `items`、保留实际 `total` 和请求页码，不在服务端重写页码。列表的两次 SQL，以及列表和稍后导出的不同请求，不提供跨并发写入的共同快照保证。

消息以 `read_at` 是否存在判定已读；投递读取原有 `status`，没有从发送次数或连接状态推断设备确认。投递“已结束”对应 `expired`，包括原有到期/停用/其他位置已读等原因，继续展示既有 `lastError`。

## 元数据 CSV

`GET /api/v1/messages/export`、`GET /api/v1/deliveries/export` 使用原管理员 Bearer，不新增角色或正文导出权限。读取当前条件的全部匹配行，不按 `page`/`size` 分页。最多 5000 行；查询第 5001 行后若超限返回 `409 {"error":"export_too_large"}`，要求缩小范围，不下载截断文件。空结果返回仅表头的 CSV，页面在结果为零时禁用导出。

固定列及顺序：

- 消息：`messageId,connectorId,source,eventType,priority,receivedAt,createdAt,expiresAt,readAt,readStatus,contentPurgedAt`。
- 投递：`eventId,messageId,deviceId,status,attempts,messageReceivedAt,receivedAt,displayedAt,readAt,nextAttemptAt`。其中 `messageReceivedAt` 是上述筛选时间，`receivedAt` 是设备已保存时间。

只导出仍保留的元数据；已清正文的消息仍可出现。不含标题、正文、摘要、发送者、原始 URL、payload、秘密或失败详情。UTF-8 BOM、CRLF、所有单元格双引号包裹，双引号翻倍；公式前缀 `= + - @`（包括前置空白/控制字符/BOM）以单引号中和，空值输出空单元格。时间输出为 UTC。响应含 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 和固定下载名 `messages-metadata.csv` / `deliveries-metadata.csv`。

```bash
curl --fail-with-body --get "$NEXUS_BASE_URL/api/v1/messages/export" \
  -H "Authorization: Bearer $NEXUS_ADMIN_TOKEN" \
  --data-urlencode 'source=manual' \
  --data-urlencode 'readStatus=unread' \
  --data-urlencode 'receivedFrom=2026-11-14T08:00:00Z' \
  --data-urlencode 'receivedBefore=2026-11-14T09:00:00Z' \
  --output messages-metadata.csv
```

请先核对 HTTP 退出状态，失败时输出文件可能是错误 JSON。

## 页面行为

- 状态切换即时生效；来源/设备和时间通过“应用筛选”提交。应用或清空时将对应列表回到第 1 页；翻页、刷新、轮询与导航保留已应用条件。“清空筛选”同时清除状态、身份与时间。
- 「最近错误」与投递状态相互独立，可在「全部」或任一状态下直接选择；切换状态会清掉已选原因，避免留下与当前状态不相干的条件。选择原因时只发送 `lastError`，不隐式附加 `status`。
- 投递状态下拉在「全部」后提供「待接收」，等同 `waiting=true`；设备卡片上的「待接收 N 条」也可点击，直接进入带 `deviceId=<设备>` 与 `waiting=true` 的投递页。钻取看到的是该设备的全部等待行，行数应与卡片计数一致（同为读时快照，并发接收/失败后可能不同）。
- 标记已读或重试后，仍使用当前条件。末页最后一项离开结果集时，根据新总数回到有效页并重新读取。
- 切换条件后先清除旧行，分别呈现加载、失败与空结果；失败提供原有顶部重试，不用旧行或“无记录”冒充新结果。
- 草稿未应用、加载、读取失败、零结果或已有导出时禁用导出；日期倒置在页面提示且不发 HTTP。导出中禁用列表操作并暂停 App 轮询，重复点击只发一次；超限、HTTP 失败或无效 CSV 有明确错误，不自动重试。
- 导航、退出或卸载取消在途导出，迟到响应不能下载文件；401 结束会话。退出重置所有条件。测试消息的待确认 ID/正文仍按[原规则](console-messages.md)保存在页面内存。

来源和设备按标识精确输入，不引入尚不存在的来源管理或全局设备选择器。正文导出、来源→设备路由和按消息批量取消仍未提供。

## CONSOLE-28 · 最近错误筛选独立可用

原因筛选原只在状态为「投递失败」时出现，但 `lastError` 也留在 `pending`/`sent`（`broker_unavailable`）和 `cancelled`（`cancelled_by_admin`）行上，按失败过滤会漏掉这些记录。现在「最近错误」始终显示，可在「全部」或任一状态下直接选择；请求只带 `lastError`，不隐式附加 `status`。`unknown`（未记录原因）仍只在「投递失败」下提供，避免在其它状态下把 `last_error IS NULL` 的成功行全部带入。切换状态会清掉已选原因，行为与「清空筛选」一致。

投递空列表增加「查看消息」动作，直接回到消息页；筛选条件、接口参数、数据库结构与导出规则未改。

`bash tools/ci/test_list_filters_ui.sh` 50 项通过，新增覆盖：原因筛选在「全部」下只发送 `lastError`、返回 31 条失败行；非失败状态不提供 `unknown`；失败状态提供 `unknown`；清空原因恢复 37 条混合列表；重新登录同时重置状态与原因。`test_overview_ui.sh` 132 项、`test_console_states_ui.sh` 128 项、投递时间线 66 项、元数据导出 51 项、消息批量 30 项、界面主题 68 项再次通过，共 525 项浏览器检查，任务空间均关闭。浏览器夹具为回环 HTTP，不代表真实 CORE、数据库、Broker 或设备。

完整 `JAVA_HOME="$(/usr/libexec/java_home -v 21)" python3 tools/run_local.py build` 退出 0：183 条中 178 通过、5 条条件跳过、0 失败/错误；JAR 的 index、DeliveryPanel 脚本/样式与 `target/frontend` 哈希一致，无旧资源。提交、推送与远程 CI 记录在[任务台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。

## CONSOLE-13 验证

`ConsoleMetadataExportFlowTest` 的 6 项实际 HTTP/独立 PostgreSQL 用例通过，覆盖组合状态、时区等价、收件边界、分页/越界、默认兼容、非法参数/鉴权、固定列/正文排除、清除后的元数据、CSV 引号/换行/公式前缀以及两类接口的 5001 行拒绝和恰好 5000 行成功。旧列表 3 项同时通过。旧实现先复现筛选计数错误及导出 404。

配套 `bash tools/ci/test_metadata_export_ui.sh` 的实际 Vue/回环 RAM HTTP 专项 44 项通过，实际下载并读取消息/投递 CSV，覆盖当前页以外的筛选行、未应用/加载/空/失败状态、范围校验、恢复、导航/刷新、重复点击、迟到响应、401 和 390px。旧页面先失败于缺少来源控件。脚本修正重复下载覆盖同名文件、带空格选择器及复用旧页轮询影响初始化请求记录的问题后完整通过；这些调整没有改变产品源码。

完整 `python3 tools/run_local.py build` 退出 0，Vue/类型检查、Maven verify/JAR 通过：85 条中 80 通过、5 条条件跳过、0 失败/错误。三个可选 C peer、真实模型与固定语音桥接仍跳过。当前 Java→固件 C 持久化/回执→Java 源码契约通过，其 SQL/传输边界仍为明确替身；没有生产迁移、真实消息、部署或实机结论。

既有发送 33/管理记忆 38/OTA 22/成长 18/筛选 63/概览 17/时间线 62/工具规则 96 项再次通过，连同新增 44 项共 393 项浏览器检查，任务空间均关闭。JAR 的三份前端资源和 V14 与当前产物/源码逐字节一致。

本项提交及远程 CI 另记[任务台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。旧 DEL-04 离线包保持原快照，不包含本次改动。

## CONSOLE-03 历史验证

`ConsoleListFilterFlowTest` 通过实际 HTTP 与独立 PostgreSQL 验证默认兼容、行/总数/分页、越界页、全部合法状态、非法值和鉴权，共 3 项通过。投递状态在隔离库中布置，断言通过 HTTP；该用例不声称设备已发送或已确认。

配套 `04-ai-desktop-pet/tools/ci/test_list_filters_ui.sh` 构建实际 Vue，使用回环 HTTP 内存夹具验证 63 项，包括筛选保持、两类末页操作、加载/失败/恢复/空状态、退出中的迟到响应与 390px 布局。旧页面先稳定失败于缺少状态控件；浏览器窗口尺寸切换后的脚本点选问题已修正并完整重跑通过。夹具不连接真实 CORE、数据库、Broker 或设备。

`python3 tools/run_local.py build` 退出 0：类型检查、前端构建、Maven verify/JAR 均通过；67 条中 62 通过、5 条条件跳过、0 失败/错误。跳过三个可选实际 C peer、真实模型与固定语音桥接，不计作本项通过；JAR 内 index/JS/CSS 与本轮前端产物哈希完全一致，没有旧资源混入。

既有测试发送 33、设备管理/记忆 38、OTA 22、成长 18 项再次通过，连同新增专项共 174 项浏览器检查。配套提交记录在[任务台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)，真实服务/设备与生产部署单独验收。
