# 管理台只读运行概览 · CONSOLE-05

管理台“概览”从 `GET /api/v1/overview` 读取统计，沿用 Bearer 鉴权（所有已认证角色可读）；概览本身只读，唯一的写入口是管理员的告警阈值配置（`PUT /api/v1/overview/thresholds`），不改动消息、投递、设备或清理策略。可见时每 5 秒读取，也可点“刷新概览”。切换页面/退出取消读取，401 结束会话；加载和失败不展示旧计数，无数据与读取失败分别呈现。

## 统计口径

数据库计数来自一条 PostgreSQL 查询和同一快照。`asOf` 是数据库快照时间；`dayStart` 是其所在配置时区（`nexus.overview.timezone`，默认 `UTC`，环境变量 `NEXUS_OVERVIEW_TIMEZONE`）的当地零点，统计窗口为 `[dayStart, asOf]`。响应中的 `timezone` 是解析后的时区 ID，页面按该时区显示当地日期；快照时间按浏览器本地时间显示。配置非法时 CORE 启动失败，不会静默退回其他日期口径。时区只影响“今日”统计，趋势桶仍按固定 UTC 原点对齐。

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
| `thresholds` | `alert_thresholds` 表当前生效的告警阈值，与本次计数同快照读取；前端据此染色与写文案，不自己保存常量 |
| `alerts` | 本次读取命中的告警（可能为空），见下节 |
| `failureReasons` | 今日失败投递按 `last_error` 的归并（可能为空），见「失败原因」一节 |

计数只覆盖当前保留的数据；启用保留策略后被删除的旧记录不会继续计入，没有永久累计或真实设备告警检测的含义。今日时区由部署配置决定，不能在页面按用户切换，也不提供推送通知；历史趋势见下节，同样只覆盖保留记录。

## 告警阈值 · OVERVIEW-ALERT-01

概览的告警是**读时从同一份快照派生的状态**，不是事件：告警本身不落库、不推送、不记历史（阈值变更另记审计，见下节）。每 5 秒轮询都会重新判定，因此同一条告警在连续读取里重复出现是正常的；读取失败时不返回告警（`alerts` 随快照一起消失），不会留下过期的红色提示。

阈值存放在 `alert_thresholds` 单行表，与计数由同一条 SQL 读出，改阈值不需要发版；管理员可在管理台调整，契约见「阈值配置 · ALERT-CONFIG-01」。

| `code` | 级别 | 命中条件 | `value` / `threshold` / `unit` | 钻取目标 `target` |
| --- | --- | --- | --- | --- |
| `mqtt_connection` | `error` | 本次读取传输未就绪 | `0 < 1`，`unit=state`；只表达"发不出去"，不代表设备离线 | `null` |
| `devices_online` | `warn` | 至少一台启用设备，但没有一台启用设备在线 | 在线启用设备数与 `onlineMin`（默认 1）比较，`unit=count`；一台设备都没登记不报 | `devices` + `status=offline` |
| `delivery_backlog` | `warn` | 当前 `pending` 积压 ≥ `backlogPending`（默认 100） | 积压条数与阈值比较，`unit=count`；只算 `pending`，不算等待设备确认的 `sent` | `deliveries` + `status=pending` |
| `delivery_failure_rate` | `warn` | 今日投递 ≥ `failureRateMinSample`（默认 20）条且失败率 ≥ `failureRate`（默认 10%） | 比例与阈值比较，`unit=ratio` | `deliveries` + `status=failed` |

每条告警同时给出 `comparison`（`gte` 表示 `value ≥ threshold` 命中，`lt` 表示 `value < threshold` 命中），消费方不需要再猜方向。`devices_online` 的判定用"启用且在线"的子集：`devices.online` 仍按原口径统计（停用但在线的设备也算在线），所以两者不能互相推导。

失败率有样本门槛：19 条投递全失败是 100%，但样本不足 `failureRateMinSample` 时不判定——单条失败就能造出 100%，据此报警只会变成噪音。门槛随响应下发，前后端一致：样本不足时失败率条形同样不染色。

前端只负责把 `alerts` 渲染成一句话，未知 `code` 原样显示，服务端新增告警不会因为前端没改版而消失；文案里的阈值和百分比都取自本次响应。告警不含"持续时间"和"首次发生时间"：这类信息需要别处持久化，本版本不做。

## 阈值配置 · ALERT-CONFIG-01

`GET /api/v1/overview/thresholds` 所有已认证角色可读，返回当前阈值、版本、真实变更总数与最近 20 次变更：

```json
{
  "thresholds": {"failureRate": 0.1, "failureRateMinSample": 20, "backlogPending": 100, "onlineMin": 1},
  "version": 1,
  "updatedBy": "system",
  "updatedAt": "2026-11-18T02:00:00Z",
  "historyTotal": 21,
  "history": [{"sequence": 21, "actor": "admin", "fromVersion": 20, "toVersion": 21,
               "failureRate": 0.25, "failureRateMinSample": 5, "backlogPending": 2,
               "onlineMin": 3, "recordedAt": "2026-11-18T02:01:00Z"}]
}
```

`GET /api/v1/overview/thresholds/audit?before=&size=` 读取更早的变更，所有已认证角色可读：

- `before` 可选，是当前已加载最旧一行的 `sequence`；只返回 `sequence < before` 的行。新写入只会出现在更大序号一侧，因此并发保存不会让翻页重复或跳过。
- `size` 默认 20，取值 1–100；`before < 1`、`size` 越界或非数字返回 400 `invalid_request`。
- 返回 `items`（按 `sequence` 降序）、`total`（全部审计行数）与 `hasMore`（是否还有更早的行）。`items` 字段与 GET 的 `history` 相同；没有更早记录时 `hasMore=false`。

`PUT /api/v1/overview/thresholds` 仅管理员可用（写路径由 RoleTokens 拦在控制器之前），body 为 `{version, thresholds}`：

- 取值范围：`failureRate` 为 0–1 的有限数，`failureRateMinSample` 1–100000，`backlogPending` 1–1000000，`onlineMin` 1–1000；越界、缺字段或类型不符返回 400 `invalid_alert_thresholds`。
- `version` 必须等于当前版本，否则返回 409 `alert_threshold_version_conflict`：先提交的人成功，后提交的人重新读取再改，而不是悄悄覆盖。写入在事务里 `SELECT … FOR UPDATE` 比对版本、更新单行并把 `version+1`。
- 成功返回与 GET 相同的结构（新版本 + 最新 20 条 + 总数）。同一事务向 `alert_threshold_audit` 追加一行，记录操作者、前后版本与写入后的完整阈值；`updatedBy` 取本次请求的登录身份。审计行不清理；GET 只回最近 20 条，`historyTotal` 给出真实总数，更早的行由 audit 接口向前翻。
- 阈值与概览计数由同一条 SQL 读出，改完下一次轮询（最长 5 秒）生效；`thresholds` 同时回给前端用于染色与文案，前端不保存常量。

管理台在概览顶部说明句旁显示「调整阈值」，仅 `admin` 可见。表单把失败率按百分比输入（保留一位小数），保存时换算为 0–1 的比例；其余三项为整数。表单显示「当前 vN · 最近更新 …」和变更审计列表（版本前后、操作者、时间与四项阈值），标题为「已显示 N / 共 M 条，最新在前」；还有更早记录时提供「加载更早变更」，按最旧 `sequence` 追加且不重复，失败就地提示并保留已加载行。保存成功后回到最新 20 条；400/409 在表单内就地提示，不关闭已填内容。operator/viewer 只读文案，没有按钮，直接调写接口同样被拒。

## 告警钻取 · ALERT-DRILL-01

告警只说"哪里越线"，接下来要能落到"具体是哪几条"。钻取目标由服务端下发（`target:{tab,status}`），页面不自己维护映射：

- `delivery_backlog` → 投递页 + `status=pending`（看等待投递）；
- `delivery_failure_rate` → 投递页 + `status=failed`（看失败投递），并可再由失败原因收一层；
- `devices_online` → 设备页 + `status=offline`（只看启用且离线的一类；停用设备可另选「已停用」）；
- `mqtt_connection` → `target` 为 `null`，不给按钮。传输不可用在管理台没有对应的列表筛选，给一个跳转等于假装能查。

只有真的存在对应筛选时才给目标：这是"不发明能力"的同一原则。页面在跳转前清空投递页上一次的搜索条件（来源/设备/收件窗口），否则"查看等待投递"会被旧筛选悄悄收窄成另一批结果；状态筛选取代而不是叠加。设备页的筛选在外壳上完成：钻取前会清掉壳层的单设备选择，状态筛选取代而不是叠加，避免"某一台 + 某一类"叠加出空列表。

设备列表由 `GET /devices` 按页读取，状态筛选、名称/ID 搜索、排序和页大小都由服务端处理，不再在页面内过滤已返回的集合；因此「离线」就是服务端 `enabled && !online` 的那一类，与概览 `devices_online` 的"启用且在线"口径互补。搜索按字面量匹配名称或设备 ID，`%`/`_` 不是通配符；排序支持创建时间、名称、最近连接及升降序，从未连接的设备在两种方向都排最后；页大小可选 12/24/48/96。`total` 始终是整表，`matched` 与三类计数按搜索结果统计。条件变化会回到第一页；筛出空结果时给出「没有匹配…的设备」或「没有…设备」，并提供清空搜索/查看全部设备。

"失败投递"还能再收一层：概览列出今日失败原因，每条带 `lastError` 筛选跳转，见下一节。

管理台实现上只是把目标交给外壳：先备好筛选再切页签，页签的侦听器带着新条件读一次，不会先读一遍全量再读一遍筛选。目标为 `null` 时不渲染按钮，告警仍照常显示。

## 失败原因 · DELIVERY-FAILURE-REASONS-01

`failureReasons` 是今日失败投递按 `deliveries.last_error` 的归并，形如 `[{code,count}]`，按 `count` 降序、同数量按 `code` 升序。口径与 `today.failedDeliveries` **同源**：今日失败数由这个归并求和得出（SQL 里 `today_failed = sum(failed_today.n)`），所以"合计 N 条"与告警里用到的失败率不可能对不上。测试直接断言两者相等，而不是各写一遍。

`code` 的取值是代码里写死的字符串集合（`ack_timeout`、`broker_unavailable`、`busy`、`storage_error`、`time_unavailable`、`expired`、`invalid`、`cancelled_by_admin`），所以归并结果天然有界，不设截断——截断会让"合计"与列表对不上。历史行没有记录原因时归为 `unknown`，并且 `lastError=unknown` 在列表侧落回 `last_error IS NULL`，保证"1 条未记录原因"点进去真的能看到那 1 条。

管理台把 `code` 显示成中文，每条右侧有「只看这类」，跳到投递页并带上 `status=failed` + `lastError=<code>`。原因下拉只在状态选为「投递失败」时出现：其它状态下它没有意义，显示出来只会让人以为有一层看不见的筛选。切换状态时原因会被清掉，否则就成了看不见的筛选条件。

## 历史趋势 · OVERVIEW-TREND-01

概览页“运行趋势”读取 `GET /api/v1/overview/trend?hours=24&bucket=1h`，与快照接口分开：快照每 5 秒轮询，趋势只在切换范围或点“刷新趋势”时读取，避免长窗口每 5 秒重扫。

| 参数 | 约束 |
| --- | --- |
| `hours` | 1–168，默认 24 |
| `bucket` | `15m`/`1h`/`6h`/`1d`，默认 `1h`；`hours×3600` 必须能被桶秒整除 |
| 桶数 | `hours×3600÷bucketSeconds`，超过 400 返回 400 `invalid_request` |
| `reason` | 可选，按投递历史的 `last_error` 收窄失败序列；去空白后 1–64 字符，空值或超长返回 400 `invalid_request`，未知原因返回空失败序列而不是错误 |

返回 `asOf`、`from`、`to`、`hours`、`bucket`、`bucketSeconds`、`reason`、`failureReasons`、`totals{messages,deliveries,failures,allFailures}` 和固定长度的 `points[]`。每个点包含 `at`（UTC 整点桶起点）、`messages`、`deliveries`、`failures`、`partial`。**最后一桶是当前未结束的桶**（`partial=true`），数值会随刷新增长，页面对此有明确文案；空窗口仍返回完整网格，页面据此区分“没有数据”和“没有窗口”。

`failureReasons` 是窗口内全部失败事件按 `coalesce(last_error,'unknown')` 的归并，形如 `[{code,count}]`，按 `count` 降序、同数量按 `code` 升序；它**始终覆盖整个窗口**，不受 `reason` 影响。`totals.allFailures` 由这个归并求和得出，因此两者同源。`reason` 为空时 `totals.failures = totals.allFailures`；带 `reason` 时只有失败序列与 `totals.failures` 收窄，`failureReasons` 与 `allFailures` 仍保留窗口全量，供页面切换原因。

口径：

- `messages`/`deliveries` 与今日统计同源：按消息 `received_at` 归桶、排除 `metadata.test=true`、来源时间不移动归日、未来收件不计。
- `failures` 来自 `delivery_history` 中 `status='failed'` 且 `kind<>'baseline'` 的记录，按 `recorded_at` 归桶。它是“进入失败的次数”，不是“当前失败按收件桶回看”；迁移前的 baseline 不计为失败事件，因此窗口早于该表建立时无法还原。`failureReasons` 使用同一批事件；`last_error` 为空的旧行归入 `unknown`。
- 三个序列都只覆盖仍保留的记录：保留策略清理后对应桶会变空，趋势不是永久累计。
- 桶按固定 origin（`2000-01-01T00:00:00Z`）用 `date_bin` 对齐，因此 15 分钟与 6 小时桶在不同窗口间一致；不使用 `date_trunc`，它不支持任意间隔。

## 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=OverviewAlertsTest,ConsoleOverviewFlowTest test
python3 tools/run_local.py build
# 同级 04 项目，使用实际编译 Vue 和回环 RAM HTTP 夹具
bash tools/ci/test_overview_ui.sh
```

HTTP/独立 PostgreSQL 专项覆盖空数据/鉴权、配置时区（测试使用 `Asia/Shanghai`）的当地日界与 `dayStart`、配置非法时启动失败、旧来源时间/未来收件、在线 90 秒阈值/停用重合、当前失败比例/跨日积压与只读重复请求；阈值配置专项覆盖默认 v1 与空历史、管理员写入推进版本并写审计、`updated_by` 取登录身份、旧版本 409、`failureRate` 越界与 `failureRateMinSample=0` 的 400、新阈值立即改变概览判定与 `threshold` 数值、operator 写 403；审计读取专项覆盖 25 条下最新 20 条与 `historyTotal`、用最旧 `sequence` 作 `before` 只取更早 5 条且不重复、`size=5` 与游标组合的边界、空页 `hasMore=false`、`before=0`/`size=0`/`size=101` 的 400、无令牌 401；告警专项覆盖空设备不报、只有停用设备不报、积压 99/100 边界、失败率 19 条样本不判定、2/20=10% 命中、无启用设备离线、`comparison` 方向与 `target` 下的页签/状态；失败原因专项覆盖折行合计等于今日失败数、多寡排序、测试消息与昨日失败被排除；列表专项覆盖按原因收窄、`unknown` 落回 NULL、未知原因返回空结果、非法原因与既有非法参数同样 400、导出与列表筛同一批；`OverviewAlertsTest` 是纯函数测试，直接覆盖夹具起不来的分支（MQTT 不可用是唯一的 `error` 且不带 `target`、告警顺序、各阈值边界、默认阈值与自定义阈值对同一快照给出不同结论）。趋势专项覆盖固定桶数、最后一桶 partial、收件归桶、测试消息/未来收件排除、失败事件归桶、窗口与桶数上限 400/401、原因折行排序与合计、`reason` 只收窄失败序列且保留窗口折行、未知原因返回空序列、空值/空白/超长原因 400。浏览器检查覆盖数据、MQTT 异常（且不给它按钮）、加载/重复点击、空/失败/恢复、可见轮询、导航/退出的迟到响应、401、阈值文案与样本门槛、调整阈值到 v2 后概览文案同步（夹具核对写入值）、三处越线各自成条、上调阈值后同一比例不再告警、告警消除、三条告警各自钻取到已筛选列表（夹具核对请求真的带 `status`）、失败原因命名与折行合计、原因钻取到只有这类失败（核对请求同时带 `status` 与 `lastError`）、清空筛选后请求不再带任何筛选、趋势 24/28 桶与范围切换、趋势空/失败/恢复、趋势原因折行与计数、点选原因后只收窄失败列并保留窗口总数（核对请求带 `reason`）、清空原因后请求不再带筛选、阈值审计 1 条时显示真实总数、25 条时首屏 20 条并提供「加载更早变更」、按最旧游标追加到 25 条后按钮消失（核对请求带 `before`）、390px 下概览、告警按钮、原因行与趋势图不溢出（审计列表未在该视口单独断言）、按响应时区显示当地日期而不是截取 UTC 字符串；夹具不是 CORE、Broker 或设备联调。实际结果与配套提交见 [04 台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。

规范沉淀：统计口径属于本项目契约；沿用已有测试和生命周期流程，无需修改通用 Skill。
