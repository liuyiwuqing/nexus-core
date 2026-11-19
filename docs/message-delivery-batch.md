# 投递批量撤销与重试 · MSG-BATCH-01/02 + DEVICE-QUEUE-CLEAR-01

状态：CORE 侧软件已实现（2026-11-19），隔离 HTTP/PostgreSQL 与真实 Vue 页面验证通过；单条消息、跨消息和单设备待发队列三种范围共用同一套判定。

一条消息会为每台启用设备各建一条投递。此前撤销和重试只能进单条投递的时间线操作，设备一多就要重复点 N 次，而且每次都要看一条时间线。本项提供按消息的批量入口，**不新增批量范围**：能批量的条件与单条接口完全一致。

## 1. 边界

| 已实现 | 未保留或未做 |
| --- | --- |
| 按消息批量撤销「从未开始发送」的投递 | 不撤销已预留、已发送、已回执或已结束的投递 |
| 按消息批量重试「已失败」的投递 | 不给超时/过期/已读消息重开投递 |
| 只读预演（`dryRun=true`）报告每条被跳过的原因 | 预演不核对 Broker 是否在线，也不预测设备会不会拒绝 |
| 写入需确认令牌 | 不做后台批量任务；一次请求只处理当前消息 |
| 每条改动照常写入 `delivery_history` | 不新建迁移，不改投递表结构，不发撤回命令 |

批量操作**不是**「发一条新消息撤回旧消息」：设备已经保存或展示的内容不会消失，相关限制见 [投递工作流](delivery-workflow.md) 与 [投递历史](console-delivery-history.md)。

## 2. 接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/v1/messages/{messageId}/deliveries/cancel?dryRun=true` | 只读核对可撤销条数与被跳过原因 |
| `POST /api/v1/messages/{messageId}/deliveries/cancel?confirm=CANCEL_MESSAGE_DELIVERIES` | 执行撤销 |
| `POST /api/v1/messages/{messageId}/deliveries/retry?dryRun=true` | 只读核对可重试条数与被跳过原因 |
| `POST /api/v1/messages/{messageId}/deliveries/retry?confirm=RETRY_MESSAGE_DELIVERIES` | 执行重试 |

两个动作共用一种响应：

```json
{
  "messageId": "…", "action": "cancel", "dryRun": false, "applied": 2,
  "counts": { "deliveries": 4, "candidates": 2, "skipped": 2, "remaining": 0 },
  "candidates": [ { "eventId": "…", "deviceId": "…", "status": "pending", "attempts": 0 } ],
  "skipped":    [ { "eventId": "…", "deviceId": "…", "status": "pending", "attempts": 1, "reason": "attempted" } ],
  "asOf": "2026-11-18T01:00:00Z"
}
```

`counts.candidates` 是**本次请求开始时**能处理的条数，`applied` 是实际改动的条数，`counts.remaining` 是请求结束后仍然可处理的条数。三者不一致只可能因为并发：期间有投递被 worker 预留或被设备回执推进。

`dryRun` 缺省为 `false`；不带确认令牌的非预演请求返回 `400 delivery_batch_confirmation_required`。若消息的投递超过 500 条，返回 `409 batch_too_large` 而不是静默截断——那通常意味着异常扇出，应由管理员到投递页核对。

## 3. 判定条件与跳过原因

撤销沿用单条接口的 `CAN_CANCEL`：`status='pending' AND attempts=0 AND NOT cancel_blocked AND expires_at>now() AND read_at IS NULL AND device.enabled`。
重试沿用 `CAN_RETRY`：`status='failed' AND expires_at>now() AND read_at IS NULL AND device.enabled`。

跳过原因是上面每个合取项的**否定**，按同一顺序判定，因此「`reason` 为空」与「可处理」在构造上等价，不存在第二份会漂移的规则：

| 撤销 | 含义 |
| --- | --- |
| `not_pending` | 状态已不是等待投递 |
| `attempted` | 已经预留过至少一次发送 |
| `cancel_blocked` | 历史记录永久禁止撤销（曾预留或曾离开 pending） |
| `message_expired` | 消息已过期 |
| `message_read` | 消息已读 |
| `device_disabled` | 设备已停用 |

| 重试 | 含义 |
| --- | --- |
| `not_failed` | 状态不是失败 |
| `message_expired` | 消息已过期 |
| `message_read` | 消息已读 |
| `device_disabled` | 设备已停用 |

写入是**一条** `UPDATE … RETURNING event_id`，不是逐条循环：与 `claim()` 争用的行会自然不再匹配，`RETURNING` 给出真正改动的集合，而不是回显候选列表。事务内先分类、再更新、再分类，`skipped` 用更新后的原因，因此竞态里的行会显示「已经尝试过发送」而不是原来的「可撤销」。

## 4. 权限与审计

- 撤销/重试与单条接口同级：管理员与 operator 可用，viewer 只读。`RoleTokens.permits` 的 operator 白名单新增 `POST /api/v1/messages/{uuid}/deliveries/{cancel|retry}`。
- 每条改动仍由 `delivery_history` 触发器记录 `state_changed`（撤销）或 `retry_requested`（重试），时间线因此与单条操作完全一致。
- 批量本身没有单独的「批量审计」记录：`delivery_history` 是逐条事实，HTTP 层审计仍只记请求结果，两者不互相替代。

## 5. 管理台

消息详情底部新增「投递批量操作…」折叠区（`MessageDeliveryBatch.vue`，不新增导航页，十二页与 h1 指纹不变）。展开后并发读取两个计划，分别显示「可撤销 N 条」「可重试 N 条」和按原因归并的跳过摘要，例如「跳过 2 条：已经尝试过发送 1、已经开始投递 1」。只有存在候选时才出现确认按钮；写入后自动重新核对，并给出「已撤销 2 条未开始的投递；2 条未撤销，原因见下方核对」。

读取失败时清空旧计划并显示错误，不把上一次的条数和错误并排显示成「还能撤销 N 条」。

## 6. 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=MessageDeliveryBatchFlowTest test
# 同级 04 项目，使用实际编译 Vue 与回环 RAM HTTP 夹具
bash tools/ci/test_message_batch_ui.sh
```

- `MessageDeliveryBatchFlowTest`（真实 HTTP + 隔离 PostgreSQL/MQTT，4 项）：预演不写库且判定与单条 `canCancel` 一致；撤销只改从未开始的投递并逐条写 `state_changed`；重试恢复失败投递但保留 `cancel_blocked`；确认令牌、角色、消息状态（已读/过期/设备停用）与 500 条上限。
- 完整 `mvn verify`：145 项，0 失败/0 错误，5 项条件跳过。
- 04 `test_message_batch_ui.sh`：16 项浏览器检查，覆盖展开即两次预演且不带确认令牌、跳过原因归并文案、确认令牌、写入后重新核对、无投递、读取失败与恢复、390 px 与折叠。夹具是回环 RAM，不是 CORE、Broker 或设备联调。
- 回归：04 `test_list_filters_ui.sh` 44 项、`test_message_compose_ui.sh` 33 项仍全部通过。

未验收：真实 Broker 上的批量撤销/设备侧表现、真实设备回执竞态、大数据量（接近 500 条）下的耗时，以及生产库。

## 7. 跨消息批量 · MSG-BATCH-02

状态：软件已实现（2026-11-18）。管理台当前页最多选 20 条消息，一次核对整单的可撤销/可重试条数，确认后在同一事务内逐消息应用。

### 7.1 接口与请求

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/v1/deliveries/batch-cancel?dryRun=true` | 整单只读预演撤销 |
| `POST /api/v1/deliveries/batch-cancel?confirm=CANCEL_MESSAGE_DELIVERIES_BATCH` | 执行跨消息撤销 |
| `POST /api/v1/deliveries/batch-retry?dryRun=true` | 整单只读预演重试 |
| `POST /api/v1/deliveries/batch-retry?confirm=RETRY_MESSAGE_DELIVERIES_BATCH` | 执行跨消息重试 |

请求体只有 `messageIds`，重复 ID 在服务端去重：

```json
{ "messageIds": ["…", "…"] }
```

响应保留每条消息的明细，并额外给出整单合计：

```json
{
  "action": "cancel", "dryRun": false, "applied": 4,
  "counts": { "messages": 2, "deliveries": 6, "candidates": 4, "skipped": 2, "remaining": 0 },
  "messages": [ { "messageId": "…", "action": "cancel", "dryRun": false, "applied": 2, "counts": { "deliveries": 3, "candidates": 2, "skipped": 1, "remaining": 0 }, "candidates": [], "skipped": [], "asOf": "…" } ],
  "asOf": "2026-11-18T02:00:00Z"
}
```

### 7.2 整单原子与上限

- 一次最多 20 条消息、合计 500 条投递；超出返回 `409 batch_too_large`，不截断。
- 空 `messageIds` 或全为 `null` 返回 `400 invalid_request`。
- 任一消息不存在（包括已被保留策略清除）返回 `404 message_not_found`，**整单不写入**。写入前的存在性检查与后续更新在同一个 `@Transactional` 内。
- 逐消息复用单条 `run(...)`：同样的判定谓词、同样的跳过原因、同样的 `delivery_history` 记录，不维护第二套规则。消息之间若有重复投递不可能发生——每台设备的 `(message_id, device_id)` 唯一。
- 非预演请求缺少对应批确认令牌返回 `400 delivery_batch_confirmation_required`。

### 7.3 权限与审计

与单条批量相同：管理员与 operator 可写，viewer 只读；`RoleTokens` 的 operator 白名单已覆盖 `POST /api/v1/deliveries/`。每条实际改动仍由 `delivery_history` 触发器写 `state_changed` 或 `retry_requested`，HTTP 层不新增批量审计。

### 7.4 管理台

消息列表当前页每行左侧新增勾选框（仅 operator/管理员可见），选中后出现「已选 N 条」操作条；达到 20 条时显示上限并禁用其余勾选框。打开「批量投递操作…」即并发读取撤销与重试两个预演，显示整单消息数、投递总数、可撤销/可重试条数，以及按原因合并的跳过摘要（例如「跳过 4 条：已经尝试过发送 2、已经开始投递 2」）。确认写入后保留面板并重新预演，显示「已撤销 4 条未开始的投递，涉及 2 条消息」。

换页会立即清空选择；筛选或刷新导致消息离开当前页时，选择收敛到仍可见的消息。整单被拒绝时清空旧计划，只显示错误，不展示「还能撤销 N 条」的过期数字；错误文案明确说明整单未做修改。

### 7.5 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=MessageDeliveryMultiBatchFlowTest test
# 同级 04 项目
bash tools/ci/test_message_batch_ui.sh
```

- `MessageDeliveryMultiBatchFlowTest`（真实 HTTP + 隔离 PostgreSQL/MQTT，4 项）：两次预演不写库且合计正确；一次写入跨越两条消息并逐条写 `delivery_history`；未知消息整单 404 且不写入；20 条消息上限与合计 500 条投递上限。
- 04 `test_message_batch_ui.sh`：30 项浏览器检查，新增当前页多选计数、20 条上限、两个预演请求形状、批确认令牌、整单拒绝文案、清空选择、390 px 适配。
- 完整 CORE 构建：168 项，0 失败/0 错误，5 项条件跳过。

未验收：真实 Broker/设备上的跨消息批量表现、接近上限时的耗时、多管理员并发操作同一批消息，以及生产库。

## 8. 单设备待发队列 · DEVICE-QUEUE-CLEAR-01

状态：软件已实现（2026-11-19）。产品计划里设备危险操作的「清空队列」落地为**只撤销该设备尚未开始发送的投递**：设备卡片上的「待接收 N 条」旁展开「撤销待发…」，范围与卡片计数同为 pending+sent，但已发送等待回执的行不会被假装撤回。

### 8.1 接口与范围

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/v1/devices/{deviceId}/deliveries/cancel?dryRun=true` | 只读核对整条待发队列，列出可撤销与保留原因 |
| `POST /api/v1/devices/{deviceId}/deliveries/cancel?confirm=CANCEL_DEVICE_DELIVERIES` | 执行撤销 |

```json
{
  "deviceId": "dodo-1", "action": "cancel", "dryRun": false, "applied": 2,
  "counts": { "waiting": 3, "candidates": 2, "skipped": 1, "remaining": 0 },
  "candidates": [ { "eventId": "…", "deviceId": "dodo-1", "status": "pending", "attempts": 0 } ],
  "skipped":    [ { "eventId": "…", "deviceId": "dodo-1", "status": "sent", "attempts": 1, "reason": "not_pending" } ],
  "asOf": "2026-11-19T01:00:00Z"
}
```

- `counts.waiting` 是请求开始时的 pending+sent 快照，与设备卡片的「待接收 N 条」同口径；`candidates` 是其中通过 `CAN_CANCEL` 的条数，`applied` 是实际改动，`remaining` 是写入后仍可撤销的条数。正常写入后 `waiting = applied + skipped + remaining`；并发预留或回执可能让快照与结果略有差异，页面以随后的重新预演为准。
- failed 等终态不在本入口的范围，仍走单条重试或按消息/跨消息重试。
- 等待行超过 500 条返回 `409 batch_too_large`，不截断；设备不存在返回 `404 device_not_found`；非预演缺少确认令牌返回 `400 delivery_batch_confirmation_required`。
- 设备路径沿用 CORE 的管理员边界：viewer 与 operator 都返回 403，避免借队列撤销绕开设备权限。

### 8.2 判定与审计

写入复用同一个 `CAN_CANCEL` 和同一条 `UPDATE … RETURNING`，只是把消息范围换成 `d.device_id=? AND d.status IN ('pending','sent')`；跳过原因仍是谓词各合取项的否定。每行改动照常由 `delivery_history` 触发器记录 `state_changed`，`last_error='cancelled_by_admin'`；不新增迁移、不发送撤回包，也不改变已收到/已展示/已读事实。

### 8.3 管理台

卡片上的「待接收 N 条」与「撤销待发…」并排；撤销面板与设置、升级、改名、删除互斥，展开即发一次 `dryRun` 预演，显示「待接收 / 可撤销 / 保留」和保留原因折行（例如「已发送，等待回执 1」）。只有存在候选时才出现确认区，且必须输入设备名；写入成功后显示实际撤销条数、重新预演并把卡片计数更新为服务端结果。读取失败清空旧计划、只显示错误；未知结果先重读队列，不自动重放。收起或 Esc 后焦点回到撤销入口。

### 8.4 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=DeviceDeliveryQueueFlowTest test
# 同级 04 项目，使用实际编译 Vue 与回环 RAM HTTP 夹具
bash tools/ci/test_management_ui.sh
```

- `DeviceDeliveryQueueFlowTest`（真实 HTTP + 隔离 PostgreSQL/MQTT，3 项）：预演只读且 sent/attempted 的保留原因正确；撤销只改该设备 pending+0，其他设备与已发送行不变，历史写入 `state_changed` 并可从筛选复核；确认令牌、viewer/operator 403、未知设备 404 与 501 条队列 409 且不写入。
- 完整 CORE `build` 与 04 `test_management_ui.sh` 结果见任务台账；浏览器夹具是回环 RAM，不是真实 CORE、Broker 或设备。

未验收：真实 Broker/设备上的设备级撤销、撤销后设备侧仍展示旧提醒的行为、接近 500 条时的耗时，以及生产库并发。
