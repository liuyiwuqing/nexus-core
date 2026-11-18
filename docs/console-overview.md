# 管理台只读运行概览 · CONSOLE-05

管理台“概览”从 `GET /api/v1/overview` 读取统计；沿用管理员 Bearer 鉴权，不接受写操作，不改变消息、投递、设备或清理策略。可见时每 5 秒读取，也可点“刷新概览”。切换页面/退出取消读取，401 结束会话；加载和失败不展示旧计数，无数据与读取失败分别呈现。

## 统计口径

数据库计数来自一条 PostgreSQL 查询和同一快照。`asOf` 是数据库快照时间；`dayStart` 是其所在 UTC 日期的零点，统计窗口为 `[dayStart, asOf]`。页面明确标记 UTC 日期；快照时间按浏览器本地时间显示。

| 返回值 | 含义 |
| --- | --- |
| `devices.total/online/offline/disabled` | 全部登记设备；在线沿用设备列表的 `online=true AND last_seen > asOf-90s`，未连接/空时间算离线，在线+离线=总数。停用单独统计，可与在线重合，不能相加计算总数 |
| `today.messages` | `received_at` 落在窗口中的收件数；来源提供的 `created_at` 不影响归日，未来收件时间不纳入 |
| `today.deliveries` | 上述今日收件产生的全部投递记录，包含当前终态；无启用设备的收件仍计消息、不会产生投递 |
| `today.failedDeliveries` | 上述投递当前处于 `failed` 的数量；重试/有效回执可使它下降，不是历史失败次数 |
| `today.deliveryFailureRate` | 当前失败投递数 / 今日收件投递数；比例范围 0–1，无投递时为 `null`，页面显示 `—` |
| `backlog.pending/sent` | 全部保留投递中当前为 `pending` / `sent` 的数量；页面积压为两者之和，包含等待 worker 收尾的记录，不承诺全部能立即发送 |
| `failedDeliveries` | 全部保留投递的当前 `failed` 数；不是只计今天，也不是不可变审计 |
| `mqtt` | 本次读取时传输连接的 `ready/unavailable`，在数据库查询之外读取；不能据此推导设备在线、已展示或已读 |

计数只覆盖当前保留的数据；启用保留策略后被删除的旧记录不会继续计入，没有永久累计或真实设备告警检测的含义。此版本不提供可选时区、生产告警阈值或推送通知；历史趋势见下节，同样只覆盖保留记录。

## 历史趋势 · OVERVIEW-TREND-01

概览页“运行趋势”读取 `GET /api/v1/overview/trend?hours=24&bucket=1h`，与快照接口分开：快照每 5 秒轮询，趋势只在切换范围或点“刷新趋势”时读取，避免长窗口每 5 秒重扫。

| 参数 | 约束 |
| --- | --- |
| `hours` | 1–168，默认 24 |
| `bucket` | `15m`/`1h`/`6h`/`1d`，默认 `1h`；`hours×3600` 必须能被桶秒整除 |
| 桶数 | `hours×3600÷bucketSeconds`，超过 400 返回 400 `invalid_request` |

返回 `asOf`、`from`、`to`、`hours`、`bucket`、`bucketSeconds`、`totals{messages,deliveries,failures}` 和固定长度的 `points[]`。每个点包含 `at`（UTC 整点桶起点）、`messages`、`deliveries`、`failures`、`partial`。**最后一桶是当前未结束的桶**（`partial=true`），数值会随刷新增长，页面对此有明确文案；空窗口仍返回完整网格，页面据此区分“没有数据”和“没有窗口”。

口径：

- `messages`/`deliveries` 与今日统计同源：按消息 `received_at` 归桶、排除 `metadata.test=true`、来源时间不移动归日、未来收件不计。
- `failures` 来自 `delivery_history` 中 `status='failed'` 且 `kind<>'baseline'` 的记录，按 `recorded_at` 归桶。它是“进入失败的次数”，不是“当前失败按收件桶回看”；迁移前的 baseline 不计为失败事件，因此窗口早于该表建立时无法还原。
- 三个序列都只覆盖仍保留的记录：保留策略清理后对应桶会变空，趋势不是永久累计。
- 桶按固定 origin（`2000-01-01T00:00:00Z`）用 `date_bin` 对齐，因此 15 分钟与 6 小时桶在不同窗口间一致；不使用 `date_trunc`，它不支持任意间隔。

## 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=ConsoleOverviewFlowTest test
python3 tools/run_local.py build
# 同级 04 项目，使用实际编译 Vue 和回环 RAM HTTP 夹具
bash tools/ci/test_overview_ui.sh
```

HTTP/独立 PostgreSQL 专项覆盖空数据/鉴权、UTC 日界、旧来源时间/未来收件、在线 90 秒阈值/停用重合、当前失败比例/跨日积压与只读重复请求；趋势专项覆盖固定桶数、最后一桶 partial、收件归桶、测试消息/未来收件排除、失败事件归桶、窗口与桶数上限 400/401。浏览器检查覆盖数据、MQTT 异常、加载/重复点击、空/失败/恢复、可见轮询、导航/退出的迟到响应、401、趋势 24/28 桶与范围切换、趋势空/失败/恢复和 390px；夹具不是 CORE、Broker 或设备联调。实际结果与配套提交见 [04 台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。

规范沉淀：统计口径属于本项目契约；沿用已有测试和生命周期流程，无需修改通用 Skill。
